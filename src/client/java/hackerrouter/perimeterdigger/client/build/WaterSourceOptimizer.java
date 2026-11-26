package hackerrouter.perimeterdigger.client.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Pure 2D water-source seed minimizer for Minecraft's 2-neighbor water source conversion
 * (zero Minecraft runtime dependencies).
 *
 * <p>A convertible cell {@code c} turns into a water source when at least two orthogonally
 * adjacent passable neighbors are water sources and the block underneath {@code c} is solid
 * or already a water source (tracked separately via {@code convertibleTargets} vs
 * {@code forcedTargets}).</p>
 */
public final class WaterSourceOptimizer {
	private static final int[][] DIRS = {
			{ 1, 0 },
			{ -1, 0 },
			{ 0, 1 },
			{ 0, -1 }
	};
	private static final int MAX_EXACT_CANDIDATES = 64;

	public record Cell(int x, int z) implements Comparable<Cell> {
		@Override
		public int compareTo(Cell o) {
			int cmp = Integer.compare(this.z, o.z);
			return cmp != 0 ? cmp : Integer.compare(this.x, o.x);
		}
	}

	public record WaterLayerInput(
			Set<Cell> convertibleTargets,
			Set<Cell> forcedTargets,
			Set<Cell> preexistingSources,
			BiPredicate<Cell, Cell> passableEdge,
			Predicate<Cell> canPlaceDirectly
	) {
		public WaterLayerInput(
				Set<Cell> convertibleTargets,
				Set<Cell> forcedTargets,
				Set<Cell> preexistingSources,
				BiPredicate<Cell, Cell> passableEdge
		) {
			this(convertibleTargets, forcedTargets, preexistingSources, passableEdge, null);
		}

		public static WaterLayerInput simple(Set<Cell> convertibleTargets) {
			return new WaterLayerInput(convertibleTargets, Set.of(), Set.of(), null, null);
		}
	}

	public record Plan(
			List<Cell> forcedSeeds,
			List<Cell> optimizedSeeds,
			int totalTargetCells,
			int freeConvertedCells,
			int estimatedWaves
	) {
		public List<Cell> allSeeds() {
			List<Cell> all = new ArrayList<>(forcedSeeds.size() + optimizedSeeds.size());
			all.addAll(forcedSeeds);
			all.addAll(optimizedSeeds);
			return all;
		}

		public int totalSeedPlacements() {
			return forcedSeeds.size() + optimizedSeeds.size();
		}

		public int savedPlacements() {
			return Math.max(0, totalTargetCells - totalSeedPlacements());
		}
	}

	private WaterSourceOptimizer() {}

