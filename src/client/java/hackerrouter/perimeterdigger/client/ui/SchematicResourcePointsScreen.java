package hackerrouter.perimeterdigger.client.ui;

import hackerrouter.perimeterdigger.client.build.SchematicResourceCache;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.PositionConfig;
import hackerrouter.perimeterdigger.client.config.WorldConfigManager;
import hackerrouter.perimeterdigger.client.state.AutomationController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Management screen for multiple named schematic resource points (chests, barrels, shulker boxes).
 * Supports one-click crosshair chest capture, 8-block nearby chest auto-discovery (deduplicating
 * double chests), manual XYZ editing, and triggering automatic inventory scanning.
 */
public final class SchematicResourcePointsScreen extends Screen {
	private static final int NEARBY_SCAN_RADIUS = 8;

	private final Screen parent;
	private final WorldConfigManager configs;
	private final AutomationController controller;
	private final PerimeterConfig draft;

	private int selectedIndex = 0;
	private String activeKey = null;
	private String nameText = "res_1";
	private String xText = "";
	private String yText = "";
	private String zText = "";

	private EditBox nameBox, xBox, yBox, zBox;
	private String message = "";
	private boolean messageIsError = false;

	public SchematicResourcePointsScreen(
			Screen parent,
			WorldConfigManager configs,
			AutomationController controller,
			PerimeterConfig draft
	) {
		super(Component.literal("投影资源点设置（箱子 / 木桶 / 潜影盒）"));
		this.parent = parent;
		this.configs = configs;
		this.controller = controller;
		this.draft = draft;
		loadSelected();
	}

	private int panelLeft() {
		return width / 2 - 190;
	}

	private int panelTop() {
		return Math.max(8, height / 2 - 114);
	}

	private void loadSelected() {
		List<String> names = ConfigGuiModel.schematicResourcePointNames(draft);
		if (names.isEmpty()) {
			selectedIndex = 0;
			activeKey = null;
			nameText = nextAutoName();
			xText = "";
			yText = "";
			zText = "";
			return;
		}
		if (selectedIndex < 0 || selectedIndex >= names.size()) {
			selectedIndex = 0;
		}
		activeKey = names.get(selectedIndex);
		nameText = activeKey;
		PositionConfig p = draft.schematicResourcePoints.get(activeKey);
		if (p == null) {
			xText = yText = zText = "";
		} else {
			xText = Integer.toString(p.x);
			yText = Integer.toString(p.y);
			zText = Integer.toString(p.z);
		}
	}

	@Override
	protected void init() {
		clearWidgets();
		int left = panelLeft();
		int top = panelTop();

		// Row 1: Selector + Name + Save/Delete
		addRenderableWidget(Button.builder(Component.literal("◀"), b -> stepPoint(-1))
				.bounds(left, top + 20, 22, 18).build());
		nameBox = createBox(left + 26, top + 20, 146, 18, nameText, 32, "资源点名");
		addRenderableWidget(Button.builder(Component.literal("▶"), b -> stepPoint(1))
				.bounds(left + 176, top + 20, 22, 18).build());
		addRenderableWidget(Button.builder(Component.literal("保存/新增"), b -> applyManualInput())
				.bounds(left + 202, top + 20, 90, 18).build());
		addRenderableWidget(Button.builder(Component.literal("删除此点"), b -> deleteCurrent())
				.bounds(left + 296, top + 20, 84, 18).build());

		// Row 2: Coordinates + Crosshair capture
		xBox = createBox(left, top + 44, 78, 18, xText, 16, "X");
		yBox = createBox(left + 82, top + 44, 78, 18, yText, 16, "Y");
		zBox = createBox(left + 164, top + 44, 78, 18, zText, 16, "Z");
		addRenderableWidget(Button.builder(Component.literal("+准星箱子/潜影盒"), b -> addCrosshairContainer())
				.bounds(left + 246, top + 44, 134, 18).build());

		// Row 3: Batch discovery + auto-scan + clear
		addRenderableWidget(Button.builder(Component.literal("+附近8格箱/潜影盒"), b -> addNearbyContainers())
				.bounds(left, top + 68, 140, 18).build());
		addRenderableWidget(Button.builder(Component.literal("自动扫描全部资源点"), b -> startAutoScan())
				.bounds(left + 144, top + 68, 152, 18).build());
		addRenderableWidget(Button.builder(Component.literal("清空全部"), b -> clearAllPoints())
				.bounds(left + 300, top + 68, 80, 18).build());

		// Bottom row
		addRenderableWidget(Button.builder(Component.literal("查看每层物品与资源判定"), b -> openMaterialsCalculator())
				.bounds(left, top + 190, 186, 20).build());
		addRenderableWidget(Button.builder(Component.literal("保存并返回"), b -> saveAndReturn())
				.bounds(left + 194, top + 190, 186, 20).build());
	}

