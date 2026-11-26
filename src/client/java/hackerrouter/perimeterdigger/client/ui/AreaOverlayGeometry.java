package hackerrouter.perimeterdigger.client.ui;

import hackerrouter.perimeterdigger.client.config.DetectedAreaConfig;
import hackerrouter.perimeterdigger.client.config.ScanlineConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure geometry builder for the semi-transparent perimeter selection overlay.
 * Uses inclusive block coordinates [minX..maxX] -> world extent [minX, maxX + 1].
 */
public final class AreaOverlayGeometry {
	private static final double EPS = 0.004;

	public record Quad(
			double x0, double y0, double z0,
			double x1, double y1, double z1,
			double x2, double y2, double z2,
			double x3, double y3, double z3
	) {}

	public record Line(double x0, double y0, double z0, double x1, double y1, double z1) {}

	private static final AreaOverlayGeometry EMPTY = new AreaOverlayGeometry(List.of(), List.of());

	private final List<Quad> quads;
	private final List<Line> lines;

	public AreaOverlayGeometry(List<Quad> quads, List<Line> lines) {
		this.quads = List.copyOf(quads);
		this.lines = List.copyOf(lines);
	}

	public static AreaOverlayGeometry empty() {
		return EMPTY;
	}

	public boolean isEmpty() {
		return quads.isEmpty() && lines.isEmpty();
	}

	public List<Quad> quads() {
		return quads;
	}

	public List<Line> lines() {
		return lines;
	}

	public static AreaOverlayGeometry build(DetectedAreaConfig area, Integer diggingMinY, Integer diggingMaxY) {
		if (area == null) return EMPTY;
		int rawMinY = diggingMinY != null ? diggingMinY : area.boundaryY;
		int rawMaxY = diggingMaxY != null ? diggingMaxY : area.boundaryY;
		int minY = Math.min(rawMinY, rawMaxY);
		int maxY = Math.max(rawMinY, rawMaxY);
		double y0 = minY - EPS;
		double y1 = maxY + 1.0 + EPS;

		if (isSimpleRectangle(area)) {
			return buildBox(area.minX - EPS, y0, area.minZ - EPS, area.maxX + 1.0 + EPS, y1, area.maxZ + 1.0 + EPS);
		}
		return buildPolygon(area.scanlines, y0, y1);
	}

	private static boolean isSimpleRectangle(DetectedAreaConfig area) {
		if (area.scanlines == null || area.scanlines.isEmpty()) return true;
		if ("rectangle".equals(area.boundaryBlock)) return true;
		long expectedRows = (long) area.maxZ - area.minZ + 1L;
		if (area.scanlines.size() != expectedRows) return false;
		for (ScanlineConfig s : area.scanlines) {
			if (s.minX != area.minX || s.maxX != area.maxX) return false;
		}
		return true;
	}

