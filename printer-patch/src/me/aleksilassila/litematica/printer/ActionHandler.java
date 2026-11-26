package me.aleksilassila.litematica.printer;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import me.aleksilassila.litematica.printer.actions.Action;
import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.Hotkeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public class ActionHandler {
	private final Minecraft client;
	private final LocalPlayer player;
	private final Queue<Action> actionQueue = new LinkedList<>();
	public PrepareAction lookAction = null;
	private int tick = 0;

	public ActionHandler(Minecraft client, LocalPlayer player) {
		this.client = client;
		this.player = player;
	}

	public void onGameTick() {
		if (!Configs.PRINT_MODE.getBooleanValue() && !Hotkeys.PRINT.getKeybind().isPressed()) {
			this.actionQueue.clear();
			this.lookAction = null;
			return;
		}
		int interval = Math.max(1, Configs.PRINTING_INTERVAL.getIntegerValue());
		this.tick = (this.tick % interval == interval - 1) ? 0 : this.tick + 1;
		if (this.tick % interval != 0) {
			return;
		}
		if (this.actionQueue.isEmpty()) {
			this.lookAction = null;
			return;
		}
		Action action;
		while ((action = this.actionQueue.poll()) != null) {
			Printer.printDebug("Sending action {}", action);
			action.send(this.client, this.player);
		}
	}

	public boolean acceptsActions() {
		return this.actionQueue.isEmpty();
	}

	public void addActions(Action... actions) {
		if (!this.acceptsActions() || actions == null || actions.length == 0) {
			return;
		}
		for (Action action : actions) {
			if (action instanceof PrepareAction prepareAction) {
				this.lookAction = prepareAction;
			}
		}
		this.actionQueue.addAll(List.of(actions));
	}
}