	private EditBox createBox(int x, int y, int w, int h, String val, int maxLen, String label) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, h, Component.literal(label)));
		box.setMaxLength(maxLen);
		box.setValue(val == null ? "" : val);
		return box;
	}

	private void captureBoxes() {
		if (nameBox != null) nameText = nameBox.getValue().trim();
		if (xBox != null) xText = xBox.getValue().trim();
		if (yBox != null) yText = yBox.getValue().trim();
		if (zBox != null) zText = zBox.getValue().trim();
	}

	private void stepPoint(int delta) {
		List<String> names = ConfigGuiModel.schematicResourcePointNames(draft);
		if (names.isEmpty()) {
			setMessage("当前无已配置资源点，请点击「+添加附近8格箱子」或手动填写坐标", false);
			return;
		}
		selectedIndex = Math.floorMod(selectedIndex + delta, names.size());
		loadSelected();
		init();
	}

	private void applyManualInput() {
		captureBoxes();
		try {
			int x = parseInt(xText, "X");
			int y = parseInt(yText, "Y");
			int z = parseInt(zText, "Z");
			String prev = draft.schematicResourcePoints.containsKey(nameText) ? nameText : null;
			ConfigGuiModel.setNamedSchematicResourcePoint(draft, prev, nameText, new PositionConfig(x, y, z));
			persistDraftToLive();
			List<String> names = ConfigGuiModel.schematicResourcePointNames(draft);
			selectedIndex = Math.max(0, names.indexOf(nameText.trim()));
			activeKey = nameText.trim();
			setMessage("已保存资源点「" + activeKey + "」(" + x + ", " + y + ", " + z + ")，共 " + names.size() + " 个", false);
			init();
		} catch (IllegalArgumentException e) {
			setMessage(e.getMessage(), true);
		}
	}

	private void addCrosshairContainer() {
		if (minecraft == null || minecraft.level == null) {
			setMessage("当前未进入世界", true);
			return;
		}
		HitResult hit = minecraft.hitResult;
		if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
			setMessage("准星当前未指向任何方块，请对准箱子/木桶/潜影盒后再试", true);
			return;
		}
		BlockPos pos = bhr.getBlockPos();
		if (!SchematicResourceCache.isResourceContainer(minecraft.level.getBlockState(pos).getBlock())) {
			setMessage("准星所指方块不是箱子/木桶/潜影盒", true);
			return;
		}
		BlockPos canon = SchematicResourceCache.canonicalChestPos(minecraft.level, pos);
		if (containsCanonicalPos(canon)) {
			setMessage("该箱子已在资源点列表中 (" + canon.toShortString() + ")", false);
			return;
		}
		String newName = nextAutoName();
		ConfigGuiModel.setNamedSchematicResourcePoint(draft, null, newName,
				new PositionConfig(canon.getX(), canon.getY(), canon.getZ()));
		persistDraftToLive();
		List<String> names = ConfigGuiModel.schematicResourcePointNames(draft);
		selectedIndex = Math.max(0, names.indexOf(newName));
		loadSelected();
		setMessage("已添加准星箱子「" + newName + "」(" + canon.toShortString() + ")，共 " + names.size() + " 个", false);
		init();
	}

	private void addNearbyContainers() {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			setMessage("当前未进入世界", true);
			return;
		}
		BlockPos origin = minecraft.player.blockPosition();
		Set<BlockPos> existing = existingCanonicalSet();
		int added = 0;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -3; dy <= 4; dy++) {
			for (int dz = -NEARBY_SCAN_RADIUS; dz <= NEARBY_SCAN_RADIUS; dz++) {
				for (int dx = -NEARBY_SCAN_RADIUS; dx <= NEARBY_SCAN_RADIUS; dx++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!minecraft.level.isLoaded(cursor)) continue;
					if (!SchematicResourceCache.isResourceContainer(minecraft.level.getBlockState(cursor).getBlock())) {
						continue;
					}
					BlockPos canon = SchematicResourceCache.canonicalChestPos(minecraft.level, cursor.immutable());
					if (!existing.add(canon)) continue;
					String newName = nextAutoName();
					ConfigGuiModel.setNamedSchematicResourcePoint(draft, null, newName,
							new PositionConfig(canon.getX(), canon.getY(), canon.getZ()));
					added++;
				}
			}
		}
		if (added == 0) {
			setMessage("附近 " + NEARBY_SCAN_RADIUS + " 格内未发现新的箱子/木桶/潜影盒", false);
			return;
		}
		persistDraftToLive();
		selectedIndex = Math.max(0, draft.schematicResourcePoints.size() - 1);
		loadSelected();
		setMessage("已自动新增 " + added + " 个附近容器（大箱子已去重），共 " + draft.schematicResourcePoints.size() + " 个", false);
		init();
	}

	private void deleteCurrent() {
		captureBoxes();
		String key = activeKey != null ? activeKey : nameText;
		if (key == null || key.isBlank() || !draft.schematicResourcePoints.containsKey(key)) {
			setMessage("当前没有可删除的资源点", true);
			return;
		}
		ConfigGuiModel.removeNamedSchematicResourcePoint(draft, key);
		persistDraftToLive();
		loadSelected();
		setMessage("已删除资源点「" + key + "」", false);
		init();
	}

	private void clearAllPoints() {
		draft.schematicResourcePoints.clear();
		persistDraftToLive();
		loadSelected();
		setMessage("已清空全部投影资源点", false);
		init();
	}

	private void startAutoScan() {
		if (draft.schematicResourcePoints.isEmpty()) {
			setMessage("请先添加至少一个箱子作为资源点", true);
			return;
		}
		persistDraftToLive();
		String err = controller.startSchematicResourceScan(configs.get());
		if (err != null) {
			setMessage(err, true);
			return;
		}
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(null);
		}
	}

	private void openMaterialsCalculator() {
		persistDraftToLive();
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(new SchematicLayerMaterialsScreen(this, configs, controller, draft));
		}
	}

	private void saveAndReturn() {
		persistDraftToLive();
		onClose();
	}

	private void persistDraftToLive() {
		PerimeterConfig live = configs.get();
		ConfigGuiModel.copyInto(draft, live);
		configs.save();
		controller.syncConfig(live);
	}

	private Set<BlockPos> existingCanonicalSet() {
		Set<BlockPos> set = new HashSet<>();
		if (minecraft == null || minecraft.level == null) return set;
		for (PositionConfig p : draft.schematicResourcePoints.values()) {
			if (p != null) {
				set.add(SchematicResourceCache.canonicalChestPos(minecraft.level, new BlockPos(p.x, p.y, p.z)));
			}
		}
		return set;
	}

	private boolean containsCanonicalPos(BlockPos canon) {
		return existingCanonicalSet().contains(canon);
	}

	private String nextAutoName() {
		int idx = 1;
		while (draft.schematicResourcePoints.containsKey("res_" + idx)) {
			idx++;
		}
		return "res_" + idx;
	}

	private static int parseInt(String raw, String label) {
		if (raw == null || raw.isBlank()) throw new IllegalArgumentException("请填写 " + label);
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + " 必须是整数: " + raw);
		}
	}

	private void setMessage(String text, boolean error) {
		this.message = text == null ? "" : text;
		this.messageIsError = error;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		int left = panelLeft();
		int top = panelTop();

		g.centeredText(font, title, width / 2, top, 0xFFFFFFFF);
		g.fill(left, top + 92, left + 380, top + 185, 0x8810141C);

		var pool = SchematicResourceCache.aggregateConfiguredResources(
				minecraft == null ? null : minecraft.level, draft.schematicResourcePoints);
		g.text(font, "已配置资源点：" + draft.schematicResourcePoints.size() + " 个（去重容器 "
				+ pool.uniqueChestCount() + "，已扫 " + pool.scannedChestCount()
				+ (pool.totalShulkerBoxes() > 0 ? "，含 " + pool.totalShulkerBoxes() + " 潜影盒" : "") + "）",
				left + 6, top + 96, 0xFF99EEFF);
		g.fill(left + 4, top + 107, left + 376, top + 108, 0x66FFFFFF);

		List<String> names = ConfigGuiModel.schematicResourcePointNames(draft);
		if (names.isEmpty()) {
			g.centeredText(font, "支持箱子、木桶、摆放的潜影盒，及箱内/背包潜影盒自动识别与取料。", width / 2, top + 138, 0xFFAAAAAA);
		} else {
			int start = Math.max(0, Math.min(selectedIndex, Math.max(0, names.size() - 6)));
			int end = Math.min(names.size(), start + 6);
			for (int i = start; i < end; i++) {
				int row = i - start;
				int ry = top + 111 + row * 12;
				String key = names.get(i);
				PositionConfig p = draft.schematicResourcePoints.get(key);
				String prefix = (i == selectedIndex ? "▶ " : "  ") + key;
				g.text(font, prefix, left + 6, ry, i == selectedIndex ? 0xFFFFFF77 : 0xFFFFFFFF);
				if (p != null) {
					g.text(font, "(" + p.x + ", " + p.y + ", " + p.z + ")", left + 104, ry, 0xFFCCCCCC);
					var snap = SchematicResourceCache.getSnapshot(minecraft == null ? null : minecraft.level, p);
					if (snap == null) {
						g.text(font, "未扫描", left + 240, ry, 0xFFFFCC66);
					} else {
						int totalQty = 0;
						for (int v : snap.itemCounts().values()) totalQty += v;
						String shulkerTag = snap.shulkerBoxCount() > 0 ? " (" + snap.shulkerBoxCount() + "盒)" : "";
						g.text(font, "已扫:" + snap.itemCounts().size() + "种/" + totalQty + shulkerTag, left + 240, ry, 0xFF77FF88);
					}
				}
			}
		}

		if (!message.isEmpty()) {
			g.centeredText(font, message, width / 2, top + 214, messageIsError ? 0xFFFF8888 : 0xFF88FF88);
		} else {
			g.centeredText(font, "提示：添加箱子后点「自动扫描全部资源点」，即可判断资源足够建到第几层。", width / 2, top + 214, 0xFFCCCCCC);
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