	/**
	 * Computes a minimal or near-minimal ordered seed set covering all required water source cells
	 * on a single Y layer.
	 */
	public static Plan plan(WaterLayerInput input) {
		if (input == null) {
			return new Plan(List.of(), List.of(), 0, 0, 0);
		}
		BiPredicate<Cell, Cell> edge = input.passableEdge() != null ? input.passableEdge() : (a, b) -> true;
		Predicate<Cell> placeable = input.canPlaceDirectly() != null ? input.canPlaceDirectly() : c -> true;
		Set<Cell> preexisting = input.preexistingSources() == null ? Set.of() : new HashSet<>(input.preexistingSources());

		Set<Cell> forced = new LinkedHashSet<>();
		if (input.forcedTargets() != null) {
			for (Cell c : input.forcedTargets()) {
				if (!preexisting.contains(c)) {
					forced.add(c);
				}
			}
		}

		Set<Cell> convertible = new LinkedHashSet<>();
		if (input.convertibleTargets() != null) {
			for (Cell c : input.convertibleTargets()) {
				if (!preexisting.contains(c) && !forced.contains(c)) {
					convertible.add(c);
				}
			}
		}

		int totalTargets = forced.size() + convertible.size();
		List<Cell> forcedSeeds = orderNearest(forced);
		if (convertible.isEmpty()) {
			return new Plan(forcedSeeds, List.of(), totalTargets, 0, 0);
		}

		// Step 1: Free closure from preexisting sources + forced seeds (zero extra buckets).
		Set<Cell> baseSources = new HashSet<>(preexisting);
		baseSources.addAll(forced);
		ClosureResult initialClosure = computeClosure(baseSources, convertible, edge);
		int freeConverted = initialClosure.convertedCount();

		Set<Cell> uncovered = new LinkedHashSet<>();
		for (Cell c : convertible) {
			if (!initialClosure.filled().contains(c)) {
				uncovered.add(c);
			}
		}
		if (uncovered.isEmpty()) {
			return new Plan(forcedSeeds, List.of(), totalTargets, freeConverted, initialClosure.waves());
		}

		// Step 2: Decompose remaining uncovered cells into 4-connected passable components.
		List<Set<Cell>> components = splitComponents(uncovered, edge);
		components.sort(Comparator.comparing(WaterSourceOptimizer::minCell));

		List<Cell> optimizedSeeds = new ArrayList<>();
		for (Set<Cell> comp : components) {
			Set<Cell> boundarySources = findAdjacentSources(comp, initialClosure.filled(), edge);
			List<Cell> compSeeds = trySolvePureRectangle(comp, boundarySources, edge, placeable);
			if (compSeeds == null) {
				compSeeds = solveGeneralComponent(comp, boundarySources, edge, placeable);
			}
			optimizedSeeds.addAll(compSeeds);
		}

		// Final verification & exact wave-depth calculation.
		Set<Cell> finalSeeds = new HashSet<>(baseSources);
		finalSeeds.addAll(optimizedSeeds);
		ClosureResult finalClosure = computeClosure(finalSeeds, convertible, edge);
		if (finalClosure.filled().size() < baseSources.size() + convertible.size()) {
			// Defensive fallback for remaining placeable cells.
			for (Cell c : convertible) {
				if (!finalClosure.filled().contains(c) && placeable.test(c)) {
					optimizedSeeds.add(c);
					finalSeeds.add(c);
				}
			}
			finalClosure = computeClosure(finalSeeds, convertible, edge);
		}

		return new Plan(
				List.copyOf(forcedSeeds),
				List.copyOf(optimizedSeeds),
				totalTargets,
				freeConverted,
				finalClosure.waves()
		);
	}

