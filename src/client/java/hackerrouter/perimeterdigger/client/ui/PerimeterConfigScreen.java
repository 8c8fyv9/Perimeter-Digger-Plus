package hackerrouter.perimeterdigger.client.ui;

import hackerrouter.perimeterdigger.client.config.AdvancedConfig;
import hackerrouter.perimeterdigger.client.config.DetectedAreaConfig;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.PositionConfig;
import hackerrouter.perimeterdigger.client.config.UnloadingPointConfig;
import hackerrouter.perimeterdigger.client.config.WorldConfigManager;
import hackerrouter.perimeterdigger.client.detect.BoundaryDetector;
import hackerrouter.perimeterdigger.client.detect.LitematicaFootprintReader;
import hackerrouter.perimeterdigger.client.state.AutomationController;
import hackerrouter.perimeterdigger.client.state.AutomationState;
import hackerrouter.perimeterdigger.client.state.RetaliationPolicy;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 5-tab full-coverage client configuration & control screen opened by holding F9. */
public final class PerimeterConfigScreen extends Screen {
	private enum Tab { AREA, FUNCTIONS, ITEMS, POINTS, ADVANCED }

	private record PointSpec(String key, String label) {}

	private static final List<PointSpec> POINT_SPECS = List.of(
			new PointSpec("consumable_supply_point", "消耗品补给点"),
			new PointSpec("durability_supply_point", "耐久装备补给点"),
			new PointSpec("bed_point", "床位置"),
			new PointSpec("perimeter_portal_overworld", "矿区传送门(主世界)"),
			new PointSpec("perimeter_portal_nether", "矿区传送门(下界)"),
			new PointSpec("repair_portal_overworld", "修补传送门(主世界)"),
			new PointSpec("repair_portal_nether", "修补传送门(下界)"),
			new PointSpec("furnace_row_start", "熔炉排起点"),
			new PointSpec("furnace_row_end", "熔炉排终点")
	);

	private final WorldConfigManager configs;
	private final AutomationController controller;
	private final BoundaryDetector detector = new BoundaryDetector();
	private PerimeterConfig draft;

	private Tab activeTab = Tab.AREA;
	private int pointIndex = 0;
	private int unloadIndex = 0;
	private int advancedPageIndex = 0;

	// Retained text values across tab/subpage switches
	private String x1Text = "";
	private String z1Text = "";
	private String x2Text = "";
	private String z2Text = "";
	private String minYText = "";
	private String maxYText = "";
	private boolean areaInputsEdited = false;

	private String sealingText = "";
	private String scaffoldText = "";
	private String foodsText = "";
	private String whitelistText = "";
	private String miningWhitelistText = "";
	private String miningBlacklistText = "";

	private String pointXText = "";
	private String pointYText = "";
	private String pointZText = "";

	private String activeUnloadKey = null;
	private String unloadNameText = "default";
	private String unloadXText = "";
	private String unloadYText = "";
	private String unloadZText = "";

	private final Map<String, String> advancedTexts = new LinkedHashMap<>();

	// Active tab widgets
	private EditBox x1Box, z1Box, x2Box, z2Box, minYBox, maxYBox;
	private EditBox sealingBox, scaffoldBox, foodsBox, whitelistBox, miningWhitelistBox, miningBlacklistBox;
	private EditBox pointXBox, pointYBox, pointZBox;
	private EditBox unloadNameBox, unloadXBox, unloadYBox, unloadZBox;
	private final Map<String, EditBox> activeAdvancedBoxes = new LinkedHashMap<>();

	private String message = "";
	private boolean messageIsError = false;

	public PerimeterConfigScreen(WorldConfigManager configs, AutomationController controller) {
		super(Component.literal("自动区域挖掘设置 (Perimeter Digger)"));
		this.configs = configs;
		this.controller = controller;
		loadAllFromLiveConfig();
	}

	private void loadAllFromLiveConfig() {
		this.draft = ConfigGuiModel.copy(configs.get());
		loadAreaTextFromDraft();
		this.sealingText = ConfigGuiModel.formatIdList(draft.sealingBlocks);
		this.scaffoldText = ConfigGuiModel.formatIdList(draft.scaffoldBlocks);
		this.foodsText = ConfigGuiModel.formatIdList(draft.foods);
		this.whitelistText = ConfigGuiModel.formatIdList(draft.unloadingWhitelist);
		this.miningWhitelistText = ConfigGuiModel.formatIdList(draft.miningWhitelist);
		this.miningBlacklistText = ConfigGuiModel.formatIdList(draft.miningBlacklist);
		loadPointTextFromDraft();
		loadUnloadTextFromDraft();
		loadAdvancedTextsFromDraft();
	}

	private void loadAreaTextFromDraft() {
		if (draft.detectedArea != null) {
			x1Text = Integer.toString(draft.detectedArea.minX);
			z1Text = Integer.toString(draft.detectedArea.minZ);
			x2Text = Integer.toString(draft.detectedArea.maxX);
			z2Text = Integer.toString(draft.detectedArea.maxZ);
		} else {
			x1Text = "";
			z1Text = "";
			x2Text = "";
			z2Text = "";
		}
		minYText = draft.diggingMinY == null ? "" : Integer.toString(draft.diggingMinY);
		maxYText = draft.diggingMaxY == null ? "" : Integer.toString(draft.diggingMaxY);
		areaInputsEdited = false;
	}

	private void loadPointTextFromDraft() {
		PositionConfig pos = ConfigGuiModel.getPoint(draft, POINT_SPECS.get(pointIndex).key());
		if (pos == null) {
			pointXText = "";
			pointYText = "";
			pointZText = "";
		} else {
			pointXText = Integer.toString(pos.x);
			pointYText = Integer.toString(pos.y);
			pointZText = Integer.toString(pos.z);
		}
	}

	private void loadUnloadTextFromDraft() {
		List<String> names = ConfigGuiModel.unloadingPointNames(draft);
		if (names.isEmpty()) {
			unloadIndex = 0;
			activeUnloadKey = null;
			unloadNameText = "default";
			unloadXText = "";
			unloadYText = "";
			unloadZText = "";
			return;
		}
		if (unloadIndex < 0 || unloadIndex >= names.size()) {
			unloadIndex = 0;
		}
		activeUnloadKey = names.get(unloadIndex);
		unloadNameText = activeUnloadKey;
		UnloadingPointConfig up = draft.unloadingPoints.get(activeUnloadKey);
		if (up == null) {
			unloadXText = "";
			unloadYText = "";
			unloadZText = "";
		} else {
			unloadXText = Integer.toString(up.x);
			unloadYText = Integer.toString(up.minY);
			unloadZText = Integer.toString(up.z);
		}
	}

	private void loadAdvancedTextsFromDraft() {
		advancedTexts.clear();
		advancedTexts.putAll(ConfigGuiModel.readAdvancedTexts(draft.advanced));
	}

	private int panelLeft() {
		return width / 2 - 190;
	}

	private int panelTop() {
		return Math.max(8, height / 2 - 114);
	}

