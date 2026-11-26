/*
 * This file is part of the Perimeter Digger project, licensed under the
 * GNU General Public License v3.0 or later.
 *
 * Copyright (C) 2026  HackerRouter
 *
 * Perimeter Digger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Perimeter Digger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Perimeter Digger. If not, see <https://www.gnu.org/licenses/>.
 */

package hackerrouter.perimeterdigger.client;

import hackerrouter.perimeterdigger.PerimeterDigger;
import hackerrouter.perimeterdigger.client.command.PerimeterCommand;
import hackerrouter.perimeterdigger.client.config.WorldConfigManager;
import hackerrouter.perimeterdigger.client.state.AutomationController;
import hackerrouter.perimeterdigger.client.ui.AreaSelectionRenderer;
import hackerrouter.perimeterdigger.client.ui.ConfigGuiModel;
import hackerrouter.perimeterdigger.client.ui.PerimeterConfigScreen;
import hackerrouter.perimeterdigger.client.ui.TapHoldDetector;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class PerimeterDiggerClient implements ClientModInitializer {
	private static final long HOLD_OPEN_CONFIG_MS = 400L;
	private final WorldConfigManager configs = new WorldConfigManager();
	private final AutomationController controller = new AutomationController();
	private final hackerrouter.perimeterdigger.client.api.AgentTasks agentTasks = new hackerrouter.perimeterdigger.client.api.AgentTasks(new hackerrouter.perimeterdigger.client.api.MinecraftTaskBackend(configs, controller));
	private final TapHoldDetector keyDetector = new TapHoldDetector(HOLD_OPEN_CONFIG_MS);
	private final AreaSelectionRenderer areaRenderer = new AreaSelectionRenderer();
	private KeyMapping actionKey;
	private hackerrouter.perimeterdigger.client.api.AgentHttpServer agentServer;
	private static PerimeterDiggerClient instance;

	/** Called on the client thread before vanilla handles Escape. */
	public static void stopOnEscape() {
		if (instance == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gui != null && mc.gui.screen() != null) {
			return;
		}
		var state = instance.controller.state();
		if (state != hackerrouter.perimeterdigger.client.state.AutomationState.IDLE
				&& state != hackerrouter.perimeterdigger.client.state.AutomationState.COMPLETE) {
			instance.controller.stop();
			instance.agentTasks.tick();
		}
		// Standalone #goto / waypoint navigation is not owned by the Perimeter controller.
		for (var bot : baritone.api.BaritoneAPI.getProvider().getAllBaritones()) {
			bot.getPathingBehavior().forceCancel();
			bot.getInputOverrideHandler().clearAllKeys();
		}
	}

	public static void onDamage(net.minecraft.network.protocol.game.ClientboundDamageEventPacket packet) {
		if (instance != null) instance.controller.onDamage(packet);
	}

	public static void onRespawn() {
		if (instance != null) instance.controller.onRespawn();
	}

	private String activeIdentity;
	private String activeDimension;

	@Override
	public void onInitializeClient() {
		instance = this;
		KeyMapping.Category keyCategory = KeyMapping.Category.register(Identifier.parse("perimeter_digger:main"));
		actionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.perimeter_digger.action", GLFW.GLFW_KEY_F9, keyCategory));
		PerimeterCommand command = new PerimeterCommand(configs, controller, agentTasks::ownsController);
		var apiDispatcher = new com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>();
		apiDispatcher.register(command.build());
		var commands = new hackerrouter.perimeterdigger.client.api.CommandApi<>(apiDispatcher, hackerrouter.perimeterdigger.client.api.MinecraftCommandSource::create);
		var apiConfig = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("perimeter-digger-plus/agent-api");
		boolean enabled = Boolean.parseBoolean(System.getProperty("perimeterdig.api.enabled", Boolean.toString(java.nio.file.Files.exists(apiConfig.resolve("enabled")))));
		if (enabled) {
			try { agentServer = hackerrouter.perimeterdigger.client.api.AgentHttpServer.start(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("perimeter-digger-plus/agent-api"), (method, path, body) -> path.equals("/v1/commands") ? commands.route(method, path, body) : agentTasks.route(method, path, body), net.minecraft.client.Minecraft.getInstance()::execute); }
			catch (java.io.IOException e) { PerimeterDigger.LOGGER.error("Agent API failed to start; mining mod remains available", e); }
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { if (agentServer != null) agentServer.close(); });
		}
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(command.build()));
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> areaRenderer.collectSubmits(context, configs));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			agentTasks.disconnect();
			controller.resetForWorldChange();
			activeIdentity = null;
			activeDimension = null;
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handleActionKey(client);
			if (client.level == null) return;
			try {
				String identity = configs.identity();
				String dimension = client.level.dimension().identifier().toString();
				if (activeIdentity != null && (!activeIdentity.equals(identity) || !dimension.equals(activeDimension))) {
					if (!activeIdentity.equals(identity)) agentTasks.disconnect();
					controller.handleWorldChange();
				}
				activeIdentity = identity;
				activeDimension = dimension;
				controller.tick();
				agentTasks.tick();
			} catch (RuntimeException exception) {
				PerimeterDigger.LOGGER.error("Unhandled perimeter automation tick failure", exception);
				controller.handleTickFailure(exception);
			}
		});
	}

	private void handleActionKey(Minecraft client) {
		if (actionKey == null) return;
		boolean clicked = false;
		while (actionKey.consumeClick()) {
			clicked = true;
		}
		if (client.player == null || client.level == null || client.gui.screen() != null) {
			keyDetector.reset();
			return;
		}
		TapHoldDetector.Action action = keyDetector.update(actionKey.isDown(), clicked, System.currentTimeMillis());
		if (action == TapHoldDetector.Action.LONG_PRESS) {
			keyDetector.reset();
			client.gui.setScreen(new PerimeterConfigScreen(configs, controller));
		} else if (action == TapHoldDetector.Action.SHORT_PRESS) {
			handleShortPress(client);
		}
	}

	private void handleShortPress(Minecraft client) {
		try {
			switch (ConfigGuiModel.resolveShortPress(controller.state())) {
				case PAUSE -> {
					controller.pause();
					overlay(client, "已暂停挖掘（短按 F9 恢复，长按 F9 设置）");
				}
				case RESUME -> {
					controller.resume(configs.get());
					overlay(client, "已恢复区域挖掘（短按 F9 暂停）");
				}
				case START -> {
					var missing = controller.start(configs.get());
					if (missing.isEmpty()) {
						overlay(client, "已开始区域挖掘（短按 F9 暂停，长按 F9 设置）");
					} else {
						overlay(client, "配置未完成（缺少 " + String.join(", ", missing) + "），长按 F9 打开设置");
					}
				}
			}
		} catch (RuntimeException exception) {
			String msg = exception.getMessage();
			overlay(client, "操作失败：" + (msg == null || msg.isBlank() ? exception.getClass().getSimpleName() : msg));
		}
	}

	private static void overlay(Minecraft client, String message) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal("[自动挖掘] " + message));
		}
	}
}
