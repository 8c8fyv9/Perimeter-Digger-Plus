package hackerrouter.perimeterdigger.client.build;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Zero-hard-dependency reflection bridge for Litematica + MaLiLib LayerRange + Litematica Printer.
 * Verified against litematica-fabric-26.2-0.28.8, malilib-fabric-26.2-0.29.6, and
 * litematica-printer-26.2-3.2.2. Fails gracefully with clear Chinese messages if absent/incompatible.
 */
public final class LitematicaPrinterBridge {
	private static final String LITEMATICA_DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";
	private static final String SCHEMATIC_WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler";
	private static final String MATERIAL_CACHE_CLASS = "fi.dy.masa.litematica.materials.MaterialCache";
	private static final String LAYER_MODE_CLASS = "fi.dy.masa.malilib.util.LayerMode";
	private static final String PRINTER_CONFIGS_CLASS = "me.aleksilassila.litematica.printer.config.Configs";

	public record Availability(boolean ok, String errorMessage) {
		public static Availability ready() {
			return new Availability(true, "");
		}

		public static Availability fail(String msg) {
			return new Availability(false, msg);
		}
	}

	public record LayerRangeSnapshot(
			Object layerMode,
			Direction.Axis axis,
			int layerSingle,
			boolean followPlayer
	) {}

	public Availability checkAvailability() {
		if (!isModLoadedSafe("litematica")) {
			return Availability.fail("未检测到投影模组 (Litematica)，无法启动自动放置投影");
		}
		try {
			Class.forName(LITEMATICA_DATA_MANAGER);
			Class.forName(SCHEMATIC_WORLD_HANDLER);
			Class.forName(LAYER_MODE_CLASS);
		} catch (ClassNotFoundException | LinkageError e) {
			return Availability.fail("Litematica 版本接口不兼容：" + e.getClass().getSimpleName());
		}
		try {
			Class<?> printerConfigs = Class.forName(PRINTER_CONFIGS_CLASS);
			if (printerConfigs.getField("PRINT_MODE").get(null) == null) {
				return Availability.fail("投影打印机 (litematica-printer) 配置未初始化");
			}
		} catch (ClassNotFoundException e) {
			return Availability.fail("未检测到投影打印机模组 (litematica-printer)，请先安装投影打印机");
		} catch (ReflectiveOperationException | LinkageError e) {
			return Availability.fail("投影打印机接口不兼容：" + e.getClass().getSimpleName());
		}
		if (getSchematicWorld() == null) {
			return Availability.fail("投影世界尚未加载，请先在投影模组中放置并启用投影");
		}
		return Availability.ready();
	}

