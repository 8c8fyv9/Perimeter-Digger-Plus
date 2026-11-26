package hackerrouter.perimeterdigger.client.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Tracks recent safe positions (breadcrumbs) for reverse-backtrack recovery and a sliding
 * position window for detecting in-place oscillation deadlocks (e.g. A <-> B shuffling that
 * defeats single-step stationary checks).
 */
public final class StallRecoveryTracker {
	public record Breadcrumb(int x, int y, int z) {
		public double distanceSq(int ox, int oy, int oz) {
			double dx = x - ox;
			double dy = y - oy;
			double dz = z - oz;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	private record Sample(double x, double y, double z) {}

	private static final int MAX_BREADCRUMBS = 40;
	private static final double MIN_BREADCRUMB_STEP_SQ = 4.0; // >= 2 blocks apart

	private final ArrayDeque<Breadcrumb> breadcrumbs = new ArrayDeque<>();
	private final ArrayDeque<Sample> window = new ArrayDeque<>();

	public void recordBreadcrumb(int x, int y, int z) {
		Breadcrumb last = breadcrumbs.peekLast();
		if (last != null && last.distanceSq(x, y, z) < MIN_BREADCRUMB_STEP_SQ) {
			return;
		}
		breadcrumbs.addLast(new Breadcrumb(x, y, z));
		while (breadcrumbs.size() > MAX_BREADCRUMBS) {
			breadcrumbs.removeFirst();
		}
	}

	/**
	 * Selects a previously visited safe position to reverse-backtrack toward.
	 * Prefers the newest safe breadcrumb in [minDist .. maxDist]; falls back to the farthest
	 * safe breadcrumb at least 3 blocks away, and trims the stuck tail so repeated backtracks
	 * step progressively further back along the trail.
	 */
	public Breadcrumb selectReverseBacktrack(
			int currentX,
			int currentY,
			int currentZ,
			double minDist,
			double maxDist,
			Predicate<Breadcrumb> isSafe
	) {
		if (breadcrumbs.isEmpty()) return null;
		List<Breadcrumb> list = new ArrayList<>(breadcrumbs);
		double minSq = minDist * minDist;
		double maxSq = maxDist * maxDist;

		int chosenIdx = -1;
		for (int i = list.size() - 1; i >= 0; i--) {
			Breadcrumb candidate = list.get(i);
			double dSq = candidate.distanceSq(currentX, currentY, currentZ);
			if (dSq >= minSq && dSq <= maxSq && isSafe.test(candidate)) {
				chosenIdx = i;
				break;
			}
		}
		if (chosenIdx < 0) {
			double bestDistSq = 9.0; // at least 3 blocks away
			for (int i = list.size() - 1; i >= 0; i--) {
				Breadcrumb candidate = list.get(i);
				double dSq = candidate.distanceSq(currentX, currentY, currentZ);
				if (dSq >= bestDistSq && isSafe.test(candidate)) {
					bestDistSq = dSq;
					chosenIdx = i;
				}
			}
		}
		if (chosenIdx < 0) return null;
		Breadcrumb chosen = list.get(chosenIdx);
		// Trim the stuck tail after chosenIdx (and chosen itself) so another backtrack moves further back.
		while (breadcrumbs.size() > chosenIdx) {
			breadcrumbs.removeLast();
		}
		return chosen;
	}

	public int breadcrumbCount() {
		return breadcrumbs.size();
	}

	public void recordSample(double x, double y, double z, int maxSamples) {
		int cap = Math.max(2, maxSamples);
		window.addLast(new Sample(x, y, z));
		while (window.size() > cap) {
			window.removeFirst();
		}
	}

	/**
	 * Returns true when at least {@code requiredSamples} recent samples are recorded and their
	 * entire 3D bounding box fits within {@code maxSpan} blocks on every axis.
	 */
	public boolean isOscillatingConfined(int requiredSamples, double maxSpan) {
		if (window.size() < Math.max(2, requiredSamples)) return false;
		double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (Sample s : window) {
			if (s.x < minX) minX = s.x;
			if (s.x > maxX) maxX = s.x;
			if (s.y < minY) minY = s.y;
			if (s.y > maxY) maxY = s.y;
			if (s.z < minZ) minZ = s.z;
			if (s.z > maxZ) maxZ = s.z;
		}
		return (maxX - minX) <= maxSpan && (maxY - minY) <= maxSpan && (maxZ - minZ) <= maxSpan;
	}

	public void clearWindow() {
		window.clear();
	}

	public void reset() {
		breadcrumbs.clear();
		window.clear();
	}
}
