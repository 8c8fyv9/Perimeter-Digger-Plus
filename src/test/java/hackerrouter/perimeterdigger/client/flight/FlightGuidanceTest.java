package hackerrouter.perimeterdigger.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightGuidanceTest {
	@Test
	void arrivesOnlyWhenWithinToleranceAndSlowEnough() {
		FlightGuidance.Step settled = FlightGuidance.step(0.2, -0.1, 0.2, 0.02, 0.01, -0.02, 0.75, 0.65, false, false, false, 10);
		assertTrue(settled.arrived());
		assertFalse(settled.forward());
		assertFalse(settled.ascend());
		assertFalse(settled.descend());

		// Still moving fast -> should coast/brake, not report arrived yet
		FlightGuidance.Step fast = FlightGuidance.step(0.2, 0.0, 0.2, 0.4, 0.0, 0.0, 0.75, 0.65, false, false, false, 10);
		assertFalse(fast.arrived());
		assertFalse(fast.forward());
	}

	@Test
	void sprintsFarTargetsAndBrakesBeforeOvershooting() {
		FlightGuidance.Step far = FlightGuidance.step(25.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.75, 0.65, false, false, false, 2);
		assertFalse(far.arrived());
		assertTrue(far.forward());
		assertTrue(far.sprint());
		assertEquals(-90.0F, far.yaw(), 0.1F);

		// Approaching with high speed (brakeX = 0.5 * 7 = 3.5 >= dx = 3.0) -> coast to brake
		FlightGuidance.Step braking = FlightGuidance.step(3.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.75, 0.65, false, false, false, 2);
		assertFalse(braking.forward());
		assertFalse(braking.sprint());
	}

	@Test
	void climbsOverObstaclesAndAvoidsAccidentalGroundTouchWhenHovering() {
		FlightGuidance.Step blocked = FlightGuidance.step(10.0, -5.0, 0.0, 0.0, 0.0, 0.0, 0.75, 0.65, true, false, false, 1);
		assertTrue(blocked.ascend());
		assertFalse(blocked.descend());
		assertFalse(blocked.sprint());

		// Target below, ground immediately below, allowTouchGround=false -> do not press SNEAK
		FlightGuidance.Step hoverGuard = FlightGuidance.step(0.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.75, 0.65, false, true, false, 1);
		assertFalse(hoverGuard.descend());

		// Target below, ground immediately below, allowTouchGround=true -> allow landing descent
		FlightGuidance.Step landing = FlightGuidance.step(0.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.75, 0.65, false, true, true, 1);
		assertTrue(landing.descend());
	}

	@Test
	void tightExactBlockToleranceArrivesInsideTargetCellWithoutStoppingAdjacent() {
		// 0.6 blocks off-center is outside the target block and must NOT report arrived under tight flyToBlock tolerance
		FlightGuidance.Step outsideCell = FlightGuidance.step(0.6, 0.0, 0.0, 0.01, 0.0, 0.0, 0.32, 0.25, false, true, true, 2);
		assertFalse(outsideCell.arrived());
		assertTrue(outsideCell.forward());

		// 0.2 blocks off-center with low speed is strictly inside the target block -> arrives cleanly
		FlightGuidance.Step insideCell = FlightGuidance.step(0.2, 0.15, 0.1, 0.01, 0.0, 0.0, 0.32, 0.25, false, true, true, 2);
		assertTrue(insideCell.arrived());
	}
}
