package hackerrouter.perimeterdigger.client.detect;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Optional runtime bridge that reads the occupied world-space bounding box of a placed
 * Litematica schematic via reflection (no hard compile-time or runtime dependency on Litematica).
 */
public final class LitematicaFootprintReader {
	private static final String LITEMATICA_MOD_ID = "litematica";
	private static final String DATA_MANAGER_CLASS = "fi.dy.masa.litematica.data.DataManager";
	private static final String REQUIRED_ENABLED_CLASS = "fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled";

	private LitematicaFootprintReader() {}

	public enum ErrorCode {
		NOT_INSTALLED,
		NO_PLACEMENT,
		EMPTY_BOUNDS,
		INCOMPATIBLE
	}

	public record Footprint(
			String placementName,
			int minX,
			int maxX,
			int minY,
			int maxY,
			int minZ,
			int maxZ,
			int subRegionCount,
			int totalPlacements
	) {
		public long columnCount() {
			return ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
		}

		public long blockCount() {
			return columnCount() * ((long) maxY - minY + 1L);
		}
	}

	public record ReadResult(Footprint footprint, ErrorCode errorCode, String errorDetail) {
		public boolean ok() {
			return footprint != null && errorCode == null;
		}

		public static ReadResult success(Footprint footprint) {
			return new ReadResult(footprint, null, null);
		}

		public static ReadResult failure(ErrorCode code, String detail) {
			return new ReadResult(null, code, detail == null ? "" : detail);
		}

		public String userMessage() {
			if (ok()) {
				String name = footprint.placementName() == null || footprint.placementName().isBlank()
						? "未命名投影"
						: footprint.placementName();
				String extra = footprint.totalPlacements() > 1
						? "（共" + footprint.totalPlacements() + "个投影）"
						: "";
				return "已读取投影「" + name + "」" + extra + ": X[" + footprint.minX() + ".." + footprint.maxX()
						+ "] Z[" + footprint.minZ() + ".." + footprint.maxZ()
						+ "] Y[" + footprint.minY() + ".." + footprint.maxY() + "]";
			}
			return switch (errorCode) {
				case NOT_INSTALLED -> "未检测到投影模组 (Litematica)，请先安装投影模组";
				case NO_PLACEMENT -> "未找到已放置的投影，请先在投影模组中放置并选中一个投影";
				case EMPTY_BOUNDS -> errorDetail.isEmpty()
						? "所选投影不包含有效占地边界数据（可能尚未加载完成）"
						: "所选投影「" + errorDetail + "」不包含有效占地边界数据";
				case INCOMPATIBLE -> errorDetail.isEmpty()
						? "投影模组数据格式不兼容，无法自动读取占地坐标"
						: "投影模组数据不兼容（" + errorDetail + "），无法自动读取占地坐标";
			};
		}
	}

	record Candidate(String name, boolean selected, boolean enabled, Footprint footprint) {}