	public Level getSchematicWorld() {
		try {
			Class<?> cls = Class.forName(SCHEMATIC_WORLD_HANDLER);
			Method m = cls.getMethod("getSchematicWorld");
			Object raw = m.invoke(null);
			return raw instanceof Level level ? level : null;
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	public LayerRangeSnapshot snapshotLayerRange() {
		try {
			Object range = getRenderLayerRange();
			if (range == null) return null;
			Object mode = invokeNoArg(range, "getLayerMode");
			Object axisRaw = invokeNoArg(range, "getAxis");
			Object singleRaw = invokeNoArg(range, "getLayerSingle");
			Object followRaw = invokeOptionalNoArg(range, "shouldFollowPlayer");
			Direction.Axis axis = axisRaw instanceof Direction.Axis a ? a : Direction.Axis.Y;
			int single = singleRaw instanceof Number n ? n.intValue() : 0;
			boolean follow = followRaw instanceof Boolean b && b;
			return new LayerRangeSnapshot(mode, axis, single, follow);
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	public boolean applySingleLayer(int y) {
		try {
			Object range = getRenderLayerRange();
			if (range == null) return false;
			Object followRaw = invokeOptionalNoArg(range, "shouldFollowPlayer");
			if (followRaw instanceof Boolean b && b) {
				Method toggle = range.getClass().getMethod("toggleShouldFollowPlayer");
				toggle.invoke(range);
			}
			Method setAxis = range.getClass().getMethod("setAxis", Direction.Axis.class);
			setAxis.invoke(range, Direction.Axis.Y);

			Class<?> modeClass = Class.forName(LAYER_MODE_CLASS);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object singleMode = Enum.valueOf((Class<Enum>) modeClass, "SINGLE_LAYER");
			Method setMode = range.getClass().getMethod("setLayerMode", modeClass);
			setMode.invoke(range, singleMode);

			Method setSingle = range.getClass().getMethod("setLayerSingle", int.class);
			setSingle.invoke(range, y);
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	public void restoreLayerRange(LayerRangeSnapshot snapshot) {
		if (snapshot == null) return;
		try {
			Object range = getRenderLayerRange();
			if (range == null) return;
			if (snapshot.axis() != null) {
				range.getClass().getMethod("setAxis", Direction.Axis.class).invoke(range, snapshot.axis());
			}
			range.getClass().getMethod("setLayerSingle", int.class).invoke(range, snapshot.layerSingle());
			if (snapshot.layerMode() != null) {
				Class<?> modeClass = Class.forName(LAYER_MODE_CLASS);
				range.getClass().getMethod("setLayerMode", modeClass).invoke(range, snapshot.layerMode());
			}
			Object currentFollow = invokeOptionalNoArg(range, "shouldFollowPlayer");
			if (currentFollow instanceof Boolean b && b != snapshot.followPlayer()) {
				range.getClass().getMethod("toggleShouldFollowPlayer").invoke(range);
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
	}

	public boolean getPrintMode() {
		try {
			Object cfg = getPrinterConfig("PRINT_MODE");
			Object val = cfg == null ? null : invokeNoArg(cfg, "getBooleanValue");
			return val instanceof Boolean b && b;
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	public boolean setPrintMode(boolean enabled) {
		try {
			Object cfg = getPrinterConfig("PRINT_MODE");
			if (cfg == null) return false;
			cfg.getClass().getMethod("setBooleanValue", boolean.class).invoke(cfg, enabled);
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	public double getPrintingRange() {
		try {
			Object cfg = getPrinterConfig("PRINTING_RANGE");
			Object val = cfg == null ? null : invokeNoArg(cfg, "getDoubleValue");
			if (val instanceof Number n) {
				return Math.max(2.0, Math.min(5.5, n.doubleValue()));
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
		return 4.0;
	}

	public boolean isReplaceFluidsEnabled() {
		try {
			Object cfg = getPrinterConfig("REPLACE_FLUIDS_SOURCE_BLOCKS");
			Object val = cfg == null ? null : invokeNoArg(cfg, "getBooleanValue");
			return val instanceof Boolean b && b;
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	public List<ItemStack> getRequiredBuildItems(BlockState state, Level schematicWorld, BlockPos pos) {
		List<ItemStack> result = new ArrayList<>();
		try {
			Class<?> cacheClass = Class.forName(MATERIAL_CACHE_CLASS);
			Object cache = cacheClass.getMethod("getInstance").invoke(null);
			if (cache != null) {
				Object itemsRaw = cacheClass.getMethod("getItems", BlockState.class).invoke(cache, state);
				if (itemsRaw instanceof Collection<?> col && !col.isEmpty()) {
					for (Object o : col) {
						if (o instanceof ItemStack stack && !stack.isEmpty()) {
							result.add(stack.copy());
						}
					}
					if (!result.isEmpty()) return result;
				}
				Object singleRaw = cacheClass
						.getMethod("getRequiredBuildItemForState", BlockState.class, Level.class, BlockPos.class)
						.invoke(cache, state, schematicWorld, pos);
				if (singleRaw instanceof ItemStack stack && !stack.isEmpty()) {
					result.add(stack.copy());
					return result;
				}
			}
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
		Item fallback = state.getBlock().asItem();
		if (fallback != Items.AIR) {
			result.add(new ItemStack(fallback, 1));
		}
		return result;
	}

	private static Object getRenderLayerRange() throws ReflectiveOperationException {
		Class<?> dataManager = Class.forName(LITEMATICA_DATA_MANAGER);
		return dataManager.getMethod("getRenderLayerRange").invoke(null);
	}

	private static Object getPrinterConfig(String fieldName) throws ReflectiveOperationException {
		Class<?> cls = Class.forName(PRINTER_CONFIGS_CLASS);
		Field field = cls.getField(fieldName);
		return field.get(null);
	}

	private static Object invokeNoArg(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static Object invokeOptionalNoArg(Object target, String method) {
		try {
			return target.getClass().getMethod(method).invoke(target);
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	private static boolean isModLoadedSafe(String modId) {
		try {
			return FabricLoader.getInstance().isModLoaded(modId);
		} catch (Throwable ignored) {
			return false;
		}
	}
}