	private static AreaOverlayGeometry buildBox(double x0, double y0, double z0, double x1, double y1, double z1) {
		List<Quad> quads = List.of(
				// Bottom & Top
				new Quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1),
				new Quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0),
				// North (z0) & South (z1)
				new Quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0),
				new Quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1),
				// West (x0) & East (x1)
				new Quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0),
				new Quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1)
		);
		List<Line> lines = List.of(
				// Bottom perimeter
				new Line(x0, y0, z0, x1, y0, z0),
				new Line(x1, y0, z0, x1, y0, z1),
				new Line(x1, y0, z1, x0, y0, z1),
				new Line(x0, y0, z1, x0, y0, z0),
				// Top perimeter
				new Line(x0, y1, z0, x1, y1, z0),
				new Line(x1, y1, z0, x1, y1, z1),
				new Line(x1, y1, z1, x0, y1, z1),
				new Line(x0, y1, z1, x0, y1, z0),
				// Vertical corners
				new Line(x0, y0, z0, x0, y1, z0),
				new Line(x1, y0, z0, x1, y1, z0),
				new Line(x1, y0, z1, x1, y1, z1),
				new Line(x0, y0, z1, x0, y1, z1)
		);
		return new AreaOverlayGeometry(quads, lines);
	}

	private static AreaOverlayGeometry buildPolygon(List<ScanlineConfig> scanlines, double y0, double y1) {
		Map<Integer, List<int[]>> byZ = new HashMap<>();
		for (ScanlineConfig s : scanlines) {
			int lo = Math.min(s.minX, s.maxX);
			int hi = Math.max(s.minX, s.maxX);
			byZ.computeIfAbsent(s.z, ignored -> new ArrayList<>()).add(new int[]{lo, hi});
		}
		for (Map.Entry<Integer, List<int[]>> entry : byZ.entrySet()) {
			entry.setValue(mergeIntervals(entry.getValue()));
		}

		List<Quad> quads = new ArrayList<>();
		List<Line> lines = new ArrayList<>();
		List<Integer> zs = new ArrayList<>(byZ.keySet());
		zs.sort(Integer::compareTo);

		for (int z : zs) {
			List<int[]> row = byZ.get(z);
			List<int[]> northRow = byZ.getOrDefault(z - 1, List.of());
			List<int[]> southRow = byZ.getOrDefault(z + 1, List.of());
			double z0 = z;
			double z1 = z + 1.0;

			for (int[] span : row) {
				double x0 = span[0];
				double x1 = span[1] + 1.0;
				// Top and bottom strips
				quads.add(new Quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1));
				quads.add(new Quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0));

				// West & East walls of this interval
				quads.add(new Quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0));
				quads.add(new Quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1));
				lines.add(new Line(x0, y0, z0, x0, y0, z1));
				lines.add(new Line(x0, y1, z0, x0, y1, z1));
				lines.add(new Line(x1, y0, z0, x1, y0, z1));
				lines.add(new Line(x1, y1, z0, x1, y1, z1));

				// Exposed North walls (span minus northRow)
				for (int[] seg : subtract(span, northRow)) {
					double sx0 = seg[0], sx1 = seg[1] + 1.0;
					quads.add(new Quad(sx0, y0, z0, sx0, y1, z0, sx1, y1, z0, sx1, y0, z0));
					lines.add(new Line(sx0, y0, z0, sx1, y0, z0));
					lines.add(new Line(sx0, y1, z0, sx1, y1, z0));
					lines.add(new Line(sx0, y0, z0, sx0, y1, z0));
					lines.add(new Line(sx1, y0, z0, sx1, y1, z0));
				}
				// Exposed South walls (span minus southRow)
				for (int[] seg : subtract(span, southRow)) {
					double sx0 = seg[0], sx1 = seg[1] + 1.0;
					quads.add(new Quad(sx0, y0, z1, sx1, y0, z1, sx1, y1, z1, sx0, y1, z1));
					lines.add(new Line(sx0, y0, z1, sx1, y0, z1));
					lines.add(new Line(sx0, y1, z1, sx1, y1, z1));
					lines.add(new Line(sx0, y0, z1, sx0, y1, z1));
					lines.add(new Line(sx1, y0, z1, sx1, y1, z1));
				}
			}
		}
		return new AreaOverlayGeometry(quads, lines);
	}

	private static List<int[]> mergeIntervals(List<int[]> raw) {
		raw.sort(Comparator.comparingInt(a -> a[0]));
		List<int[]> merged = new ArrayList<>();
		for (int[] cur : raw) {
			if (merged.isEmpty() || cur[0] > merged.getLast()[1] + 1) {
				merged.add(new int[]{cur[0], cur[1]});
			} else {
				merged.getLast()[1] = Math.max(merged.getLast()[1], cur[1]);
			}
		}
		return merged;
	}

	private static List<int[]> subtract(int[] span, List<int[]> mask) {
		List<int[]> result = new ArrayList<>();
		int cursor = span[0];
		int end = span[1];
		for (int[] m : mask) {
			if (m[1] < cursor) continue;
			if (m[0] > end) break;
			if (m[0] > cursor) {
				result.add(new int[]{cursor, m[0] - 1});
			}
			cursor = m[1] + 1;
			if (cursor > end) break;
		}
		if (cursor <= end) {
			result.add(new int[]{cursor, end});
		}
		return result;
	}
}
