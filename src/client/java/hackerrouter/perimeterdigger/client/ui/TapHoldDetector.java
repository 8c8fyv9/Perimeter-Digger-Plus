package hackerrouter.perimeterdigger.client.ui;

/**
 * Distinguishes short press (tap) vs long press (hold) on a single keybind.
 * Handles both normal multi-tick taps and sub-tick fast taps via KeyMapping's click queue.
 */
public final class TapHoldDetector {
	public enum Action {
		NONE,
		SHORT_PRESS,
		LONG_PRESS
	}

	private final long holdThresholdMs;
	private boolean pressed;
	private boolean holdTriggered;
	private long pressStartMs;

	public TapHoldDetector(long holdThresholdMs) {
		if (holdThresholdMs <= 0L) {
			throw new IllegalArgumentException("holdThresholdMs must be positive");
		}
		this.holdThresholdMs = holdThresholdMs;
	}

	public Action update(boolean isDown, boolean clicked, long nowMs) {
		if (!pressed) {
			if (isDown) {
				pressed = true;
				holdTriggered = false;
				pressStartMs = nowMs;
				return Action.NONE;
			}
			return clicked ? Action.SHORT_PRESS : Action.NONE;
		}
		if (isDown) {
			if (!holdTriggered && (nowMs - pressStartMs) >= holdThresholdMs) {
				holdTriggered = true;
				return Action.LONG_PRESS;
			}
			return Action.NONE;
		}
		boolean wasHold = holdTriggered;
		pressed = false;
		holdTriggered = false;
		if (!wasHold) {
			return Action.SHORT_PRESS;
		}
		return clicked ? Action.SHORT_PRESS : Action.NONE;
	}

	public void reset() {
		pressed = false;
		holdTriggered = false;
		pressStartMs = 0L;
	}
}