	/**
	 * Exact optimal solver for an unobstructed W x L rectangle with no pre-existing boundary
	 * sources. Strictly achieves the perimeter lower bound ceil((W + L) / 2):
	 * - W diagonal seeds fill the W x W prefix square (and create a 2x2 infinite source after seed #2).
	 * - 1 seed every 2 columns across the remaining L - W columns fills 2W cells per bucket.
	 */
	private static List<Cell> trySolvePureRectangle(
			Set<Cell> comp,
			Set<Cell> boundarySources,
			BiPredicate<Cell, Cell> edge,
			Predicate<Cell> placeable
	) {
		if (!boundarySources.isEmpty() || comp.isEmpty()) {
			return null;
		}
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (Cell c : comp) {
			if (c.x() < minX) minX = c.x();
			if (c.x() > maxX) maxX = c.x();
			if (c.z() < minZ) minZ = c.z();
			if (c.z() > maxZ) maxZ = c.z();
		}
		int nx = maxX - minX + 1;
		int nz = maxZ - minZ + 1;
		if ((long) nx * (long) nz != comp.size()) {
			return null;
		}
		// Verify all internal orthogonal edges inside the rectangle are mutually passable.
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				Cell a = new Cell(x, z);
				if (x + 1 <= maxX) {
					Cell b = new Cell(x + 1, z);
					if (!edge.test(a, b) || !edge.test(b, a)) return null;
				}
				if (z + 1 <= maxZ) {
					Cell b = new Cell(x, z + 1);
					if (!edge.test(a, b) || !edge.test(b, a)) return null;
				}
			}
		}

		boolean xIsShort = nx <= nz;
		int shortSide = xIsShort ? nx : nz;
		int longSide = xIsShort ? nz : nx;
		List<Cell> seeds = new ArrayList<>((shortSide + longSide + 1) / 2);

		// Prefix square: main diagonal (0,0), (1,1), ..., (W-1, W-1).
		for (int i = 0; i < shortSide; i++) {
			seeds.add(mapRectCell(minX, minZ, xIsShort, i, i));
		}

		// Remaining strip of width (L - W): 1 seed every 2 columns at row 0.
		int lastPlacedCol = shortSide - 1;
		for (int col = shortSide + 1; col < longSide; col += 2) {
			seeds.add(mapRectCell(minX, minZ, xIsShort, 0, col));
			lastPlacedCol = col;
		}
		if (lastPlacedCol < longSide - 1) {
			seeds.add(mapRectCell(minX, minZ, xIsShort, 0, longSide - 1));
		}
		if (allPlaceable(seeds, placeable)) {
			return seeds;
		}

		// Water-over-water layer (floor is water, only solid perimeter walls are clickable):
		// Seed every other cell along two adjacent perimeter edges (L-edge). Full row + full col
		// cascades via 2-neighbor conversion across the entire W x L interior in ~(W + L)/2 buckets!
		int[] rowOptions = { 0, shortSide - 1 };
		int[] colOptions = { 0, longSide - 1 };
		for (int rEdge : rowOptions) {
			for (int cEdge : colOptions) {
				List<Cell> edgeSeeds = buildAdjacentEdgeSeeds(minX, minZ, xIsShort, shortSide, longSide, rEdge, cEdge);
				if (allPlaceable(edgeSeeds, placeable)) {
					return edgeSeeds;
				}
			}
		}
		return null;
	}

	private static List<Cell> buildAdjacentEdgeSeeds(
			int minX, int minZ, boolean xIsShort, int shortSide, int longSide, int rEdge, int cEdge
	) {
		List<Cell> seeds = new ArrayList<>((shortSide + longSide) / 2 + 2);
		// Corner first, then stride-2 along row rEdge, then stride-2 along col cEdge.
		int cDir = cEdge == 0 ? 1 : -1;
		int lastC = cEdge;
		for (int step = 0; step < longSide; step += 2) {
			lastC = cEdge + cDir * step;
			seeds.add(mapRectCell(minX, minZ, xIsShort, rEdge, lastC));
		}
		int endC = cEdge == 0 ? longSide - 1 : 0;
		if (lastC != endC) {
			seeds.add(mapRectCell(minX, minZ, xIsShort, rEdge, endC));
		}

		int rDir = rEdge == 0 ? 1 : -1;
		int lastR = rEdge;
		for (int step = 2; step < shortSide; step += 2) {
			lastR = rEdge + rDir * step;
			seeds.add(mapRectCell(minX, minZ, xIsShort, lastR, cEdge));
		}
		int endR = rEdge == 0 ? shortSide - 1 : 0;
		if (shortSide > 1 && lastR != endR) {
			seeds.add(mapRectCell(minX, minZ, xIsShort, endR, cEdge));
		}
		return seeds;
	}

	private static boolean allPlaceable(List<Cell> cells, Predicate<Cell> placeable) {
		for (Cell c : cells) {
			if (!placeable.test(c)) return false;
		}
		return true;
	}

	private static Cell mapRectCell(int minX, int minZ, boolean xIsShort, int r, int c) {
		return xIsShort ? new Cell(minX + r, minZ + c) : new Cell(minX + c, minZ + r);
	}

	/**
	 * Lookahead closure-greedy solver + reverse redundancy pruning for arbitrary irregular shapes,
	 * pools with pillars, partial preexisting edges, or internal wall blocks.
	 */
	private static List<Cell> solveGeneralComponent(
			Set<Cell> comp,
			Set<Cell> boundarySources,
			BiPredicate<Cell, Cell> edge,
			Predicate<Cell> placeable
	) {
		Set<Cell> filled = new HashSet<>(boundarySources);
		Set<Cell> uncovered = new LinkedHashSet<>(comp);
		List<Cell> seeds = new ArrayList<>();
		Cell lastSeed = null;

		while (!uncovered.isEmpty()) {
			List<Cell> candidates = collectCandidates(uncovered, filled, edge, placeable, lastSeed);
			Cell best = null;
			int bestDelta = -1;
			int bestFrontier = -1;
			long bestDistSq = Long.MAX_VALUE;

			for (Cell cand : candidates) {
				EvalScore score = evaluateCandidate(cand, filled, uncovered, edge);
				long distSq = lastSeed == null ? 0L : distSq(cand, lastSeed);
				if (score.delta() > bestDelta
						|| (score.delta() == bestDelta && score.newFrontier() > bestFrontier)
						|| (score.delta() == bestDelta && score.newFrontier() == bestFrontier && distSq < bestDistSq)
						|| (score.delta() == bestDelta && score.newFrontier() == bestFrontier && distSq == bestDistSq
						&& (best == null || cand.compareTo(best) < 0))) {
					best = cand;
					bestDelta = score.delta();
					bestFrontier = score.newFrontier();
					bestDistSq = distSq;
				}
			}

			if (best == null) {
				break;
			}
			seeds.add(best);
			lastSeed = best;
			applySeedIncrementally(best, filled, uncovered, edge);
		}

		// Reverse redundancy pruning: remove any seed whose omission still yields full coverage.
		// Bounded to medium components so huge fragmented layers (e.g. 2500 cells / 1000 seeds) stay <30ms on render thread.
		if (seeds.size() > 1 && seeds.size() <= 96 && comp.size() <= 600) {
			for (int i = seeds.size() - 1; i >= 0; i--) {
				Cell removed = seeds.remove(i);
				Set<Cell> testSources = new HashSet<>(boundarySources);
				testSources.addAll(seeds);
				ClosureResult check = computeClosure(testSources, comp, edge);
				if (check.convertedCount() < comp.size() - seeds.size()) {
					// Not all comp cells covered without `removed`; restore it.
					seeds.add(i, removed);
				} else {
					boolean allCovered = true;
					for (Cell c : comp) {
						if (!check.filled().contains(c)) {
							allCovered = false;
							break;
						}
					}
					if (!allCovered) {
						seeds.add(i, removed);
					}
				}
			}
		}

		return seeds;
	}

	private static List<Cell> collectCandidates(
			Set<Cell> uncovered,
			Set<Cell> filled,
			BiPredicate<Cell, Cell> edge,
			Predicate<Cell> placeable,
			Cell lastSeed
	) {
		Set<Cell> frontier1 = new LinkedHashSet<>();
		for (Cell u : uncovered) {
			for (int[] d : DIRS) {
				Cell n = new Cell(u.x() + d[0], u.z() + d[1]);
				if (filled.contains(n) && edge.test(n, u)) {
					frontier1.add(u);
					break;
				}
			}
		}

		if (frontier1.isEmpty()) {
			// Start from a minimum-degree placeable corner cell of the remaining component.
			Cell corner = pickCorner(uncovered, edge, placeable);
			return corner == null ? List.of() : List.of(corner);
		}

		// Distance <= 2 from filled: frontier1 + their passable uncovered neighbors (captures diagonals & 1-gap bridges).
		Set<Cell> candSet = new LinkedHashSet<>();
		for (Cell f : frontier1) {
			if (placeable.test(f)) candSet.add(f);
			for (int[] d : DIRS) {
				Cell n = new Cell(f.x() + d[0], f.z() + d[1]);
				if (uncovered.contains(n) && edge.test(n, f) && placeable.test(n)) {
					candSet.add(n);
				}
			}
		}
		if (candSet.isEmpty()) {
			// If no placeable cell is within dist <= 2 of frontier1 (e.g. disconnected wall island), pick any placeable cell.
			for (Cell u : uncovered) {
				if (placeable.test(u)) candSet.add(u);
			}
		}
		if (candSet.isEmpty()) {
			return List.of();
		}

		List<Cell> list = new ArrayList<>(candSet);
		if (list.size() <= MAX_EXACT_CANDIDATES) {
			return list;
		}
		// Pre-rank large frontiers by number of adjacent frontier1 cells (higher bridge potential) and proximity.
		list.sort((a, b) -> {
			int adjA = countNeighborsIn(a, frontier1, edge);
			int adjB = countNeighborsIn(b, frontier1, edge);
			if (adjA != adjB) return Integer.compare(adjB, adjA);
			if (lastSeed != null) {
				int cmpD = Long.compare(distSq(a, lastSeed), distSq(b, lastSeed));
				if (cmpD != 0) return cmpD;
			}
			return a.compareTo(b);
		});
		return list.subList(0, MAX_EXACT_CANDIDATES);
	}

	private static Cell pickCorner(Set<Cell> uncovered, BiPredicate<Cell, Cell> edge, Predicate<Cell> filter) {
		Cell corner = null;
		int minDeg = Integer.MAX_VALUE;
		for (Cell u : uncovered) {
			if (!filter.test(u)) continue;
			int deg = 0;
			for (int[] d : DIRS) {
				Cell n = new Cell(u.x() + d[0], u.z() + d[1]);
				if (uncovered.contains(n) && edge.test(u, n)) deg++;
			}
			if (deg < minDeg || (deg == minDeg && (corner == null || u.compareTo(corner) < 0))) {
				minDeg = deg;
				corner = u;
			}
		}
		return corner;
	}

	private static int countNeighborsIn(Cell cell, Set<Cell> target, BiPredicate<Cell, Cell> edge) {
		int count = 0;
		for (int[] d : DIRS) {
			Cell n = new Cell(cell.x() + d[0], cell.z() + d[1]);
			if (target.contains(n) && edge.test(cell, n)) count++;
		}
		return count;
	}

	private record EvalScore(int delta, int newFrontier) {}

	private static EvalScore evaluateCandidate(
			Cell seed,
			Set<Cell> filled,
			Set<Cell> uncovered,
			BiPredicate<Cell, Cell> edge
	) {
		Set<Cell> added = new HashSet<>();
		Deque<Cell> queue = new ArrayDeque<>();
		added.add(seed);
		queue.add(seed);

		while (!queue.isEmpty()) {
			Cell cur = queue.poll();
			for (int[] d : DIRS) {
				Cell nb = new Cell(cur.x() + d[0], cur.z() + d[1]);
				if (!uncovered.contains(nb) || added.contains(nb)) continue;
				if (!edge.test(cur, nb)) continue;
				int sourceNeighbors = 0;
				for (int[] d2 : DIRS) {
					Cell s = new Cell(nb.x() + d2[0], nb.z() + d2[1]);
					if ((filled.contains(s) || added.contains(s)) && edge.test(s, nb)) {
						sourceNeighbors++;
						if (sourceNeighbors >= 2) break;
					}
				}
				if (sourceNeighbors >= 2) {
					added.add(nb);
					queue.add(nb);
				}
			}
		}

		// Count distinct remaining uncovered cells that now touch `added` (new 1-neighbor frontier).
		Set<Cell> touched = new HashSet<>();
		for (Cell a : added) {
			for (int[] d : DIRS) {
				Cell nb = new Cell(a.x() + d[0], a.z() + d[1]);
				if (uncovered.contains(nb) && !added.contains(nb) && edge.test(a, nb)) {
					touched.add(nb);
				}
			}
		}
		return new EvalScore(added.size(), touched.size());
	}

	private static void applySeedIncrementally(
			Cell seed,
			Set<Cell> filled,
			Set<Cell> uncovered,
			BiPredicate<Cell, Cell> edge
	) {
		Deque<Cell> queue = new ArrayDeque<>();
		filled.add(seed);
		uncovered.remove(seed);
		queue.add(seed);

		while (!queue.isEmpty()) {
			Cell cur = queue.poll();
			for (int[] d : DIRS) {
				Cell nb = new Cell(cur.x() + d[0], cur.z() + d[1]);
				if (!uncovered.contains(nb) || !edge.test(cur, nb)) continue;
				int sourceNeighbors = 0;
				for (int[] d2 : DIRS) {
					Cell s = new Cell(nb.x() + d2[0], nb.z() + d2[1]);
					if (filled.contains(s) && edge.test(s, nb)) {
						sourceNeighbors++;
						if (sourceNeighbors >= 2) break;
					}
				}
				if (sourceNeighbors >= 2) {
					filled.add(nb);
					uncovered.remove(nb);
					queue.add(nb);
				}
			}
		}
	}

	public record ClosureResult(Set<Cell> filled, int convertedCount, int waves) {}

	/**
	 * Simulates Minecraft's wave-by-wave 2-neighbor source conversion starting from {@code sources}.
	 * Exposed as package-private/public helper so tests can independently verify any seed set.
	 */
	public static ClosureResult computeClosure(
			Set<Cell> sources,
			Set<Cell> convertible,
			BiPredicate<Cell, Cell> passableEdge
	) {
		BiPredicate<Cell, Cell> edge = passableEdge != null ? passableEdge : (a, b) -> true;
		Set<Cell> filled = new HashSet<>(sources);
		Map<Cell, Integer> neighborCounts = new HashMap<>();
		List<Cell> currentWave = new ArrayList<>();

		// Initialize neighbor counts from starting sources.
		for (Cell c : convertible) {
			if (filled.contains(c)) continue;
			int cnt = 0;
			for (int[] d : DIRS) {
				Cell nb = new Cell(c.x() + d[0], c.z() + d[1]);
				if (filled.contains(nb) && edge.test(nb, c)) {
					cnt++;
				}
			}
			if (cnt >= 2) {
				currentWave.add(c);
			} else if (cnt > 0) {
				neighborCounts.put(c, cnt);
			}
		}

		int converted = 0;
		int waves = 0;
		while (!currentWave.isEmpty()) {
			waves++;
			for (Cell c : currentWave) {
				if (filled.add(c)) {
					converted++;
				}
			}
			List<Cell> nextWave = new ArrayList<>();
			for (Cell newlyFilled : currentWave) {
				for (int[] d : DIRS) {
					Cell nb = new Cell(newlyFilled.x() + d[0], newlyFilled.z() + d[1]);
					if (!convertible.contains(nb) || filled.contains(nb)) continue;
					if (!edge.test(newlyFilled, nb)) continue;
					int updated = neighborCounts.merge(nb, 1, Integer::sum);
					if (updated == 2) {
						nextWave.add(nb);
					}
				}
			}
			currentWave = nextWave;
		}

		return new ClosureResult(filled, converted, waves);
	}

	private static List<Set<Cell>> splitComponents(Set<Cell> cells, BiPredicate<Cell, Cell> edge) {
		List<Set<Cell>> components = new ArrayList<>();
		Set<Cell> visited = new HashSet<>();
		for (Cell start : cells) {
			if (!visited.add(start)) continue;
			Set<Cell> comp = new LinkedHashSet<>();
			Deque<Cell> q = new ArrayDeque<>();
			q.add(start);
			comp.add(start);
			while (!q.isEmpty()) {
				Cell cur = q.poll();
				for (int[] d : DIRS) {
					Cell nb = new Cell(cur.x() + d[0], cur.z() + d[1]);
					if (cells.contains(nb) && !visited.contains(nb)
							&& (edge.test(cur, nb) || edge.test(nb, cur))) {
						visited.add(nb);
						comp.add(nb);
						q.add(nb);
					}
				}
			}
			components.add(comp);
		}
		return components;
	}

	private static Set<Cell> findAdjacentSources(
			Set<Cell> comp,
			Set<Cell> filled,
			BiPredicate<Cell, Cell> edge
	) {
		Set<Cell> boundary = new HashSet<>();
		for (Cell c : comp) {
			for (int[] d : DIRS) {
				Cell nb = new Cell(c.x() + d[0], c.z() + d[1]);
				if (filled.contains(nb) && edge.test(nb, c)) {
					boundary.add(nb);
				}
			}
		}
		return boundary;
	}

	private static List<Cell> orderNearest(Set<Cell> cells) {
		if (cells.size() <= 1) {
			return new ArrayList<>(cells);
		}
		List<Cell> remaining = new ArrayList<>(cells);
		remaining.sort(Comparator.naturalOrder());
		List<Cell> ordered = new ArrayList<>(remaining.size());
		Cell cur = remaining.removeFirst();
		ordered.add(cur);
		while (!remaining.isEmpty()) {
			int bestIdx = 0;
			long bestDist = distSq(cur, remaining.getFirst());
			for (int i = 1; i < remaining.size(); i++) {
				long d = distSq(cur, remaining.get(i));
				if (d < bestDist) {
					bestDist = d;
					bestIdx = i;
				}
			}
			cur = remaining.remove(bestIdx);
			ordered.add(cur);
		}
		return ordered;
	}

	private static Cell minCell(Set<Cell> comp) {
		Cell best = null;
		for (Cell c : comp) {
			if (best == null || c.compareTo(best) < 0) best = c;
		}
		return best;
	}

	private static long distSq(Cell a, Cell b) {
		long dx = (long) a.x() - b.x();
		long dz = (long) a.z() - b.z();
		return dx * dx + dz * dz;
	}
}