	/** Reads the active/nearest placed Litematica schematic footprint from the live client. */
	public static ReadResult readCurrentFootprint(Minecraft mc) {
		if (!isLitematicaLoaded()) {
			return ReadResult.failure(ErrorCode.NOT_INSTALLED, "");
		}
		Class<?> dataManagerClass;
		try {
			dataManagerClass = Class.forName(DATA_MANAGER_CLASS);
		} catch (ClassNotFoundException e) {
			return ReadResult.failure(ErrorCode.NOT_INSTALLED, "");
		} catch (LinkageError e) {
			return ReadResult.failure(ErrorCode.INCOMPATIBLE, "无法加载 Litematica 类: " + e.getClass().getSimpleName());
		}

		try {
			Object manager = invokeNoArg(dataManagerClass, null, "getSchematicPlacementManager");
			if (manager == null) {
				return ReadResult.failure(ErrorCode.INCOMPATIBLE, "SchematicPlacementManager 为空");
			}
			Object selectedPlacement = invokeOptionalNoArg(manager, "getSelectedSchematicPlacement");
			Object allRaw = invokeOptionalNoArg(manager, "getAllSchematicsPlacements");
			List<Object> placements = new ArrayList<>();
			if (allRaw instanceof Collection<?> collection) {
				for (Object item : collection) {
					if (item != null) placements.add(item);
				}
			} else if (allRaw != null) {
				return ReadResult.failure(ErrorCode.INCOMPATIBLE, "投影列表类型不兼容: " + allRaw.getClass().getSimpleName());
			}
			if (selectedPlacement != null && placements.stream().noneMatch(p -> p == selectedPlacement)) {
				placements.add(0, selectedPlacement);
			}
			if (placements.isEmpty()) {
				return ReadResult.failure(ErrorCode.NO_PLACEMENT, "");
			}

			double px = mc != null && mc.player != null ? mc.player.getX() : 0.0;
			double py = mc != null && mc.player != null ? mc.player.getY() : 0.0;
			double pz = mc != null && mc.player != null ? mc.player.getZ() : 0.0;
			ReadResult extracted = extractFromPlacements(placements, selectedPlacement, px, py, pz);
			if (!extracted.ok()) {
				return extracted;
			}
			return clampToWorldIfNeeded(extracted.footprint(), mc != null ? mc.level : null);
		} catch (IncompatibleLitematicaException e) {
			return ReadResult.failure(ErrorCode.INCOMPATIBLE, e.getMessage());
		} catch (LinkageError | RuntimeException e) {
			String msg = e.getMessage();
			return ReadResult.failure(ErrorCode.INCOMPATIBLE,
					e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg));
		}
	}

	/** Pure reflective extraction helper, testable without Minecraft or Litematica installed. */
	static ReadResult extractFromPlacements(
			List<?> placements,
			Object selectedPlacement,
			double playerX,
			double playerY,
			double playerZ
	) throws IncompatibleLitematicaException {
		if (placements == null || placements.isEmpty()) {
			return ReadResult.failure(ErrorCode.NO_PLACEMENT, "");
		}
		int total = placements.size();
		List<Candidate> validCandidates = new ArrayList<>();
		String firstEmptyName = null;
		IncompatibleLitematicaException lastIncompatible = null;

		for (Object placement : placements) {
			if (placement == null) continue;
			boolean isSelected = placement == selectedPlacement;
			String name = safePlacementName(placement);
			boolean enabled = safePlacementEnabled(placement);
			try {
				List<int[]> boxes = extractBoxesFromPlacement(placement);
				if (boxes.isEmpty()) {
					if (isSelected || firstEmptyName == null) {
						firstEmptyName = name;
					}
					continue;
				}
				Footprint fp = mergeBoxes(name, boxes, total);
				validCandidates.add(new Candidate(name, isSelected, enabled, fp));
			} catch (IncompatibleLitematicaException e) {
				if (isSelected) {
					throw e;
				}
				lastIncompatible = e;
			}
		}

		if (validCandidates.isEmpty()) {
			if (lastIncompatible != null) {
				throw lastIncompatible;
			}
			return ReadResult.failure(ErrorCode.EMPTY_BOUNDS, firstEmptyName == null ? "" : firstEmptyName);
		}

		int chosen = selectCandidateIndex(validCandidates, playerX, playerY, playerZ);
		return ReadResult.success(validCandidates.get(chosen).footprint());
	}

	/** Merges one or more inclusive 3D boxes {@code [x1, y1, z1, x2, y2, z2]} into one footprint. */
	static Footprint mergeBoxes(String placementName, List<int[]> boxes, int totalPlacements) {
		if (boxes == null || boxes.isEmpty()) {
			throw new IllegalArgumentException("No boxes to merge");
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int[] box : boxes) {
			if (box == null || box.length != 6) {
				throw new IllegalArgumentException("Malformed box coordinate array");
			}
			minX = Math.min(minX, Math.min(box[0], box[3]));
			minY = Math.min(minY, Math.min(box[1], box[4]));
			minZ = Math.min(minZ, Math.min(box[2], box[5]));
			maxX = Math.max(maxX, Math.max(box[0], box[3]));
			maxY = Math.max(maxY, Math.max(box[1], box[4]));
			maxZ = Math.max(maxZ, Math.max(box[2], box[5]));
		}
		long width = (long) maxX - minX + 1L;
		long height = (long) maxY - minY + 1L;
		long length = (long) maxZ - minZ + 1L;
		if (width <= 0L || height <= 0L || length <= 0L || width > 100_000L || length > 100_000L) {
			throw new IllegalArgumentException("Placement bounds out of sane range: " + width + "x" + height + "x" + length);
		}
		return new Footprint(placementName == null ? "" : placementName,
				minX, maxX, minY, maxY, minZ, maxZ, boxes.size(), Math.max(1, totalPlacements));
	}

	/**
	 * Selection priority:
	 * 1. Explicitly selected placement in Litematica;
	 * 2. Nearest enabled placement to the player;
	 * 3. Nearest placement of any state to the player.
	 */
	static int selectCandidateIndex(List<Candidate> candidates, double playerX, double playerY, double playerZ) {
		for (int i = 0; i < candidates.size(); i++) {
			if (candidates.get(i).selected()) {
				return i;
			}
		}
		int bestEnabled = -1;
		double bestEnabledDistSq = Double.MAX_VALUE;
		int bestAny = 0;
		double bestAnyDistSq = Double.MAX_VALUE;
		for (int i = 0; i < candidates.size(); i++) {
			Candidate c = candidates.get(i);
			double dSq = distanceSqToFootprint(c.footprint(), playerX, playerY, playerZ);
			if (dSq < bestAnyDistSq) {
				bestAnyDistSq = dSq;
				bestAny = i;
			}
			if (c.enabled() && dSq < bestEnabledDistSq) {
				bestEnabledDistSq = dSq;
				bestEnabled = i;
			}
		}
		return bestEnabled >= 0 ? bestEnabled : bestAny;
	}

	private static double distanceSqToFootprint(Footprint fp, double px, double py, double pz) {
		double cx = Math.max(fp.minX(), Math.min(px, fp.maxX() + 1.0));
		double cy = Math.max(fp.minY(), Math.min(py, fp.maxY() + 1.0));
		double cz = Math.max(fp.minZ(), Math.min(pz, fp.maxZ() + 1.0));
		double dx = px - cx;
		double dy = py - cy;
		double dz = pz - cz;
		return dx * dx + dy * dy + dz * dz;
	}

	private static ReadResult clampToWorldIfNeeded(Footprint fp, Level level) {
		if (level == null) {
			return ReadResult.success(fp);
		}
		int worldMinY = level.dimensionType().minY();
		int worldMaxY = worldMinY + level.dimensionType().height() - 1;
		if (fp.maxY() < worldMinY || fp.minY() > worldMaxY) {
			return ReadResult.failure(ErrorCode.INCOMPATIBLE,
					"投影高度 Y[" + fp.minY() + ".." + fp.maxY() + "] 完全超出当前世界高度范围 [" + worldMinY + ".." + worldMaxY + "]");
		}
		int clampedMinY = Math.max(worldMinY, fp.minY());
		int clampedMaxY = Math.min(worldMaxY, fp.maxY());
		if (clampedMinY == fp.minY() && clampedMaxY == fp.maxY()) {
			return ReadResult.success(fp);
		}
		return ReadResult.success(new Footprint(
				fp.placementName(), fp.minX(), fp.maxX(), clampedMinY, clampedMaxY, fp.minZ(), fp.maxZ(),
				fp.subRegionCount(), fp.totalPlacements()));
	}

	private static boolean isLitematicaLoaded() {
		try {
			return FabricLoader.getInstance().isModLoaded(LITEMATICA_MOD_ID);
		} catch (LinkageError | RuntimeException e) {
			return false;
		}
	}

	private static List<int[]> extractBoxesFromPlacement(Object placement) throws IncompatibleLitematicaException {
		List<int[]> boxes = new ArrayList<>();
		boolean foundAnyBoxApi = false;

		// 1. Prefer enabled sub-region boxes (accounts for placement + subregion origin/rotation/mirror).
		Method getSubRegionBoxes = findSubRegionBoxesMethod(placement.getClass());
		if (getSubRegionBoxes != null) {
			foundAnyBoxApi = true;
			Class<?> enumType = getSubRegionBoxes.getParameterTypes()[0];
			Object enabledEnum = findEnumConstant(enumType, "PLACEMENT_ENABLED");
			Object anyEnum = findEnumConstant(enumType, "ANY");
			if (enabledEnum != null) {
				collectBoxesFromMap(invokeMethod(getSubRegionBoxes, placement, enabledEnum), boxes);
			}
			if (boxes.isEmpty() && anyEnum != null) {
				collectBoxesFromMap(invokeMethod(getSubRegionBoxes, placement, anyEnum), boxes);
			}
		}

		// 2. Fallback to enclosing box (Litematica historically spells this "getEclosingBox"; also check "getEnclosingBox").
		if (boxes.isEmpty()) {
			Method enclosing = findNoArgMethod(placement.getClass(), "getEclosingBox");
			if (enclosing == null) {
				enclosing = findNoArgMethod(placement.getClass(), "getEnclosingBox");
			}
			if (enclosing != null) {
				foundAnyBoxApi = true;
				Object box = invokeMethod(enclosing, placement);
				if (box != null) {
					int[] coords = extractBoxCoords(box);
					if (coords != null) {
						boxes.add(coords);
					}
				}
			}
		}

		if (!foundAnyBoxApi) {
			throw new IncompatibleLitematicaException("缺少 getSubRegionBoxes/getEclosingBox 接口");
		}
		return boxes;
	}

	private static void collectBoxesFromMap(Object mapObj, List<int[]> out) throws IncompatibleLitematicaException {
		if (mapObj == null) return;
		if (!(mapObj instanceof Map<?, ?> map)) {
			throw new IncompatibleLitematicaException("子区域边界返回值不是 Map: " + mapObj.getClass().getSimpleName());
		}
		for (Object box : map.values()) {
			if (box == null) continue;
			int[] coords = extractBoxCoords(box);
			if (coords != null) {
				out.add(coords);
			}
		}
	}

	private static int[] extractBoxCoords(Object box) throws IncompatibleLitematicaException {
		Method getPos1 = findNoArgMethod(box.getClass(), "getPos1");
		Method getPos2 = findNoArgMethod(box.getClass(), "getPos2");
		if (getPos1 == null || getPos2 == null) {
			throw new IncompatibleLitematicaException("Box 缺少 getPos1/getPos2 方法");
		}
		Object p1 = invokeMethod(getPos1, box);
		Object p2 = invokeMethod(getPos2, box);
		if (p1 == null || p2 == null) {
			return null;
		}
		int[] c1 = extractXYZ(p1);
		int[] c2 = extractXYZ(p2);
		return new int[]{c1[0], c1[1], c1[2], c2[0], c2[1], c2[2]};
	}

	private static int[] extractXYZ(Object pos) throws IncompatibleLitematicaException {
		if (pos instanceof Vec3i vec) {
			return new int[]{vec.getX(), vec.getY(), vec.getZ()};
		}
		Method gx = findNoArgMethod(pos.getClass(), "getX");
		Method gy = findNoArgMethod(pos.getClass(), "getY");
		Method gz = findNoArgMethod(pos.getClass(), "getZ");
		if (gx == null || gy == null || gz == null) {
			throw new IncompatibleLitematicaException("坐标对象缺少 getX/getY/getZ: " + pos.getClass().getSimpleName());
		}
		Object x = invokeMethod(gx, pos);
		Object y = invokeMethod(gy, pos);
		Object z = invokeMethod(gz, pos);
		if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) {
			throw new IncompatibleLitematicaException("坐标值不是整数类型");
		}
		return new int[]{nx.intValue(), ny.intValue(), nz.intValue()};
	}

	private static String safePlacementName(Object placement) {
		try {
			Object value = invokeOptionalNoArg(placement, "getName");
			return value == null ? "" : value.toString();
		} catch (IncompatibleLitematicaException e) {
			return "";
		}
	}

	private static boolean safePlacementEnabled(Object placement) {
		try {
			Object value = invokeOptionalNoArg(placement, "isEnabled");
			return !(value instanceof Boolean b) || b;
		} catch (IncompatibleLitematicaException e) {
			return true;
		}
	}

	private static Method findSubRegionBoxesMethod(Class<?> clazz) {
		for (Method m : clazz.getMethods()) {
			if ("getSubRegionBoxes".equals(m.getName()) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isEnum()) {
				return m;
			}
		}
		try {
			Class<?> reqClass = Class.forName(REQUIRED_ENABLED_CLASS);
			return clazz.getMethod("getSubRegionBoxes", reqClass);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	private static Object findEnumConstant(Class<?> enumType, String name) {
		Object[] constants = enumType.getEnumConstants();
		if (constants == null) return null;
		for (Object constant : constants) {
			if (constant instanceof Enum<?> e && name.equals(e.name())) {
				return constant;
			}
		}
		return null;
	}

	private static Method findNoArgMethod(Class<?> clazz, String name) {
		try {
			return clazz.getMethod(name);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private static Object invokeNoArg(Class<?> clazz, Object target, String methodName) throws IncompatibleLitematicaException {
		Method m = findNoArgMethod(clazz, methodName);
		if (m == null) {
			throw new IncompatibleLitematicaException("缺少方法 " + clazz.getSimpleName() + "." + methodName + "()");
		}
		return invokeMethod(m, target);
	}

	private static Object invokeOptionalNoArg(Object target, String methodName) throws IncompatibleLitematicaException {
		Method m = findNoArgMethod(target.getClass(), methodName);
		if (m == null) {
			return null;
		}
		return invokeMethod(m, target);
	}

	private static Object invokeMethod(Method method, Object target, Object... args) throws IncompatibleLitematicaException {
		try {
			return method.invoke(target, args);
		} catch (ReflectiveOperationException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			throw new IncompatibleLitematicaException("调用 " + method.getName() + " 失败: " + cause.getClass().getSimpleName());
		}
	}

	static final class IncompatibleLitematicaException extends Exception {
		IncompatibleLitematicaException(String message) {
			super(message);
		}
	}
}