	@Override
	protected void init() {
		clearWidgets();
		activeAdvancedBoxes.clear();
		int left = panelLeft();
		int top = panelTop();

		addTabButton(left, top + 14, 74, Tab.AREA, "选区控制");
		addTabButton(left + 76, top + 14, 74, Tab.FUNCTIONS, "功能开关");
		addTabButton(left + 152, top + 14, 74, Tab.ITEMS, "方块物品");
		addTabButton(left + 228, top + 14, 74, Tab.POINTS, "坐标点位");
		addTabButton(left + 304, top + 14, 76, Tab.ADVANCED, "数值参数");

		switch (activeTab) {
			case AREA -> buildAreaTab(left, top);
			case FUNCTIONS -> buildFunctionsTab(left, top);
			case ITEMS -> buildItemsTab(left, top);
			case POINTS -> buildPointsTab(left, top);
			case ADVANCED -> buildAdvancedTab(left, top);
		}

		addRenderableWidget(Button.builder(Component.literal("保存并关闭"), b -> save(true))
				.bounds(left, top + 190, 122, 20).build());
		addRenderableWidget(Button.builder(Component.literal("仅保存"), b -> save(false))
				.bounds(left + 129, top + 190, 122, 20).build());
		addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
				.bounds(left + 258, top + 190, 122, 20).build());
	}

	private void addTabButton(int x, int y, int w, Tab tab, String label) {
		Button button = addRenderableWidget(Button.builder(
				Component.literal(activeTab == tab ? "▶" + label : label),
				b -> switchTab(tab)
		).bounds(x, y, w, 18).build());
		button.active = activeTab != tab;
	}

	private void switchTab(Tab next) {
		captureActiveInputs();
		activeTab = next;
		init();
	}

	private void buildAreaTab(int left, int top) {
		addRenderableWidget(Button.builder(workModeLabel(), b -> {
			captureActiveInputs();
			draft.workMode = ConfigGuiModel.cycle(ConfigGuiModel.WORK_MODES, draft.workMode);
			init();
		}).bounds(left, top + 48, 94, 18).build());
		addRenderableWidget(Button.builder(startResumeLabel(), b -> actionStartOrResume())
				.bounds(left + 97, top + 48, 88, 18).build());
		addRenderableWidget(Button.builder(Component.literal("暂停"), b -> actionPause())
				.bounds(left + 188, top + 48, 62, 18).build());
		addRenderableWidget(Button.builder(Component.literal("停止"), b -> actionStop())
				.bounds(left + 253, top + 48, 62, 18).build());
		addRenderableWidget(Button.builder(Component.literal("重载"), b -> actionReload())
				.bounds(left + 318, top + 48, 62, 18).build());

		if ("build_schematic".equalsIgnoreCase(draft.workMode)) {
			x1Box = z1Box = x2Box = z2Box = minYBox = maxYBox = null;
			addRenderableWidget(Button.builder(wrongBlockPolicyLabel(), b -> {
				draft.schematicWrongBlockPolicy = ConfigGuiModel.cycle(
						ConfigGuiModel.SCHEMATIC_WRONG_BLOCK_POLICIES, draft.schematicWrongBlockPolicy);
				b.setMessage(wrongBlockPolicyLabel());
			}).bounds(left, top + 82, 186, 18).build());
			addRenderableWidget(Button.builder(missingMaterialPolicyLabel(), b -> {
				draft.schematicMissingMaterialPolicy = ConfigGuiModel.cycle(
						ConfigGuiModel.SCHEMATIC_MISSING_MATERIAL_POLICIES, draft.schematicMissingMaterialPolicy);
				b.setMessage(missingMaterialPolicyLabel());
			}).bounds(left + 194, top + 82, 186, 18).build());

			addToggle(left, top + 106, 186, 18, "水体优化放置", draft.schematicOptimizeWater,
					v -> draft.schematicOptimizeWater = v);
			addToggle(left + 194, top + 106, 186, 18, "清理投影空气处多余块", draft.schematicClearExtraBlocks,
					v -> draft.schematicClearExtraBlocks = v);

			addToggle(left, top + 130, 122, 18, "飞行 (/fly)", draft.functions.flightMovement,
					v -> draft.functions.flightMovement = v);
			addToggle(left + 126, top + 130, 124, 18, "自动进食", draft.functions.eat,
					v -> draft.functions.eat = v);
			addRenderableWidget(Button.builder(bubbleEscapeModeLabel(), b -> {
				draft.bubbleColumnEscapeMode = ConfigGuiModel.cycle(
						ConfigGuiModel.BUBBLE_COLUMN_ESCAPE_MODES, draft.bubbleColumnEscapeMode);
				b.setMessage(bubbleEscapeModeLabel());
			}).bounds(left + 254, top + 130, 126, 18).build());

			addRenderableWidget(Button.builder(Component.literal("检查投影/打印机"), b -> checkSchematicBuildReady())
					.bounds(left, top + 154, 122, 18).build());
			addRenderableWidget(Button.builder(
					Component.literal("资源点设置(" + draft.schematicResourcePoints.size() + ")"),
					b -> openSchematicResourcePointsScreen()
			).bounds(left + 126, top + 154, 124, 18).build());
			addRenderableWidget(Button.builder(Component.literal("计算每层物品"), b -> openSchematicMaterialsScreen())
					.bounds(left + 254, top + 154, 126, 18).build());
			return;
		}

		x1Box = createBox(left, top + 80, 116, 18, x1Text, 16, "X1");
		z1Box = createBox(left + 120, top + 80, 116, 18, z1Text, 16, "Z1");
		x1Box.setResponder(v -> areaInputsEdited = true);
		z1Box.setResponder(v -> areaInputsEdited = true);
		addRenderableWidget(Button.builder(Component.literal("角点1=脚下"), b -> fillCornerFromPlayer(true))
				.bounds(left + 240, top + 80, 72, 18).build());
		addRenderableWidget(Button.builder(Component.literal("清缓存"), b -> actionClearCache())
				.bounds(left + 316, top + 80, 64, 18).build());

		x2Box = createBox(left, top + 112, 116, 18, x2Text, 16, "X2");
		z2Box = createBox(left + 120, top + 112, 116, 18, z2Text, 16, "Z2");
		x2Box.setResponder(v -> areaInputsEdited = true);
		z2Box.setResponder(v -> areaInputsEdited = true);
		addRenderableWidget(Button.builder(Component.literal("角点2=脚下"), b -> fillCornerFromPlayer(false))
				.bounds(left + 240, top + 112, 72, 18).build());
		addRenderableWidget(Button.builder(Component.literal("主手探界"), b -> detectFromMainHand())
				.bounds(left + 316, top + 112, 64, 18).build());

		minYBox = createBox(left, top + 144, 72, 18, minYText, 8, "最小Y");
		maxYBox = createBox(left + 76, top + 144, 72, 18, maxYText, 8, "最大Y");
		addRenderableWidget(Button.builder(Component.literal("Y=脚下..+32"), b -> fillYFromPlayer())
				.bounds(left + 152, top + 144, 78, 18).build());
		addRenderableWidget(Button.builder(Component.literal("读取投影"), b -> readLitematicaFootprint())
				.bounds(left + 234, top + 144, 80, 18).build());
		addRenderableWidget(Button.builder(Component.literal("清除选区"), b -> clearArea())
				.bounds(left + 318, top + 144, 62, 18).build());

		addRenderableWidget(Button.builder(miningStrategyLabel(), b -> {
			draft.miningStrategy = ConfigGuiModel.cycle(ConfigGuiModel.MINING_STRATEGIES, draft.miningStrategy);
			b.setMessage(miningStrategyLabel());
		}).bounds(left, top + 167, 94, 18).build());

		addRenderableWidget(Button.builder(liquidPolicyLabel(), b -> {
			draft.liquidPolicy = ConfigGuiModel.cycle(ConfigGuiModel.LIQUID_POLICIES, draft.liquidPolicy);
			b.setMessage(liquidPolicyLabel());
		}).bounds(left + 96, top + 167, 94, 18).build());

		addRenderableWidget(Button.builder(repairModeLabel(), b -> {
			draft.durabilityRecoveryMode = ConfigGuiModel.cycle(ConfigGuiModel.REPAIR_MODES, draft.durabilityRecoveryMode);
			b.setMessage(repairModeLabel());
		}).bounds(left + 192, top + 167, 94, 18).build());

		addRenderableWidget(Button.builder(renderAreaShortLabel(), b -> {
			draft.functions.renderArea = !draft.functions.renderArea;
			b.setMessage(renderAreaShortLabel());
		}).bounds(left + 288, top + 167, 92, 18).build());
	}

	private void buildFunctionsTab(int left, int top) {
		addToggle(left, top + 44, 186, 16, "工具耐久保护", draft.functions.durabilityProtection, v -> draft.functions.durabilityProtection = v);
		addToggle(left + 194, top + 44, 186, 16, "耐久自动恢复", draft.functions.durabilityRecovery, v -> draft.functions.durabilityRecovery = v);

		addToggle(left, top + 62, 186, 16, "背包自动换工具", draft.functions.autoSwapBackpackTool, v -> draft.functions.autoSwapBackpackTool = v);
		addToggle(left + 194, top + 62, 186, 16, "低耐久换高耐久", draft.functions.autoReplaceLowDurabilityTool, v -> draft.functions.autoReplaceLowDurabilityTool = v);

		addToggle(left, top + 80, 186, 16, "自动进食", draft.functions.eat, v -> draft.functions.eat = v);
		addToggle(left + 194, top + 80, 186, 16, "飞行移动 (/fly)", draft.functions.flightMovement, v -> draft.functions.flightMovement = v);

		addToggle(left, top + 98, 92, 16, "显示选区", draft.functions.renderArea, v -> draft.functions.renderArea = v);
		addToggle(left + 94, top + 98, 92, 16, "停滞脱困", draft.advanced.miningStallDetectionEnabled, v -> draft.advanced.miningStallDetectionEnabled = v);
		addRenderableWidget(Button.builder(bubbleEscapeModeLabel(), b -> {
			draft.bubbleColumnEscapeMode = ConfigGuiModel.cycle(
					ConfigGuiModel.BUBBLE_COLUMN_ESCAPE_MODES, draft.bubbleColumnEscapeMode);
			b.setMessage(bubbleEscapeModeLabel());
		}).bounds(left + 194, top + 98, 186, 16).build());

		addToggle(left, top + 116, 186, 16, "不挖方块白名单", draft.functions.miningWhitelist, v -> draft.functions.miningWhitelist = v);
		addToggle(left + 194, top + 116, 186, 16, "只挖方块黑名单", draft.functions.miningBlacklist, v -> draft.functions.miningBlacklist = v);

		addToggle(left, top + 134, 186, 16, "收集掉落物", draft.functions.collectDrops, v -> draft.functions.collectDrops = v);
		addToggle(left + 194, top + 134, 186, 16, "自动卸货", draft.functions.unload, v -> draft.functions.unload = v);

		addToggle(left, top + 152, 186, 16, "自动补给物资", draft.functions.resupply, v -> draft.functions.resupply = v);
		addToggle(left + 194, top + 152, 186, 16, "跨维度传送门修补", draft.functions.crossDimensionRepair, v -> draft.functions.crossDimensionRepair = v);

		addToggle(left, top + 170, 186, 16, "鞘翅飞行导航", draft.functions.elytraNavigation, v -> draft.functions.elytraNavigation = v);
		addToggle(left + 194, top + 170, 186, 16, "夜晚自动睡觉", draft.functions.sleep, v -> draft.functions.sleep = v);
	}

	private void addToggle(int x, int y, int w, int h, String label, boolean initial, java.util.function.Consumer<Boolean> setter) {
		boolean[] state = { initial };
		addRenderableWidget(Button.builder(Component.literal(label + ": " + (state[0] ? "开启" : "关闭")), b -> {
			state[0] = !state[0];
			setter.accept(state[0]);
			b.setMessage(Component.literal(label + ": " + (state[0] ? "开启" : "关闭")));
		}).bounds(x, y, w, h).build());
	}

	private void buildItemsTab(int left, int top) {
		sealingBox = createBox(left, top + 44, 274, 15, sealingText, 512, "填水/封堵方块");
		addRenderableWidget(Button.builder(Component.literal("+主手方块"), b -> appendMainHand(sealingBox, true))
				.bounds(left + 278, top + 44, 102, 15).build());

		scaffoldBox = createBox(left, top + 69, 274, 15, scaffoldText, 512, "搭路方块");
		addRenderableWidget(Button.builder(Component.literal("+主手方块"), b -> appendMainHand(scaffoldBox, true))
				.bounds(left + 278, top + 69, 102, 15).build());

		foodsBox = createBox(left, top + 94, 212, 15, foodsText, 512, "允许食物");
		addRenderableWidget(Button.builder(Component.literal("+主手物品"), b -> appendMainHand(foodsBox, false))
				.bounds(left + 216, top + 94, 78, 15).build());
		addToggle(left + 298, top + 94, 82, 15, "自动换食", draft.functions.autoSwapBackpackFood, v -> draft.functions.autoSwapBackpackFood = v);

		whitelistBox = createBox(left, top + 119, 274, 15, whitelistText, 512, "卸货白名单");
		addRenderableWidget(Button.builder(Component.literal("+主手物品"), b -> appendMainHand(whitelistBox, false))
				.bounds(left + 278, top + 119, 102, 15).build());

		miningWhitelistBox = createBox(left, top + 144, 212, 15, miningWhitelistText, 512, "不挖方块白名单");
		addRenderableWidget(Button.builder(Component.literal("+主手方块"), b -> appendMainHand(miningWhitelistBox, true))
				.bounds(left + 216, top + 144, 78, 15).build());
		addToggle(left + 298, top + 144, 82, 15, "白名单", draft.functions.miningWhitelist, v -> draft.functions.miningWhitelist = v);

		miningBlacklistBox = createBox(left, top + 169, 212, 15, miningBlacklistText, 512, "只挖方块黑名单");
		addRenderableWidget(Button.builder(Component.literal("+主手方块"), b -> appendMainHand(miningBlacklistBox, true))
				.bounds(left + 216, top + 169, 78, 15).build());
		addToggle(left + 298, top + 169, 82, 15, "黑名单", draft.functions.miningBlacklist, v -> draft.functions.miningBlacklist = v);
	}

	private void buildPointsTab(int left, int top) {
		// Fixed named points (9 points)
		addRenderableWidget(Button.builder(Component.literal("◀"), b -> stepPoint(-1))
				.bounds(left, top + 46, 22, 18).build());
		addRenderableWidget(Button.builder(pointSelectorLabel(), b -> stepPoint(1))
				.bounds(left + 26, top + 46, 256, 18).build());
		addRenderableWidget(Button.builder(Component.literal("▶"), b -> stepPoint(1))
				.bounds(left + 286, top + 46, 22, 18).build());
		addRenderableWidget(Button.builder(Component.literal("清除该点"), b -> {
			ConfigGuiModel.setPoint(draft, POINT_SPECS.get(pointIndex).key(), null);
			loadPointTextFromDraft();
			setMessage("已清除 " + POINT_SPECS.get(pointIndex).label(), false);
			init();
		}).bounds(left + 312, top + 46, 68, 18).build());

		pointXBox = createBox(left, top + 68, 78, 18, pointXText, 16, "X");
		pointYBox = createBox(left + 82, top + 68, 78, 18, pointYText, 16, "Y");
		pointZBox = createBox(left + 164, top + 68, 78, 18, pointZText, 16, "Z");
		addRenderableWidget(Button.builder(Component.literal("设为脚下坐标"), b -> fillPointFromPlayer())
				.bounds(left + 246, top + 68, 134, 18).build());

		// Multi-unloading-point management
		addRenderableWidget(Button.builder(Component.literal("◀"), b -> stepUnload(-1))
				.bounds(left, top + 101, 22, 18).build());
		unloadNameBox = createBox(left + 26, top + 101, 136, 18, unloadNameText, 32, "卸货点名");
		addRenderableWidget(Button.builder(Component.literal("▶"), b -> stepUnload(1))
				.bounds(left + 166, top + 101, 22, 18).build());
		addRenderableWidget(Button.builder(Component.literal("应用/新增卸货点"), b -> applyUnloadInputsToDraft(true, true))
				.bounds(left + 192, top + 101, 100, 18).build());
		addRenderableWidget(Button.builder(Component.literal("删除卸货点"), b -> deleteCurrentUnloadPoint())
				.bounds(left + 296, top + 101, 84, 18).build());

		unloadXBox = createBox(left, top + 123, 78, 18, unloadXText, 16, "卸货X");
		unloadYBox = createBox(left + 82, top + 123, 78, 18, unloadYText, 16, "卸货Y");
		unloadZBox = createBox(left + 164, top + 123, 78, 18, unloadZText, 16, "卸货Z");
		addRenderableWidget(Button.builder(Component.literal("卸货点=脚下"), b -> fillUnloadFromPlayer())
				.bounds(left + 246, top + 123, 134, 18).build());

		// Single-flow debug actions
		addRenderableWidget(Button.builder(Component.literal("测卸货"), b -> runDebug("卸货", () -> controller.debugStage5(configs.get())))
				.bounds(left, top + 159, 72, 18).build());
		addRenderableWidget(Button.builder(Component.literal("测补耗材"), b -> runDebug("消耗品补给", () -> controller.debugStage6(configs.get(), false)))
				.bounds(left + 76, top + 159, 74, 18).build());
		addRenderableWidget(Button.builder(Component.literal("测补耐久"), b -> runDebug("耐久装备补给", () -> controller.debugStage6(configs.get(), true)))
				.bounds(left + 154, top + 159, 74, 18).build());
		addRenderableWidget(Button.builder(Component.literal("测修补"), b -> runDebug("经验修补", () -> controller.debugStage7(configs.get())))
				.bounds(left + 232, top + 159, 74, 18).build());
		addRenderableWidget(Button.builder(Component.literal("测睡觉"), b -> runDebug("夜晚睡觉", () -> controller.debugSleep(configs.get())))
				.bounds(left + 310, top + 159, 70, 18).build());
	}

	private void buildAdvancedTab(int left, int top) {
		List<ConfigGuiModel.AdvancedPage> pages = ConfigGuiModel.ADVANCED_PAGES;
		ConfigGuiModel.AdvancedPage page = pages.get(advancedPageIndex);

		addRenderableWidget(Button.builder(Component.literal("◀ 上页"), b -> stepAdvancedPage(-1))
				.bounds(left, top + 38, 58, 18).build());
		String pageTitle = "第 " + (advancedPageIndex + 1) + "/" + pages.size() + " 页: " + page.title();
		addRenderableWidget(Button.builder(Component.literal(pageTitle), b -> stepAdvancedPage(1))
				.bounds(left + 62, top + 38, 190, 18).build());
		addRenderableWidget(Button.builder(Component.literal("下页 ▶"), b -> stepAdvancedPage(1))
				.bounds(left + 256, top + 38, 58, 18).build());
		addRenderableWidget(Button.builder(Component.literal("恢复默认"), b -> {
			draft.advanced = new AdvancedConfig();
			loadAdvancedTextsFromDraft();
			setMessage("已重置全部 32 项高级参数为默认值（保存后生效）", false);
			init();
		}).bounds(left + 318, top + 38, 62, 18).build());

		List<ConfigGuiModel.AdvancedField> fields = page.fields();
		for (int i = 0; i < fields.size(); i++) {
			ConfigGuiModel.AdvancedField field = fields.get(i);
			int col = i % 2;
			int row = i / 2;
			int x = col == 0 ? left : left + 194;
			int y = top + 64 + row * 36;
			EditBox box = createBox(x, y + 10, 186, 18, advancedTexts.getOrDefault(field.key(), ""), 16, field.label());
			activeAdvancedBoxes.put(field.key(), box);
		}
		// Page 0 ("耐久与进食") has 5 numeric fields: place the Tool Durability Protection switch right in slot 6.
		if (advancedPageIndex == 0) {
			addToggle(left + 194, top + 64 + 2 * 36 + 10, 186, 18, "工具耐久保护",
					draft.functions.durabilityProtection, v -> draft.functions.durabilityProtection = v);
		}
	}

	private void stepPoint(int delta) {
		captureActiveInputs();
		applyPointInputsToDraft(false);
		pointIndex = Math.floorMod(pointIndex + delta, POINT_SPECS.size());
		loadPointTextFromDraft();
		init();
	}

	private void stepUnload(int delta) {
		captureActiveInputs();
		applyUnloadInputsToDraft(false, false);
		List<String> names = ConfigGuiModel.unloadingPointNames(draft);
		if (names.isEmpty()) {
			setMessage("当前无已保存卸货点，填写名称与坐标后点「应用/新增卸货点」", false);
			return;
		}
		unloadIndex = Math.floorMod(unloadIndex + delta, names.size());
		loadUnloadTextFromDraft();
		init();
	}

	private void stepAdvancedPage(int delta) {
		captureActiveInputs();
		advancedPageIndex = Math.floorMod(advancedPageIndex + delta, ConfigGuiModel.ADVANCED_PAGES.size());
		init();
	}

	private EditBox createBox(int x, int y, int w, int h, String value, int maxLength, String label) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, h, Component.literal(label)));
		box.setMaxLength(maxLength);
		box.setValue(value == null ? "" : value);
		return box;
	}

	private void captureActiveInputs() {
		switch (activeTab) {
			case AREA -> {
				if (x1Box != null) x1Text = x1Box.getValue().trim();
				if (z1Box != null) z1Text = z1Box.getValue().trim();
				if (x2Box != null) x2Text = x2Box.getValue().trim();
				if (z2Box != null) z2Text = z2Box.getValue().trim();
				if (minYBox != null) minYText = minYBox.getValue().trim();
				if (maxYBox != null) maxYText = maxYBox.getValue().trim();
			}
			case ITEMS -> {
				if (sealingBox != null) sealingText = sealingBox.getValue().trim();
				if (scaffoldBox != null) scaffoldText = scaffoldBox.getValue().trim();
				if (foodsBox != null) foodsText = foodsBox.getValue().trim();
				if (whitelistBox != null) whitelistText = whitelistBox.getValue().trim();
				if (miningWhitelistBox != null) miningWhitelistText = miningWhitelistBox.getValue().trim();
				if (miningBlacklistBox != null) miningBlacklistText = miningBlacklistBox.getValue().trim();
			}
			case POINTS -> {
				if (pointXBox != null) pointXText = pointXBox.getValue().trim();
				if (pointYBox != null) pointYText = pointYBox.getValue().trim();
				if (pointZBox != null) pointZText = pointZBox.getValue().trim();
				if (unloadNameBox != null) unloadNameText = unloadNameBox.getValue().trim();
				if (unloadXBox != null) unloadXText = unloadXBox.getValue().trim();
				if (unloadYBox != null) unloadYText = unloadYBox.getValue().trim();
				if (unloadZBox != null) unloadZText = unloadZBox.getValue().trim();
				applyPointInputsToDraft(false);
				applyUnloadInputsToDraft(false, false);
			}
			case ADVANCED -> {
				for (Map.Entry<String, EditBox> entry : activeAdvancedBoxes.entrySet()) {
					advancedTexts.put(entry.getKey(), entry.getValue().getValue().trim());
				}
			}
			case FUNCTIONS -> {}
		}
	}

	private void fillCornerFromPlayer(boolean first) {
		if (minecraft == null || minecraft.player == null) return;
		String px = Integer.toString(minecraft.player.getBlockX());
		String pz = Integer.toString(minecraft.player.getBlockZ());
		if (first) {
			x1Text = px;
			z1Text = pz;
			if (x1Box != null) x1Box.setValue(px);
			if (z1Box != null) z1Box.setValue(pz);
		} else {
			x2Text = px;
			z2Text = pz;
			if (x2Box != null) x2Box.setValue(px);
			if (z2Box != null) z2Box.setValue(pz);
		}
		areaInputsEdited = true;
		setMessage("已填入当前位置 (" + px + ", " + pz + ")", false);
	}

	private void fillYFromPlayer() {
		if (minecraft == null || minecraft.player == null) return;
		int y = minecraft.player.getBlockY();
		minYText = Integer.toString(y);
		maxYText = Integer.toString(y + 32);
		if (minYBox != null) minYBox.setValue(minYText);
		if (maxYBox != null) maxYBox.setValue(maxYText);
		setMessage("已填入 Y=" + minYText + ".." + maxYText, false);
	}

	private void detectFromMainHand() {
		if (minecraft == null || minecraft.player == null) return;
		ItemStack stack = minecraft.player.getMainHandItem();
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			setMessage("请手持用于围成边界的方块后再点击「主手探界」", true);
			return;
		}
		try {
			Block block = blockItem.getBlock();
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			int y = minecraft.player.getBlockY();
			DetectedAreaConfig detected = detector.detect(block, id, y);
			draft.detectedArea = detected;
			loadAreaTextFromDraft();
			setMessage("探测成功：" + detected.columnCount + " 列 (" + id + " @ Y=" + y + ")，请保存", false);
			init();
		} catch (RuntimeException e) {
			setMessage("探测失败：" + e.getMessage(), true);
		}
	}

	private void readLitematicaFootprint() {
		LitematicaFootprintReader.ReadResult result = LitematicaFootprintReader.readCurrentFootprint(minecraft);
		if (!result.ok()) {
			setMessage(result.userMessage(), true);
			return;
		}
		LitematicaFootprintReader.Footprint fp = result.footprint();
		DetectedAreaConfig area = ConfigGuiModel.buildRectangleArea(
				fp.minX(), fp.minZ(), fp.maxX(), fp.maxZ(), fp.minY(), fp.maxY());
		if (minecraft != null && minecraft.level != null) {
			area.dimension = minecraft.level.dimension().identifier().toString();
		}
		draft.detectedArea = area;
		draft.diggingMinY = fp.minY();
		draft.diggingMaxY = fp.maxY();
		loadAreaTextFromDraft();
		setMessage(result.userMessage() + "（点击保存生效）", false);
		init();
	}

	private void clearArea() {
		draft.detectedArea = null;
		draft.diggingMinY = null;
		draft.diggingMaxY = null;
		loadAreaTextFromDraft();
		setMessage("已清空选区（保存后生效）", false);
		init();
	}

	private void fillPointFromPlayer() {
		if (minecraft == null || minecraft.player == null) return;
		int x = minecraft.player.getBlockX();
		int y = minecraft.player.getBlockY();
		int z = minecraft.player.getBlockZ();
		pointXText = Integer.toString(x);
		pointYText = Integer.toString(y);
		pointZText = Integer.toString(z);
		if (pointXBox != null) pointXBox.setValue(pointXText);
		if (pointYBox != null) pointYBox.setValue(pointYText);
		if (pointZBox != null) pointZBox.setValue(pointZText);
		ConfigGuiModel.setPoint(draft, POINT_SPECS.get(pointIndex).key(), new PositionConfig(x, y, z));
		setMessage("已将 " + POINT_SPECS.get(pointIndex).label() + " 设为 (" + x + ", " + y + ", " + z + ")", false);
		init();
	}

	private void fillUnloadFromPlayer() {
		if (minecraft == null || minecraft.player == null) return;
		captureActiveInputs();
		unloadXText = Integer.toString(minecraft.player.getBlockX());
		unloadYText = Integer.toString(minecraft.player.getBlockY());
		unloadZText = Integer.toString(minecraft.player.getBlockZ());
		if (unloadXBox != null) unloadXBox.setValue(unloadXText);
		if (unloadYBox != null) unloadYBox.setValue(unloadYText);
		if (unloadZBox != null) unloadZBox.setValue(unloadZText);
		applyUnloadInputsToDraft(true, true);
	}

	private void deleteCurrentUnloadPoint() {
		captureActiveInputs();
		String targetKey = activeUnloadKey != null ? activeUnloadKey : unloadNameText;
		if (targetKey == null || targetKey.isBlank() || !draft.unloadingPoints.containsKey(targetKey)) {
			setMessage("当前没有可删除的卸货点", true);
			return;
		}
		ConfigGuiModel.removeNamedUnloadingPoint(draft, targetKey);
		loadUnloadTextFromDraft();
		setMessage("已删除卸货点 " + targetKey + "（保存后生效）", false);
		init();
	}

	private void appendMainHand(EditBox box, boolean blockOnly) {
		if (minecraft == null || minecraft.player == null || box == null) return;
		ItemStack stack = minecraft.player.getMainHandItem();
		if (stack.isEmpty()) {
			setMessage("主手为空", true);
			return;
		}
		if (blockOnly && !(stack.getItem() instanceof BlockItem)) {
			setMessage("主手当前不是方块物品", true);
			return;
		}
		String id = blockOnly
				? BuiltInRegistries.BLOCK.getKey(((BlockItem) stack.getItem()).getBlock()).toString()
				: BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		List<String> list = ConfigGuiModel.parseIdList(box.getValue());
		if (!list.contains(id)) list.add(id);
		box.setValue(ConfigGuiModel.formatIdList(list));
		setMessage("已添加主手物品: " + id, false);
	}

	private void actionStartOrResume() {
		if (!applyAndSaveDraft()) return;
		try {
			AutomationState state = controller.state();
			if (state == AutomationState.PAUSED) {
				controller.resume(configs.get());
				setMessage("build_schematic".equalsIgnoreCase(configs.get().workMode) ? "已恢复投影放置" : "已恢复区域挖掘", false);
			} else if (!RetaliationPolicy.active(state)) {
				List<String> missing = controller.start(configs.get());
				if (missing.isEmpty()) {
					setMessage("build_schematic".equalsIgnoreCase(configs.get().workMode) ? "已开始逐层放置投影" : "已开始区域挖掘", false);
				} else if (missing.contains("schematic_build")) {
					setMessage(controller.detail().getString(), true);
				} else {
					setMessage("无法启动，缺少配置：" + String.join(", ", missing), true);
				}
			} else {
				setMessage("任务已在运行中", false);
			}
			init();
		} catch (RuntimeException e) {
			setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
		}
	}

	private void actionPause() {
		try {
			controller.pause();
			setMessage("已暂停挖掘", false);
			init();
		} catch (RuntimeException e) {
			setMessage("当前没有可暂停的运行任务", true);
		}
	}

	private void actionStop() {
		controller.stop();
		setMessage("已停止挖掘任务", false);
		init();
	}

	private void actionReload() {
		try {
			configs.reload();
			PerimeterConfig live = configs.get();
			controller.syncConfig(live);
			loadAllFromLiveConfig();
			setMessage("已从磁盘重新加载当前世界配置", false);
			init();
		} catch (RuntimeException e) {
			setMessage("重载失败：" + e.getMessage(), true);
		}
	}

	private void actionClearCache() {
		controller.clearCachedState();
		setMessage("已停止并清理自动化缓存状态", false);
		init();
	}

	private void runDebug(String label, java.util.function.Supplier<List<String>> runner) {
		if (!applyAndSaveDraft()) return;
		try {
			List<String> missing = runner.get();
			if (missing.isEmpty()) setMessage("已启动「" + label + "」单项测试", false);
			else setMessage("无法测试「" + label + "」，缺少：" + String.join(", ", missing), true);
		} catch (RuntimeException e) {
			setMessage("测试失败：" + e.getMessage(), true);
		}
	}

	private void save(boolean closeAfter) {
		if (!applyAndSaveDraft()) return;
		if (closeAfter) {
			onClose();
		} else {
			setMessage("全部配置已保存并立即生效", false);
		}
	}

	private boolean applyAndSaveDraft() {
		captureActiveInputs();
		try {
			applyAreaInputsToDraft();
			applyItemInputsToDraft();
			applyPointInputsToDraft(true);
			applyUnloadInputsToDraft(true, false);
			ConfigGuiModel.applyAdvancedTexts(draft.advanced, advancedTexts);

			PerimeterConfig live = configs.get();
			ConfigGuiModel.copyInto(draft, live);
			configs.save();
			controller.syncConfig(live);
			return true;
		} catch (IllegalArgumentException e) {
			setMessage(e.getMessage(), true);
			return false;
		} catch (RuntimeException e) {
			setMessage("保存失败：" + e.getMessage(), true);
			return false;
		}
	}

	private void applyAreaInputsToDraft() {
		boolean anyCoord = !x1Text.isEmpty() || !z1Text.isEmpty() || !x2Text.isEmpty() || !z2Text.isEmpty();
		boolean anyY = !minYText.isEmpty() || !maxYText.isEmpty();
		if (anyY) {
			int minY = parseInt(minYText, "最小 Y");
			int maxY = parseInt(maxYText, "最大 Y");
			if (minY > maxY) throw new IllegalArgumentException("最小 Y 不能大于最大 Y");
			draft.diggingMinY = minY;
			draft.diggingMaxY = maxY;
		} else {
			draft.diggingMinY = null;
			draft.diggingMaxY = null;
		}
		if (!anyCoord) {
			draft.detectedArea = null;
			return;
		}
		int x1 = parseInt(x1Text, "角点 1 X");
		int z1 = parseInt(z1Text, "角点 1 Z");
		int x2 = parseInt(x2Text, "角点 2 X");
		int z2 = parseInt(z2Text, "角点 2 Z");
		boolean keepExistingPolygon = !areaInputsEdited && draft.detectedArea != null
				&& !"rectangle".equals(draft.detectedArea.boundaryBlock)
				&& draft.detectedArea.minX == Math.min(x1, x2)
				&& draft.detectedArea.maxX == Math.max(x1, x2)
				&& draft.detectedArea.minZ == Math.min(z1, z2)
				&& draft.detectedArea.maxZ == Math.max(z1, z2);
		if (!keepExistingPolygon) {
			int minY = draft.diggingMinY == null ? 0 : draft.diggingMinY;
			int maxY = draft.diggingMaxY == null ? minY : draft.diggingMaxY;
			DetectedAreaConfig rect = ConfigGuiModel.buildRectangleArea(x1, z1, x2, z2, minY, maxY);
			if (minecraft != null && minecraft.level != null) {
				rect.dimension = minecraft.level.dimension().identifier().toString();
			}
			draft.detectedArea = rect;
		}
	}

	private void applyItemInputsToDraft() {
		List<String> sealing = ConfigGuiModel.parseIdList(sealingText);
		for (String idText : sealing) {
			Identifier id = Identifier.tryParse(idText);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || block.defaultBlockState().isAir()) {
				throw new IllegalArgumentException("无效填水/封堵方块 ID: " + idText);
			}
		}
		List<String> scaffold = ConfigGuiModel.parseIdList(scaffoldText);
		for (String idText : scaffold) {
			Identifier id = Identifier.tryParse(idText);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || block.defaultBlockState().isAir()) {
				throw new IllegalArgumentException("无效搭路方块 ID: " + idText);
			}
		}
		List<String> foods = ConfigGuiModel.parseIdList(foodsText);
		for (String idText : foods) {
			Identifier id = Identifier.tryParse(idText);
			var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || !item.components().has(net.minecraft.core.component.DataComponents.CONSUMABLE)) {
				throw new IllegalArgumentException("无效食物物品 ID: " + idText);
			}
		}
		List<String> whitelist = ConfigGuiModel.parseIdList(whitelistText);
		for (String idText : whitelist) {
			Identifier id = Identifier.tryParse(idText);
			if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
				throw new IllegalArgumentException("无效白名单物品 ID: " + idText);
			}
		}
		List<String> miningWhitelist = ConfigGuiModel.parseIdList(miningWhitelistText);
		for (String idText : miningWhitelist) {
			Identifier id = Identifier.tryParse(idText);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || block.defaultBlockState().isAir()) {
				throw new IllegalArgumentException("无效不挖方块白名单 ID: " + idText);
			}
		}
		List<String> miningBlacklist = ConfigGuiModel.parseIdList(miningBlacklistText);
		for (String idText : miningBlacklist) {
			Identifier id = Identifier.tryParse(idText);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || block.defaultBlockState().isAir()) {
				throw new IllegalArgumentException("无效只挖方块黑名单 ID: " + idText);
			}
		}
		draft.sealingBlocks = sealing;
		draft.scaffoldBlocks = scaffold;
		draft.foods = foods;
		draft.unloadingWhitelist = whitelist;
		draft.miningWhitelist = miningWhitelist;
		draft.miningBlacklist = miningBlacklist;
	}

	private void applyPointInputsToDraft(boolean strict) {
		boolean allBlank = pointXText.isEmpty() && pointYText.isEmpty() && pointZText.isEmpty();
		String key = POINT_SPECS.get(pointIndex).key();
		if (allBlank) {
			ConfigGuiModel.setPoint(draft, key, null);
			return;
		}
		if (pointXText.isEmpty() || pointYText.isEmpty() || pointZText.isEmpty()) {
			if (strict) throw new IllegalArgumentException(POINT_SPECS.get(pointIndex).label() + " 的 X/Y/Z 必须同时填写或同时留空");
			return;
		}
		try {
			int x = parseInt(pointXText, "坐标 X");
			int y = parseInt(pointYText, "坐标 Y");
			int z = parseInt(pointZText, "坐标 Z");
			ConfigGuiModel.setPoint(draft, key, new PositionConfig(x, y, z));
		} catch (IllegalArgumentException e) {
			if (strict) throw e;
		}
	}

	private void applyUnloadInputsToDraft(boolean strict, boolean showFeedback) {
		if (activeTab == Tab.POINTS) {
			if (unloadNameBox != null) unloadNameText = unloadNameBox.getValue().trim();
			if (unloadXBox != null) unloadXText = unloadXBox.getValue().trim();
			if (unloadYBox != null) unloadYText = unloadYBox.getValue().trim();
			if (unloadZBox != null) unloadZText = unloadZBox.getValue().trim();
		}
		boolean allCoordBlank = unloadXText.isEmpty() && unloadYText.isEmpty() && unloadZText.isEmpty();
		if (allCoordBlank) {
			if (showFeedback) {
				setMessage("请先填写卸货点坐标 X / Y / Z（或点击「卸货点=脚下」）", true);
			}
			return;
		}
		if (unloadXText.isEmpty() || unloadYText.isEmpty() || unloadZText.isEmpty()) {
			if (strict) {
				if (showFeedback) setMessage("卸货点坐标 X / Y / Z 必须同时填写", true);
				else throw new IllegalArgumentException("卸货点坐标 X / Y / Z 必须同时填写或同时留空");
			}
			return;
		}
		try {
			int x = parseInt(unloadXText, "卸货点 X");
			int y = parseInt(unloadYText, "卸货点 Y");
			int z = parseInt(unloadZText, "卸货点 Z");
			// If user typed a brand-new name different from activeUnloadKey and wants to add a new point,
			// keep existing points unless renaming the same name.
			String previous = draft.unloadingPoints.containsKey(unloadNameText) ? unloadNameText : null;
			ConfigGuiModel.setNamedUnloadingPoint(draft, previous, unloadNameText, new PositionConfig(x, y, z));
			List<String> names = ConfigGuiModel.unloadingPointNames(draft);
			unloadIndex = Math.max(0, names.indexOf(unloadNameText.trim()));
			activeUnloadKey = unloadNameText.trim();
			if (showFeedback) {
				setMessage("已写入卸货点 " + activeUnloadKey + " (" + x + ", " + y + ", " + z + ")，共 " + names.size() + " 个", false);
				init();
			}
		} catch (IllegalArgumentException e) {
			if (showFeedback) setMessage(e.getMessage(), true);
			else if (strict) throw e;
		}
	}

	private static int parseInt(String text, String label) {
		if (text == null || text.isBlank()) throw new IllegalArgumentException("请填写 " + label);
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + " 必须是整数: " + text);
		}
	}

	private void setMessage(String text, boolean error) {
		this.message = text == null ? "" : text;
		this.messageIsError = error;
	}

	private Component startResumeLabel() {
		AutomationState state = controller.state();
		if (state == AutomationState.PAUSED) return Component.literal("恢复任务");
		if (RetaliationPolicy.active(state)) return Component.literal("运行中");
		return Component.literal("保存并开始");
	}

	private Component workModeLabel() {
		boolean build = "build_schematic".equalsIgnoreCase(draft.workMode);
		return Component.literal("模式: " + (build ? "投影打印" : "自动挖掘"));
	}

	private Component wrongBlockPolicyLabel() {
		boolean breakMode = "break_and_place".equalsIgnoreCase(draft.schematicWrongBlockPolicy);
		return Component.literal("错误方块: " + (breakMode ? "破坏重放" : "跳过"));
	}

	private Component missingMaterialPolicyLabel() {
		boolean pauseNow = "pause_immediately".equalsIgnoreCase(draft.schematicMissingMaterialPolicy);
		return Component.literal("缺料处理: " + (pauseNow ? "立刻暂停" : "先放已有"));
	}

	private Component bubbleEscapeModeLabel() {
		String label = switch (draft.bubbleColumnEscapeMode == null ? "auto" : draft.bubbleColumnEscapeMode) {
			case "scaffold_first" -> "垫块封泡";
			case "bucket_first" -> "空桶断流";
			case "walk_or_fly" -> "潜行/飞行";
			default -> "自动综合";
		};
		return Component.literal("气泡脱困: " + label);
	}

	private void checkSchematicBuildReady() {
		var bridge = new hackerrouter.perimeterdigger.client.build.LitematicaPrinterBridge();
		var avail = bridge.checkAvailability();
		if (!avail.ok()) {
			setMessage(avail.errorMessage(), true);
			return;
		}
		var fp = LitematicaFootprintReader.readCurrentFootprint(minecraft);
		setMessage(fp.userMessage(), !fp.ok());
	}

	private void openSchematicMaterialsScreen() {
		if (minecraft == null || minecraft.gui == null) return;
		minecraft.gui.setScreen(new SchematicLayerMaterialsScreen(this, configs, controller, draft));
	}

	private void openSchematicResourcePointsScreen() {
		if (minecraft == null || minecraft.gui == null) return;
		minecraft.gui.setScreen(new SchematicResourcePointsScreen(this, configs, controller, draft));
	}

	private Component miningStrategyLabel() {
		String label = switch (draft.miningStrategy == null ? "global_top_down" : draft.miningStrategy) {
			case "local_top_down" -> "局部顶部";
			case "chunk_top_down" -> "单区块";
			default -> "整体顶部";
		};
		return Component.literal("策略: " + label);
	}

	private Component liquidPolicyLabel() {
		String label = switch (draft.liquidPolicy) {
			case "replace" -> "全填水";
			case "avoid" -> "避开水";
			default -> "封边水";
		};
		return Component.literal("流体: " + label);
	}

	private Component repairModeLabel() {
		String label = "supply_point".equals(draft.durabilityRecoveryMode) ? "补给点" : "传送门";
		return Component.literal("修补: " + label);
	}

	private Component renderAreaShortLabel() {
		return Component.literal("选区: " + (draft.functions.renderArea ? "开启" : "关闭"));
	}

	private Component pointSelectorLabel() {
		PointSpec spec = POINT_SPECS.get(pointIndex);
		PositionConfig pos = ConfigGuiModel.getPoint(draft, spec.key());
		String suffix = pos == null ? "[未设置]" : "(" + pos.x + "," + pos.y + "," + pos.z + ")";
		return Component.literal(spec.label() + " " + suffix);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		int left = panelLeft();
		int top = panelTop();

		g.centeredText(font, title, width / 2, top, 0xFFFFFFFF);

		switch (activeTab) {
			case AREA -> {
				String modeTag = controller.isUsingCreativeFlight() ? "[/fly飞行]" : "[地面寻路]";
				String statusText = "状态: " + controller.state().name() + " " + modeTag + " | " + truncate(controller.detail().getString(), 34);
				g.text(font, statusText, left, top + 36, 0xFF99EEFF);
				if ("build_schematic".equalsIgnoreCase(draft.workMode)) {
					g.text(font, "逐层投影放置设置（联动 Litematica 单层显示 + 投影打印机）", left, top + 71, 0xFFE0E0E0);
					g.text(font, "提示：短按 F9 开始/暂停/继续；开启水体优化时生存带 2 桶水即可自动续水。", left, top + 176, 0xFFAAAAAA);
				} else {
					g.text(font, "角点 1 (X1 / Z1)", left, top + 70, 0xFFE0E0E0);
					g.text(font, "角点 2 (X2 / Z2)", left, top + 102, 0xFFE0E0E0);
					g.text(font, "挖掘高度 (最小Y / 最大Y)    " + areaSummary(), left, top + 134, 0xFFE0E0E0);
				}
			}
			case FUNCTIONS -> {
				g.text(font, "点击切换自动化与保护开关（保存后实时生效）", left, top + 34, 0xFFCCCCCC);
			}
			case ITEMS -> {
				g.text(font, "填水/封堵方块 ID（留空搭路方块时也参与搭路，如 minecraft:netherrack）", left, top + 35, 0xFFE0E0E0);
				g.text(font, "搭路方块 ID（寻路垫高/搭桥；留空=默认圆石/泥土/石头/封堵块）", left, top + 60, 0xFFE0E0E0);
				g.text(font, "允许食物 ID（逗号分隔，如 minecraft:cooked_beef）", left, top + 85, 0xFFE0E0E0);
				g.text(font, "卸货保留白名单（逗号分隔；工具/装备/食物默认自动保留）", left, top + 110, 0xFFE0E0E0);
				g.text(font, "不挖方块白名单（跳过不挖，如 minecraft:diamond_ore）", left, top + 135, 0xFFE0E0E0);
				g.text(font, "只挖方块黑名单（仅挖指定，如 minecraft:mangrove_log）", left, top + 160, 0xFFE0E0E0);
			}
			case POINTS -> {
				g.text(font, "固定点位（补给 / 床 / 传送门 / 熔炉排；留空坐标即未设置）", left, top + 36, 0xFFE0E0E0);
				g.text(font, "多卸货点管理（已配置 " + draft.unloadingPoints.size() + " 个；改名点应用可新增）", left, top + 91, 0xFFE0E0E0);
				g.text(font, "单项流程调试（保存后立即触发对应子流程）", left, top + 148, 0xFFE0E0E0);
			}
			case ADVANCED -> {
				ConfigGuiModel.AdvancedPage page = ConfigGuiModel.ADVANCED_PAGES.get(advancedPageIndex);
				List<ConfigGuiModel.AdvancedField> fields = page.fields();
				for (int i = 0; i < fields.size(); i++) {
					int col = i % 2;
					int row = i / 2;
					int x = col == 0 ? left : left + 194;
					int y = top + 64 + row * 36;
					g.text(font, fields.get(i).label(), x, y, 0xFFE0E0E0);
				}
				if (advancedPageIndex == 0) {
					g.text(font, "快捷开关（同步功能开关页）", left + 194, top + 64 + 2 * 36, 0xFFE0E0E0);
					g.text(font, "提示：饥饿值 ≤ 饥饿阈值 或 血量 ≤ 血量阈值(且未饱) 时自动进食。", left, top + 174, 0xFFAAAAAA);
				} else {
					g.text(font, "提示：所有 6 页数值修改跨页保留，点击下方「保存」后统一生效。", left, top + 174, 0xFFAAAAAA);
				}
			}
		}

		if (!message.isEmpty()) {
			g.centeredText(font, message, width / 2, top + 214, messageIsError ? 0xFFFF8888 : 0xFF88FF88);
		} else {
			g.centeredText(font, "短按 F9 开始/暂停挖掘 | 长按 F9 打开设置（可在控制设置中修改按键）", width / 2, top + 214, 0xFFCCCCCC);
		}
	}

	private String areaSummary() {
		DetectedAreaConfig area = draft.detectedArea;
		if (area == null) return "当前: 未设定选区";
		String kind = "rectangle".equals(area.boundaryBlock) ? "矩形" : "多边形";
		if (draft.diggingMinY != null && draft.diggingMaxY != null && draft.diggingMaxY >= draft.diggingMinY) {
			long total = area.columnCount * ((long) draft.diggingMaxY - draft.diggingMinY + 1L);
			return kind + " " + area.columnCount + " 列 (" + total + " 格)";
		}
		return kind + " " + area.columnCount + " 列";
	}

	private static String truncate(String text, int maxChars) {
		if (text == null || text.length() <= maxChars) return text == null ? "" : text;
		return text.substring(0, Math.max(0, maxChars - 1)) + "…";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
