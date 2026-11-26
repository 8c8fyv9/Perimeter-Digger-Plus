package hackerrouter.perimeterdigger.client.ui;

import hackerrouter.perimeterdigger.client.config.DetectedAreaConfig;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.ScanlineConfig;
import hackerrouter.perimeterdigger.client.config.WorldConfigManager;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/**
 * Renders the configured perimeter digging area using Minecraft 26.2's native Gizmo pipeline via
 * Fabric's LevelRenderEvents.COLLECT_SUBMITS.
 *
 * GizmoFeatureRenderer performs double-precision camera subtraction internally, keeping the overlay
 * rock-solid at 10M+ coordinates and compatible with Sodium and Reverse-Z depth testing.
 */
public final class AreaSelectionRenderer {
	/** Translucent cyan fill (ARGB, alpha ~25%) for depth-tested volume walls/caps. */
	private static final int FILL_ARGB = 0x4028C8FA;
	/** Crisp bright cyan wireframe for visible (depth-tested) perimeter edges. */
	private static final int LINE_ARGB = 0xF055F5FF;
	/** Subtle see-through wireframe so outer bounds remain legible behind terrain. */
	private static final int SEE_THROUGH_LINE_ARGB = 0x6055F5FF;
	private static final float LINE_WIDTH = 3.0F;
	private static final float SEE_THROUGH_LINE_WIDTH = 1.5F;

	private DetectedAreaConfig cachedArea;
	private Integer cachedMinY;
	private Integer cachedMaxY;
	private int cachedScanlineCount = -1;
	private int cachedBoundsHash;
	private DrawableGizmoPrimitives cachedDepthTested;
	private DrawableGizmoPrimitives cachedSeeThrough;

	public void collectSubmits(LevelRenderContext context, WorldConfigManager configs) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		PerimeterConfig config;
		try {
			config = configs.get();
		} catch (RuntimeException ignored) {
			return;
		}
		if (config == null || config.functions == null || !config.functions.renderArea || config.detectedArea == null) {
			return;
		}
		String currentDimension = mc.level.dimension().identifier().toString();
		if (config.detectedArea.dimension != null
				&& !config.detectedArea.dimension.isBlank()
				&& !config.detectedArea.dimension.equals(currentDimension)) {
			return;
		}
		ensureCache(config);
		if (cachedDepthTested == null) return;

		CameraRenderState cam = context.levelState().cameraRenderState;
		if (cam == null) return;
		cachedDepthTested.submit(context.submitNodeCollector(), cam, false);
		if (cachedSeeThrough != null) {
			cachedSeeThrough.submit(context.submitNodeCollector(), cam, true);
		}
	}

	private void ensureCache(PerimeterConfig config) {
		DetectedAreaConfig area = config.detectedArea;
		int scanCount = area.scanlines == null ? 0 : area.scanlines.size();
		int boundsHash = Objects.hash(area.dimension, area.minX, area.maxX, area.minZ, area.maxZ,
				area.boundaryY, area.boundaryBlock, area.columnCount, scanlinesHash(area.scanlines));
		if (area == cachedArea
				&& Objects.equals(config.diggingMinY, cachedMinY)
				&& Objects.equals(config.diggingMaxY, cachedMaxY)
				&& scanCount == cachedScanlineCount
				&& boundsHash == cachedBoundsHash) {
			return;
		}
		cachedArea = area;
		cachedMinY = config.diggingMinY;
		cachedMaxY = config.diggingMaxY;
		cachedScanlineCount = scanCount;
		cachedBoundsHash = boundsHash;

		AreaOverlayGeometry geometry = AreaOverlayGeometry.build(area, config.diggingMinY, config.diggingMaxY);
		if (geometry.isEmpty()) {
			cachedDepthTested = null;
			cachedSeeThrough = null;
			return;
		}
		DrawableGizmoPrimitives depthTested = new DrawableGizmoPrimitives();
		for (AreaOverlayGeometry.Quad q : geometry.quads()) {
			Vec3 v0 = new Vec3(q.x0(), q.y0(), q.z0());
			Vec3 v1 = new Vec3(q.x1(), q.y1(), q.z1());
			Vec3 v2 = new Vec3(q.x2(), q.y2(), q.z2());
			Vec3 v3 = new Vec3(q.x3(), q.y3(), q.z3());
			// DEBUG_FILLED_BOX enables backface culling; emit both windings so faces are visible from inside and outside.
			depthTested.addQuad(v0, v1, v2, v3, FILL_ARGB);
			depthTested.addQuad(v0, v3, v2, v1, FILL_ARGB);
		}
		DrawableGizmoPrimitives seeThrough = new DrawableGizmoPrimitives();
		for (AreaOverlayGeometry.Line l : geometry.lines()) {
			Vec3 a = new Vec3(l.x0(), l.y0(), l.z0());
			Vec3 b = new Vec3(l.x1(), l.y1(), l.z1());
			depthTested.addLine(a, b, LINE_ARGB, LINE_WIDTH);
			seeThrough.addLine(a, b, SEE_THROUGH_LINE_ARGB, SEE_THROUGH_LINE_WIDTH);
		}
		cachedDepthTested = depthTested;
		cachedSeeThrough = seeThrough;
	}

	private static int scanlinesHash(List<ScanlineConfig> scanlines) {
		if (scanlines == null || scanlines.isEmpty()) return 0;
		int h = 1;
		for (ScanlineConfig s : scanlines) {
			h = 31 * h + s.z;
			h = 31 * h + s.minX;
			h = 31 * h + s.maxX;
		}
		return h;
	}
}
