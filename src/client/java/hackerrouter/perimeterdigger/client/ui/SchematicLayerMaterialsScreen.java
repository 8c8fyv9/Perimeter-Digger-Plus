package hackerrouter.perimeterdigger.client.ui;

import hackerrouter.perimeterdigger.client.build.SchematicLayerMaterialCalculator;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.WorldConfigManager;
import hackerrouter.perimeterdigger.client.state.AutomationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Interactive schematic material calculator opened from the F9 configuration screen.
 * Provides three direct views:
 * 1) Current target placement layer (automatically synced to the active/paused builder layer or
 *    the lowest unfinished layer, with prev/next stepping to inspect any single layer);
 * 2) All unfinished layers overview (scrollable list with per-layer material brief, resource
 *    status, and one-click jump into any layer's full detail);
 * 3) Grand total materials across the schematic (remaining-only or full schematic scope),
 *    paired with live backpack + resource point (including shulker boxes) sufficiency verdict.
 */
public final class SchematicLayerMaterialsScreen extends Screen {
	private enum ViewTab {
		TARGET_LAYER,
		UNFINISHED_LAYERS,
		GRAND_TOTAL
	}

	private static final int VISIBLE_ROWS = 6;

	private final Screen parent;
	private final WorldConfigManager configs;
	private final AutomationController controller;
	private final PerimeterConfig config;

	private ViewTab activeTab = ViewTab.TARGET_LAYER;
	private boolean remainingOnly = true;
	private SchematicLayerMaterialCalculator.Report report;
	private List<Integer> unfinishedIndices = List.of();
	private int targetLayerIndex = 0;
	private int selectedLayerIndex = 0;
	private int scrollOffset = 0;
	private String message = "";
	private boolean messageIsError = false;

	public SchematicLayerMaterialsScreen(
			Screen parent,
			WorldConfigManager configs,
			AutomationController controller,
			PerimeterConfig config
	) {
		super(Component.literal("投影所需物品与资源点判定"));
		this.parent = parent;
		this.configs = configs;
		this.controller = controller;
		this.config = config;
	}

	private int panelLeft() {
		return width / 2 - 190;
	}

	private int panelTop() {
		return Math.max(8, height / 2 - 114);
	}

	@Override
	protected void init() {
		if (report == null) {
			recompute(true);
		}
		refreshWidgets();
	}

