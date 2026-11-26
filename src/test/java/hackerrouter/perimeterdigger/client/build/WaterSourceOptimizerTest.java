package hackerrouter.perimeterdigger.client.build;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class WaterSourceOptimizerTest {
	@Test
	void achievesExactPerimeterLowerBoundForRectangles() {
		assertExactRectangle(1, 1, 1);
		assertExactRectangle(1, 2, 2);
		assertExactRectangle(1, 8, 5);
		assertExactRectangle(1, 9, 5);
		assertExactRectangle(9, 1, 5);
		assertExactRectangle(2, 8, 5);
		assertExactRectangle(8, 2, 5);
		assertExactRectangle(3, 6, 5);
		assertExactRectangle(10, 10, 10);
		assertExactRectangle(20, 40, 30);
		assertExactRectangle(40, 20, 30);
	}

	@Test
	void leveragesPreexistingAndWaterloggedBoundarySourcesForFree() {
		// 2x2 pool where two diagonal corners are already waterlogged sources -> 0 buckets needed.
		Set<WaterSourceOptimizer.Cell> targets = Set.of(
				new WaterSourceOptimizer.Cell(1, 0),
				new WaterSourceOptimizer.Cell(0, 1)
		);
		Set<WaterSourceOptimizer.Cell> pre = Set.of(
				new WaterSourceOptimizer.Cell(0, 0),
				new WaterSourceOptimizer.Cell(1, 1)
		);
		var plan0 = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(targets, Set.of(), pre, null));
		assertEquals(0, plan0.totalSeedPlacements());
		assertEquals(2, plan0.freeConvertedCells());
		assertEquals(1, plan0.estimatedWaves());

		// 10x10 pool with an entire preexisting edge at x=-1 -> only 5 buckets needed!
		Set<WaterSourceOptimizer.Cell> pool = rect(0, 0, 10, 10);
		Set<WaterSourceOptimizer.Cell> leftEdge = new HashSet<>();
		for (int z = 0; z < 10; z++) {
			leftEdge.add(new WaterSourceOptimizer.Cell(-1, z));
		}
		var planEdge = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(pool, Set.of(), leftEdge, null));
		assertTrue(planEdge.totalSeedPlacements() <= 6, "Expected <=6 seeds with pre-filled edge, got " + planEdge.totalSeedPlacements());
		assertFullCoverage(pool, Set.of(), leftEdge, null, planEdge);
	}

	@Test
	void respectsBlockedWallsFromWaterloggedStairsAndSolidBackFaces() {
		// 1x2 target at (1,0), (2,0); preexisting waterlogged stair at (0,0) has solid back facing (1,0).
		Set<WaterSourceOptimizer.Cell> targets = Set.of(
				new WaterSourceOptimizer.Cell(1, 0),
				new WaterSourceOptimizer.Cell(2, 0)
		);
		Set<WaterSourceOptimizer.Cell> pre = Set.of(new WaterSourceOptimizer.Cell(0, 0));
		BiPredicate<WaterSourceOptimizer.Cell, WaterSourceOptimizer.Cell> passable = (from, to) ->
				!(from.equals(new WaterSourceOptimizer.Cell(0, 0)) && to.equals(new WaterSourceOptimizer.Cell(1, 0)));

		var plan = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(targets, Set.of(), pre, passable));
		// Because (0,0)->(1,0) is blocked, (1,0) cannot borrow (0,0), so both (1,0) and (2,0) must be placed.
		assertEquals(2, plan.totalSeedPlacements());
		assertFullCoverage(targets, Set.of(), pre, passable, plan);
	}

	@Test
	void forcesUnsupportedCellsAndReusesThemAsHorizontalSources() {
		// 3x3 pool where the main diagonal has air underneath (forced), and the other 6 cells have solid floors.
		Set<WaterSourceOptimizer.Cell> forced = Set.of(
				new WaterSourceOptimizer.Cell(0, 0),
				new WaterSourceOptimizer.Cell(1, 1),
				new WaterSourceOptimizer.Cell(2, 2)
		);
		Set<WaterSourceOptimizer.Cell> convertible = new LinkedHashSet<>(rect(0, 0, 3, 3));
		convertible.removeAll(forced);

		var plan = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(convertible, forced, Set.of(), null));
		assertEquals(3, plan.forcedSeeds().size());
		assertEquals(0, plan.optimizedSeeds().size());
		assertEquals(6, plan.freeConvertedCells());
		assertFullCoverage(convertible, forced, Set.of(), null, plan);
	}

	@Test
	void optimizesIrregularShapesLShapePillarPoolAndMoatRingWithZeroRedundancy() {
		// 1) L-shape: 8x8 minus 5x5 top-right quadrant (39 cells)
		Set<WaterSourceOptimizer.Cell> lShape = new LinkedHashSet<>();
		for (int z = 0; z < 8; z++) {
			for (int x = 0; x < 8; x++) {
				if (x < 3 || z < 3) {
					lShape.add(new WaterSourceOptimizer.Cell(x, z));
				}
			}
		}
		var lPlan = WaterSourceOptimizer.plan(WaterSourceOptimizer.WaterLayerInput.simple(lShape));
		assertFullCoverage(lShape, Set.of(), Set.of(), null, lPlan);
		assertTrue(lPlan.totalSeedPlacements() <= 11, "Expected <=11 seeds for 39-cell L-shape, got " + lPlan.totalSeedPlacements());
		assertNoRedundantSeeds(lShape, Set.of(), null, lPlan);

		// 2) 10x10 pool with a 2x2 pillar in the center (96 cells)
		Set<WaterSourceOptimizer.Cell> pillarPool = new LinkedHashSet<>(rect(0, 0, 10, 10));
		pillarPool.removeAll(rect(4, 4, 2, 2));
		var pillarPlan = WaterSourceOptimizer.plan(WaterSourceOptimizer.WaterLayerInput.simple(pillarPool));
		assertFullCoverage(pillarPool, Set.of(), Set.of(), null, pillarPlan);
		assertTrue(pillarPlan.totalSeedPlacements() <= 14, "Expected <=14 seeds for 96-cell pillar pool, got " + pillarPlan.totalSeedPlacements());
		assertNoRedundantSeeds(pillarPool, Set.of(), null, pillarPlan);

		// 3) Two disconnected 4x4 pools on the same layer (32 cells -> 4 + 4 = 8 seeds)
		Set<WaterSourceOptimizer.Cell> twoPools = new LinkedHashSet<>(rect(0, 0, 4, 4));
		twoPools.addAll(rect(10, 10, 4, 4));
		var twoPlan = WaterSourceOptimizer.plan(WaterSourceOptimizer.WaterLayerInput.simple(twoPools));
		assertEquals(8, twoPlan.totalSeedPlacements());
		assertFullCoverage(twoPools, Set.of(), Set.of(), null, twoPlan);
	}

	@Test
	void seedsOnlyAlongClickableWallsForWaterOverWaterUpperLayers() {
		// Upper layer of a 10x10 and 20x40 3D water/bubble-column tank:
		// Floor is water (not clickable), only cells bordering the solid perimeter walls are clickable!
		Set<WaterSourceOptimizer.Cell> pool10 = rect(0, 0, 10, 10);
		var plan10 = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(
				pool10, Set.of(), Set.of(), null,
				c -> c.x() == 0 || c.x() == 9 || c.z() == 0 || c.z() == 9));
		assertTrue(plan10.totalSeedPlacements() <= 11, "Expected <=11 wall seeds for 10x10, got " + plan10.totalSeedPlacements());
		for (var seed : plan10.allSeeds()) {
			assertTrue(seed.x() == 0 || seed.x() == 9 || seed.z() == 0 || seed.z() == 9,
					"Interior unclickable seed chosen: " + seed);
		}
		assertFullCoverage(pool10, Set.of(), Set.of(), null, plan10);

		Set<WaterSourceOptimizer.Cell> pool20x40 = rect(0, 0, 20, 40);
		var plan20x40 = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(
				pool20x40, Set.of(), Set.of(), null,
				c -> c.x() == 0 || c.x() == 19 || c.z() == 0 || c.z() == 39));
		assertTrue(plan20x40.totalSeedPlacements() <= 31, "Expected <=31 wall seeds for 20x40, got " + plan20x40.totalSeedPlacements());
		for (var seed : plan20x40.allSeeds()) {
			assertTrue(seed.x() == 0 || seed.x() == 19 || seed.z() == 0 || seed.z() == 39,
					"Interior unclickable seed chosen: " + seed);
		}
		assertFullCoverage(pool20x40, Set.of(), Set.of(), null, plan20x40);
	}

	private static void assertExactRectangle(int width, int length, int expectedSeeds) {
		Set<WaterSourceOptimizer.Cell> cells = rect(0, 0, width, length);
		var plan = WaterSourceOptimizer.plan(WaterSourceOptimizer.WaterLayerInput.simple(cells));
		assertEquals(expectedSeeds, plan.totalSeedPlacements(), width + "x" + length + " seed count");
		assertEquals(width * length - expectedSeeds, plan.savedPlacements());
		assertFullCoverage(cells, Set.of(), Set.of(), null, plan);
	}

	private static void assertFullCoverage(
			Set<WaterSourceOptimizer.Cell> convertible,
			Set<WaterSourceOptimizer.Cell> forced,
			Set<WaterSourceOptimizer.Cell> preexisting,
			BiPredicate<WaterSourceOptimizer.Cell, WaterSourceOptimizer.Cell> edge,
			WaterSourceOptimizer.Plan plan
	) {
		Set<WaterSourceOptimizer.Cell> seeds = new HashSet<>(preexisting);
		seeds.addAll(plan.allSeeds());
		for (WaterSourceOptimizer.Cell f : forced) {
			assertTrue(seeds.contains(f), "Forced cell missing from seeds: " + f);
		}
		var closure = WaterSourceOptimizer.computeClosure(seeds, convertible, edge);
		for (WaterSourceOptimizer.Cell c : convertible) {
			assertTrue(closure.filled().contains(c), "Uncovered convertible cell: " + c);
		}
	}

	private static void assertNoRedundantSeeds(
			Set<WaterSourceOptimizer.Cell> convertible,
			Set<WaterSourceOptimizer.Cell> preexisting,
			BiPredicate<WaterSourceOptimizer.Cell, WaterSourceOptimizer.Cell> edge,
			WaterSourceOptimizer.Plan plan
	) {
		for (WaterSourceOptimizer.Cell s : plan.optimizedSeeds()) {
			Set<WaterSourceOptimizer.Cell> without = new HashSet<>(preexisting);
			without.addAll(plan.allSeeds());
			without.remove(s);
			var closure = WaterSourceOptimizer.computeClosure(without, convertible, edge);
			boolean allCovered = true;
			for (WaterSourceOptimizer.Cell c : convertible) {
				if (!closure.filled().contains(c)) {
					allCovered = false;
					break;
				}
			}
			assertFalse(allCovered, "Seed " + s + " is redundant!");
		}
	}

	private static Set<WaterSourceOptimizer.Cell> rect(int x0, int z0, int w, int l) {
		Set<WaterSourceOptimizer.Cell> set = new LinkedHashSet<>();
		for (int z = z0; z < z0 + l; z++) {
			for (int x = x0; x < x0 + w; x++) {
				set.add(new WaterSourceOptimizer.Cell(x, z));
			}
		}
		return set;
	}
}
