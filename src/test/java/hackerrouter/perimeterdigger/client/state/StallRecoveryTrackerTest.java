package hackerrouter.perimeterdigger.client.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StallRecoveryTrackerTest {
	@Test
	void reverseBacktrackStepsProgressivelyBackwardAlongSafeBreadcrumbs() {
		StallRecoveryTracker tracker = new StallRecoveryTracker();
		tracker.recordBreadcrumb(0, 64, 0);
		tracker.recordBreadcrumb(4, 64, 0);
		tracker.recordBreadcrumb(8, 64, 0);
		tracker.recordBreadcrumb(12, 64, 0);
		tracker.recordBreadcrumb(16, 64, 0);

		// Stuck at (18, 64, 0): should pick newest safe breadcrumb in [5..16] -> (12, 64, 0) (dist 6)
		StallRecoveryTracker.Breadcrumb first = tracker.selectReverseBacktrack(18, 64, 0, 5.0, 16.0, c -> true);
		assertEquals(new StallRecoveryTracker.Breadcrumb(12, 64, 0), first);

		// If stuck again near (12, 64, 0), tail was trimmed so it steps further back -> (4, 64, 0) (dist 8)
		StallRecoveryTracker.Breadcrumb second = tracker.selectReverseBacktrack(12, 64, 0, 5.0, 16.0, c -> true);
		assertEquals(new StallRecoveryTracker.Breadcrumb(4, 64, 0), second);

		// Skips unsafe breadcrumbs cleanly
		StallRecoveryTracker.Breadcrumb third = tracker.selectReverseBacktrack(4, 64, 0, 3.0, 16.0, c -> c.x() != 0);
		assertNull(third);
	}

	@Test
	void detectsInPlaceOscillationThatDefeatsSingleStepChecks() {
		StallRecoveryTracker tracker = new StallRecoveryTracker();
		// Shuffling back and forth by 1.5 blocks every sample (would defeat >0.25 single-step check)
		for (int i = 0; i < 16; i++) {
			double x = (i % 2 == 0) ? 10.0 : 11.5;
			tracker.recordSample(x, 64.0, 20.0, 16);
		}
		assertTrue(tracker.isOscillatingConfined(16, 2.5));

		// Actual sustained travel breaks confinement immediately
		tracker.recordSample(15.0, 64.0, 20.0, 16);
		assertFalse(tracker.isOscillatingConfined(16, 2.5));
	}

	@Test
	void deduplicatesTinyMovementsWhenRecordingBreadcrumbs() {
		StallRecoveryTracker tracker = new StallRecoveryTracker();
		tracker.recordBreadcrumb(10, 64, 10);
		tracker.recordBreadcrumb(11, 64, 10); // distance 1 < 2 -> ignored
		assertEquals(1, tracker.breadcrumbCount());
		tracker.recordBreadcrumb(12, 64, 10); // distance 2 -> recorded
		assertEquals(2, tracker.breadcrumbCount());
	}
}