	private void refreshWidgets() {
		clearWidgets();
		int left = panelLeft();
		int top = panelTop();

		// Row 1: 3 direct view tabs + scope toggle (total 380px)
		Button tabTarget = addRenderableWidget(Button.builder(
				Component.literal((activeTab == ViewTab.TARGET_LAYER ? "● " : "") + "当前目标层"),
				b -> switchTab(ViewTab.TARGET_LAYER, true)
		).bounds(left, top + 14, 88, 18).build());
		Button tabUnfinished = addRenderableWidget(Button.builder(
				Component.literal((activeTab == ViewTab.UNFINISHED_LAYERS ? "● " : "")
						+ "未完成层(" + unfinishedIndices.size() + ")"),
				b -> switchTab(ViewTab.UNFINISHED_LAYERS, false)
		).bounds(left + 92, top + 14, 104, 18).build());
		Button tabTotal = addRenderableWidget(Button.builder(
				Component.literal((activeTab == ViewTab.GRAND_TOTAL ? "● " : "") + "总体材料"),
				b -> switchTab(ViewTab.GRAND_TOTAL, false)
		).bounds(left + 200, top + 14, 78, 18).build());
		tabTarget.active = activeTab != ViewTab.TARGET_LAYER || selectedLayerIndex != targetLayerIndex;
		tabUnfinished.active = activeTab != ViewTab.UNFINISHED_LAYERS;
		tabTotal.active = activeTab != ViewTab.GRAND_TOTAL;

		addRenderableWidget(Button.builder(
				Component.literal("口径:" + (remainingOnly ? "仅待放" : "全投影")),
				b -> {
					remainingOnly = !remainingOnly;
					recompute(false);
					refreshWidgets();
				}
		).bounds(left + 282, top + 14, 98, 18).build());

		// Row 2: Context controls + Resource points + Export (total 380px)
		int pointCount = config == null || config.schematicResourcePoints == null
				? 0 : config.schematicResourcePoints.size();
		if (activeTab == ViewTab.TARGET_LAYER) {
			Button prevBtn = addRenderableWidget(Button.builder(Component.literal("◀"), b -> stepLayer(-1))
					.bounds(left, top + 36, 20, 18).build());
			addRenderableWidget(Button.builder(layerPickerLabel(), b -> {
				selectedLayerIndex = targetLayerIndex;
				scrollOffset = 0;
				setMessage("已回到当前目标层", false);
				refreshWidgets();
			}).bounds(left + 22, top + 36, 124, 18).build());
			Button nextBtn = addRenderableWidget(Button.builder(Component.literal("▶"), b -> stepLayer(1))
					.bounds(left + 148, top + 36, 20, 18).build());
			boolean canStep = report != null && report.ok() && report.layers().size() > 1;
			prevBtn.active = canStep;
			nextBtn.active = canStep;

			addRenderableWidget(Button.builder(Component.literal("资源点(" + pointCount + ")"), b -> openResourcePoints())
					.bounds(left + 172, top + 36, 66, 18).build());
			addRenderableWidget(Button.builder(Component.literal("扫描资源"), b -> scanResourcePoints())
					.bounds(left + 241, top + 36, 56, 18).build());
			addRenderableWidget(Button.builder(Component.literal("复制"), b -> copyFullReport())
					.bounds(left + 300, top + 36, 38, 18).build());
			addRenderableWidget(Button.builder(Component.literal("发聊天"), b -> sendCurrentToChat())
					.bounds(left + 340, top + 36, 40, 18).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("查看当前目标层"), b -> switchTab(ViewTab.TARGET_LAYER, true))
					.bounds(left, top + 36, 108, 18).build());
			addRenderableWidget(Button.builder(Component.literal("资源点(" + pointCount + ")"), b -> openResourcePoints())
					.bounds(left + 112, top + 36, 76, 18).build());
			addRenderableWidget(Button.builder(Component.literal("扫描资源点"), b -> scanResourcePoints())
					.bounds(left + 192, top + 36, 72, 18).build());
			addRenderableWidget(Button.builder(Component.literal("复制清单"), b -> copyFullReport())
					.bounds(left + 268, top + 36, 56, 18).build());
			addRenderableWidget(Button.builder(Component.literal("发聊天"), b -> sendCurrentToChat())
					.bounds(left + 328, top + 36, 52, 18).build());
		}

		// Per-row "明细" jump buttons on UNFINISHED_LAYERS tab
		if (activeTab == ViewTab.UNFINISHED_LAYERS && report != null && report.ok() && !unfinishedIndices.isEmpty()) {
			int end = Math.min(unfinishedIndices.size(), scrollOffset + VISIBLE_ROWS);
			for (int i = scrollOffset; i < end; i++) {
				int row = i - scrollOffset;
				final int layerIdx = unfinishedIndices.get(i);
				addRenderableWidget(Button.builder(Component.literal("明细"), b -> {
					selectedLayerIndex = layerIdx;
					activeTab = ViewTab.TARGET_LAYER;
					scrollOffset = 0;
					refreshWidgets();
				}).bounds(left + 324, top + 109 + row * 12, 34, 11).build());
			}
		}

		// Scroll buttons if current list exceeds visible rows
		int totalRows = currentListSize();
		if (totalRows > VISIBLE_ROWS) {
			Button upBtn = addRenderableWidget(Button.builder(Component.literal("▲"), b -> scrollBy(-VISIBLE_ROWS))
					.bounds(left + 362, top + 108, 18, 36).build());
			Button downBtn = addRenderableWidget(Button.builder(Component.literal("▼"), b -> scrollBy(VISIBLE_ROWS))
					.bounds(left + 362, top + 146, 18, 36).build());
			upBtn.active = scrollOffset > 0;
			downBtn.active = scrollOffset + VISIBLE_ROWS < totalRows;
		}

		// Bottom row
		addRenderableWidget(Button.builder(Component.literal("重新计算"), b -> {
			recompute(false);
			refreshWidgets();
		}).bounds(left, top + 190, 186, 20).build());
		addRenderableWidget(Button.builder(Component.literal("返回设置"), b -> onClose())
				.bounds(left + 194, top + 190, 186, 20).build());
	}

	private void switchTab(ViewTab tab, boolean snapToTarget) {
		this.activeTab = tab;
		if (snapToTarget) {
			this.selectedLayerIndex = this.targetLayerIndex;
		}
		this.scrollOffset = 0;
		refreshWidgets();
	}

	private void recompute(boolean snapToTarget) {
		int previousY = (report != null && report.ok() && selectedLayerIndex >= 0
				&& selectedLayerIndex < report.layers().size())
				? report.layers().get(selectedLayerIndex).y()
				: Integer.MIN_VALUE;

		this.report = SchematicLayerMaterialCalculator.computeAllLayers(minecraft, config, remainingOnly);
		this.scrollOffset = 0;
		if (!report.ok()) {
			this.unfinishedIndices = List.of();
			this.targetLayerIndex = 0;
			this.selectedLayerIndex = 0;
			setMessage(report.errorMessage(), true);
			return;
		}

		this.unfinishedIndices = SchematicLayerMaterialCalculator.unfinishedLayerIndices(report);
		Integer activeBuilderY = controller != null ? controller.currentSchematicTargetY() : null;
		this.targetLayerIndex = SchematicLayerMaterialCalculator.resolveTargetLayerIndex(report, activeBuilderY);

		if (snapToTarget || previousY == Integer.MIN_VALUE) {
			this.selectedLayerIndex = this.targetLayerIndex;
		} else {
			int matched = this.targetLayerIndex;
			for (int i = 0; i < report.layers().size(); i++) {
				if (report.layers().get(i).y() == previousY) {
					matched = i;
					break;
				}
			}
			this.selectedLayerIndex = matched;
		}

		String targetHint = report.layers().isEmpty()
				? ""
				: "，当前目标第 " + (targetLayerIndex + 1) + " 层(Y=" + report.layers().get(targetLayerIndex).y() + ")";
		setMessage("共 " + report.layers().size() + " 层，未完成 " + unfinishedIndices.size() + " 层"
				+ targetHint + "，总计 " + report.grandTotal().items().size() + " 种物品", false);
	}

	private void stepLayer(int delta) {
		if (report == null || !report.ok() || report.layers().isEmpty()) return;
		activeTab = ViewTab.TARGET_LAYER;
		selectedLayerIndex = Math.floorMod(selectedLayerIndex + delta, report.layers().size());
		scrollOffset = 0;
		refreshWidgets();
	}

	private void openResourcePoints() {
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(new SchematicResourcePointsScreen(this, configs, controller, config));
		}
	}

	private void scanResourcePoints() {
		if (config == null || config.schematicResourcePoints == null || config.schematicResourcePoints.isEmpty()) {
			setMessage("请先点击「资源点」添加至少一个箱子或潜影盒", true);
			return;
		}
		PerimeterConfig live = configs.get();
		ConfigGuiModel.copyInto(config, live);
		configs.save();
		controller.syncConfig(live);
		String err = controller.startSchematicResourceScan(live);
		if (err != null) {
			setMessage(err, true);
			return;
		}
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(null);
		}
	}

	private void copyFullReport() {
		if (report == null || !report.ok()) {
			setMessage("当前无可导出的投影统计", true);
			return;
		}
		if (minecraft != null && minecraft.keyboardHandler != null) {
			minecraft.keyboardHandler.setClipboard(SchematicLayerMaterialCalculator.formatExportReport(report));
			setMessage("已复制全部未完成层与总体清单到剪贴板", false);
		}
	}

	private void sendCurrentToChat() {
		if (report == null || !report.ok()) {
			setMessage("当前无可发送的投影统计", true);
			return;
		}
		String line;
		if (activeTab == ViewTab.UNFINISHED_LAYERS) {
			line = SchematicLayerMaterialCalculator.formatUnfinishedOverviewChat(report, targetLayerIndex);
		} else if (activeTab == ViewTab.GRAND_TOTAL) {
			line = SchematicLayerMaterialCalculator.formatSingleLayerChat(report, report.grandTotal(), 0);
		} else {
			SchematicLayerMaterialCalculator.LayerEntry entry = selectedLayerEntry();
			if (entry == null) return;
			line = SchematicLayerMaterialCalculator.formatSingleLayerChat(report, entry, selectedLayerIndex + 1);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gui != null) {
			mc.gui.chatListener().handleSystemMessage(Component.literal(line), false);
			setMessage("已将当前视图材料统计输出到聊天栏", false);
		}
	}

	private int currentListSize() {
		if (report == null || !report.ok()) return 0;
		return switch (activeTab) {
			case TARGET_LAYER -> {
				var e = selectedLayerEntry();
				yield e == null ? 0 : e.items().size();
			}
			case UNFINISHED_LAYERS -> unfinishedIndices.size();
			case GRAND_TOTAL -> report.grandTotal().items().size();
		};
	}

	private void scrollBy(int delta) {
		int maxOffset = Math.max(0, currentListSize() - VISIBLE_ROWS);
		int next = Math.max(0, Math.min(maxOffset, scrollOffset + delta));
		if (next != scrollOffset) {
			scrollOffset = next;
			refreshWidgets();
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (verticalAmount != 0.0) {
			scrollBy(verticalAmount > 0 ? -2 : 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private SchematicLayerMaterialCalculator.LayerEntry selectedLayerEntry() {
		if (report == null || !report.ok() || report.layers().isEmpty()) return null;
		int idx = Math.max(0, Math.min(report.layers().size() - 1, selectedLayerIndex));
		return report.layers().get(idx);
	}

	private Component layerPickerLabel() {
		if (report == null || !report.ok() || report.layers().isEmpty()) {
			return Component.literal("未加载投影");
		}
		int idx = Math.max(0, Math.min(report.layers().size() - 1, selectedLayerIndex));
		int y = report.layers().get(idx).y();
		String tag = idx == targetLayerIndex ? "★目标 " : "回目标|";
		return Component.literal(tag + "第" + (idx + 1) + "/" + report.layers().size() + "层 Y=" + y);
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
		g.fill(left, top + 94, left + 380, top + 186, 0x8810141C);

		if (report == null || !report.ok()) {
			String err = report == null ? "正在计算..." : report.errorMessage();
			g.centeredText(font, err, width / 2, top + 134, 0xFFFF8888);
		} else {
			renderSummaryLines(g, left, top);
			renderSufficiencyBanner(g, left, top);
			if (activeTab == ViewTab.UNFINISHED_LAYERS) {
				renderUnfinishedLayersTable(g, left, top);
			} else {
				SchematicLayerMaterialCalculator.LayerEntry entry = activeTab == ViewTab.GRAND_TOTAL
						? report.grandTotal()
						: selectedLayerEntry();
				renderItemTable(g, left, top, entry);
			}
		}

		if (!message.isEmpty()) {
			g.centeredText(font, message, width / 2, top + 214, messageIsError ? 0xFFFF8888 : 0xFF88FF88);
		}
	}

	private void renderSummaryLines(GuiGraphicsExtractor g, int left, int top) {
		String scopeTag = report.remainingOnly() ? "仅待放" : "全投影";
		if (activeTab == ViewTab.UNFINISHED_LAYERS) {
			String targetDesc = report.layers().isEmpty()
					? "无"
					: "第" + (targetLayerIndex + 1) + "层(Y=" + report.layers().get(targetLayerIndex).y() + ")";
			String head1 = "所有未完成层：" + unfinishedIndices.size() + "/" + report.layers().size()
					+ " 层 [" + scopeTag + "] | 当前目标：" + targetDesc;
			g.text(font, truncate(head1, 56), left, top + 57, 0xFF99EEFF);
			g.text(font, "提示：▶ 标示当前放置目标层；点右侧「明细」或滚轮查看全部未完成层。", left, top + 69, 0xFFAAAAAA);
			return;
		}

		SchematicLayerMaterialCalculator.LayerEntry entry = activeTab == ViewTab.GRAND_TOTAL
				? report.grandTotal()
				: selectedLayerEntry();
		String viewPrefix;
		if (activeTab == ViewTab.GRAND_TOTAL) {
			viewPrefix = "总体材料(未完" + unfinishedIndices.size() + "层)";
		} else {
			int idx = Math.max(0, Math.min(report.layers().size() - 1, selectedLayerIndex));
			int y = report.layers().get(idx).y();
			viewPrefix = (idx == targetLayerIndex ? "当前目标层" : "单层查看")
					+ " 第" + (idx + 1) + "/" + report.layers().size() + "层(Y=" + y + ")";
		}
		String head1 = viewPrefix + " [" + scopeTag + "] | "
				+ (entry == null ? 0 : entry.items().size()) + " 种物品 (共 "
				+ SchematicLayerMaterialCalculator.formatStackCount(entry == null ? 0 : entry.totalRequiredItems()) + ")";
		g.text(font, truncate(head1, 56), left, top + 57, 0xFF99EEFF);

		if (entry != null && (entry.waterloggedCells() > 0 || entry.waterSourceCells() > 0)) {
			StringBuilder waterLine = new StringBuilder("水体：");
			if (entry.waterloggedCells() > 0) {
				waterLine.append("含水").append(entry.waterloggedCells()).append("格 ");
			}
			if (entry.waterSourceCells() > 0) {
				waterLine.append("水源").append(entry.waterSourceCells()).append("格(");
				if (report.optimizeWater() && entry.savedWaterSeeds() > 0) {
					waterLine.append("优化倒水").append(entry.plannedWaterSeeds())
							.append("次,省").append(entry.savedWaterSeeds()).append("次)");
				} else {
					waterLine.append("倒水").append(entry.plannedWaterSeeds()).append("次)");
				}
			}
			g.text(font, waterLine.toString(), left, top + 69, 0xFF77DDFF);
		} else if (entry != null && entry.unloadedCells() > 0) {
			g.text(font, "注意：当前范围有 " + entry.unloadedCells() + " 格投影区块未加载。", left, top + 69, 0xFFFFCC66);
		} else {
			g.text(font, "提示：上方可一键切换「当前目标层 / 未完成层 / 总体材料」，滚轮翻页。", left, top + 69, 0xFFAAAAAA);
		}
	}

	private void renderSufficiencyBanner(GuiGraphicsExtractor g, int left, int top) {
		var suf = report.sufficiency();
		if (suf == null) return;
		String verdict;
		int color;
		if (!suf.hasConfiguredChests()) {
			verdict = "资源点：未配置 | "
					+ (suf.overallEnough() ? "仅靠背包总体足够" : "仅靠背包可建至第 " + suf.supportedLayerCount() + "/" + suf.totalLayers() + " 层");
			color = suf.overallEnough() ? 0xFF77FF88 : 0xFFFFCC66;
		} else if (!suf.hasScannedChests()) {
			verdict = "资源点：已配 " + suf.configuredChests() + " 处未扫描（点「扫描资源」读取箱子/潜影盒）";
			color = 0xFFFFCC66;
		} else if (suf.overallEnough()) {
			verdict = "资源判定：总体足够！可完整建造全部 " + suf.totalLayers() + " 层 [已扫 "
					+ suf.scannedChests() + "/" + suf.configuredChests() + "]";
			color = 0xFF77FF88;
		} else if (suf.supportedLayerCount() > 0) {
			verdict = "资源判定：总体不足 | 可建至第 " + suf.supportedLayerCount() + "/" + suf.totalLayers()
					+ " 层(Y=" + suf.supportedThroughY() + ")，卡第" + suf.firstBlockedLayerNo()
					+ "层(Y=" + suf.firstBlockedY() + ")";
			color = 0xFFFF9966;
		} else {
			verdict = "资源判定：总体不足 | 第 " + suf.firstBlockedLayerNo() + " 层(Y=" + suf.firstBlockedY()
					+ ")即缺料 [已扫 " + suf.scannedChests() + "/" + suf.configuredChests() + "]";
			color = 0xFFFF7777;
		}
		g.text(font, truncate(verdict, 54), left, top + 81, color);
	}

	private void renderUnfinishedLayersTable(GuiGraphicsExtractor g, int left, int top) {
		g.text(font, "层号 / Y高度", left + 6, top + 97, 0xFFCCCCCC);
		g.text(font, "所需主要材料与水体", left + 86, top + 97, 0xFFCCCCCC);
		g.text(font, "状态", left + 292, top + 97, 0xFFCCCCCC);
		g.fill(left + 4, top + 107, left + 358, top + 108, 0x66FFFFFF);

		if (unfinishedIndices.isEmpty()) {
			g.centeredText(font, "当前投影全部层均已完成，无待放材料！", width / 2, top + 138, 0xFF88FF88);
			return;
		}

		var suf = report.sufficiency();
		int end = Math.min(unfinishedIndices.size(), scrollOffset + VISIBLE_ROWS);
		for (int i = scrollOffset; i < end; i++) {
			int row = i - scrollOffset;
			int ry = top + 111 + row * 12;
			int layerIdx = unfinishedIndices.get(i);
			var layer = report.layers().get(layerIdx);
			boolean isTarget = layerIdx == targetLayerIndex;

			String layerTag = (isTarget ? "▶ " : "  ") + "第" + (layerIdx + 1) + "层 Y=" + layer.y();
			g.text(font, layerTag, left + 4, ry, isTarget ? 0xFFFFEE66 : 0xFFFFFFFF);

			String brief = SchematicLayerMaterialCalculator.formatLayerBrief(layer, report.optimizeWater());
			g.text(font, truncate(brief, 28), left + 86, ry, isTarget ? 0xFFAAEEFF : 0xFFDDDDDD);

			if (suf != null) {
				String badge;
				int badgeColor;
				if (suf.overallEnough() || layerIdx < suf.supportedLayerCount()) {
					badge = "可建";
					badgeColor = 0xFF77FF88;
				} else if (layerIdx + 1 == suf.firstBlockedLayerNo()) {
					badge = "首缺";
					badgeColor = 0xFFFF7777;
				} else {
					badge = "待补";
					badgeColor = 0xFFFFBB66;
				}
				g.text(font, badge, left + 292, ry, badgeColor);
			}
		}
	}

	private void renderItemTable(
			GuiGraphicsExtractor g,
			int left,
			int top,
			SchematicLayerMaterialCalculator.LayerEntry entry
	) {
		var suf = report.sufficiency();
		boolean hasResourcePool = suf != null && suf.hasScannedChests();

		g.text(font, "物品名称", left + 6, top + 97, 0xFFCCCCCC);
		g.text(font, "所需数量", left + 150, top + 97, 0xFFCCCCCC);
		g.text(font, hasResourcePool ? "背包+资源点 / 缺口" : "背包对比", left + 242, top + 97, 0xFFCCCCCC);
		g.fill(left + 4, top + 107, left + 358, top + 108, 0x66FFFFFF);

		if (entry == null || entry.items().isEmpty()) {
			String emptyHint = (entry != null && (entry.waterloggedCells() > 0 || entry.waterSourceCells() > 0))
					? "本层无实体方块物品需求（仅含水体，请见上方水体统计）"
					: "当前范围无需放置任何方块物品";
			g.centeredText(font, emptyHint, width / 2, top + 138, 0xFF88FF88);
			return;
		}

		int end = Math.min(entry.items().size(), scrollOffset + VISIBLE_ROWS);
		for (int i = scrollOffset; i < end; i++) {
			int row = i - scrollOffset;
			int ry = top + 111 + row * 12;
			var item = entry.items().get(i);
			g.text(font, truncate(item.displayName(), 20), left + 6, ry, 0xFFFFFFFF);
			g.text(font, SchematicLayerMaterialCalculator.formatStackCount(item.required()), left + 150, ry, 0xFFFFEE99);
			if (hasResourcePool) {
				int totalStock = item.available() + item.inResourceChests();
				if (item.combinedMissing() > 0) {
					g.text(font, "缺" + item.combinedMissing() + " (背" + item.available() + "+箱" + item.inResourceChests() + ")", left + 242, ry, 0xFFFF8888);
				} else {
					g.text(font, "足 (背" + item.available() + "+箱" + item.inResourceChests() + "=" + totalStock + ")", left + 242, ry, 0xFF77FF88);
				}
			} else if (item.missing() > 0) {
				g.text(font, "缺 " + item.missing() + " (有" + item.available() + ")", left + 242, ry, 0xFFFF8888);
			} else {
				g.text(font, "充足 (有" + item.available() + ")", left + 242, ry, 0xFF77FF88);
			}
		}
	}

	private static String truncate(String text, int maxChars) {
		if (text == null || text.length() <= maxChars) return text == null ? "" : text;
		return text.substring(0, Math.max(0, maxChars - 1)) + "…";
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
