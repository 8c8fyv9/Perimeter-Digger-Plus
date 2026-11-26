package hackerrouter.perimeterdigger.client.detect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LitematicaFootprintReaderTest {
	public enum FakeRequiredEnabled {
		ANY, PLACEMENT_ENABLED, RENDERING_ENABLED
	}

	public static final class FakePos {
		private final int x, y, z;
		FakePos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getZ() { return z; }
	}

	public static final class FakeBox {
		private final FakePos p1, p2;
		FakeBox(FakePos p1, FakePos p2) { this.p1 = p1; this.p2 = p2; }
		public FakePos getPos1() { return p1; }
		public FakePos getPos2() { return p2; }
	}

	public static final class FakePlacement {
		private final String name;
		private final boolean enabled;
		private final Map<String, FakeBox> enabledBoxes;
		private final Map<String, FakeBox> anyBoxes;
		private final FakeBox enclosing;

		FakePlacement(String name, boolean enabled, Map<String, FakeBox> enabledBoxes, Map<String, FakeBox> anyBoxes, FakeBox enclosing) {
			this.name = name;
			this.enabled = enabled;
			this.enabledBoxes = enabledBoxes;
			this.anyBoxes = anyBoxes;
			this.enclosing = enclosing;
		}

		public String getName() { return name; }
		public boolean isEnabled() { return enabled; }
		public FakeBox getEclosingBox() { return enclosing; }
		public Map<String, FakeBox> getSubRegionBoxes(FakeRequiredEnabled req) {
			return req == FakeRequiredEnabled.PLACEMENT_ENABLED ? enabledBoxes : anyBoxes;
		}
	}

	public static final class BrokenPlacement {
		public String getName() { return "broken"; }
	}

	@Test
	void mergesMultipleSubRegionsWithInvertedCorners() {
		LitematicaFootprintReader.Footprint fp = LitematicaFootprintReader.mergeBoxes(
				"fish_farm",
				List.of(
						new int[]{100, 60, 200, 90, 50, 190},
						new int[]{101, 55, 195, 110, 72, 205}
				),
				2
		);
		assertEquals("fish_farm", fp.placementName());
		assertEquals(90, fp.minX());
		assertEquals(110, fp.maxX());
		assertEquals(50, fp.minY());
		assertEquals(72, fp.maxY());
		assertEquals(190, fp.minZ());
		assertEquals(205, fp.maxZ());
		assertEquals(2, fp.subRegionCount());
		assertEquals(21L * 16L, fp.columnCount());
		assertEquals(21L * 16L * 23L, fp.blockCount());
	}

	@Test
	void prefersSelectedPlacementOverCloserOthers() throws Exception {
		FakePlacement farSelected = new FakePlacement(
				"selected_build", true,
				Map.of("main", new FakeBox(new FakePos(500, 64, 500), new FakePos(510, 70, 510))),
				Map.of(), null
		);
		FakePlacement nearOther = new FakePlacement(
				"near_build", true,
				Map.of("main", new FakeBox(new FakePos(0, 64, 0), new FakePos(10, 70, 10))),
				Map.of(), null
		);
		LitematicaFootprintReader.ReadResult res = LitematicaFootprintReader.extractFromPlacements(
				List.of(nearOther, farSelected), farSelected, 5, 64, 5
		);
		assertTrue(res.ok());
		assertEquals("selected_build", res.footprint().placementName());
		assertEquals(500, res.footprint().minX());
		assertEquals(510, res.footprint().maxX());
	}

	@Test
	void picksNearestEnabledPlacementWhenNoneSelectedAndFallsBackToEnclosingBox() throws Exception {
		FakePlacement disabledClose = new FakePlacement(
				"disabled_close", false, Map.of(), Map.of(),
				new FakeBox(new FakePos(0, 60, 0), new FakePos(5, 65, 5))
		);
		FakePlacement enabledNear = new FakePlacement(
				"enabled_near", true, Map.of(), Map.of(),
				new FakeBox(new FakePos(30, 50, 30), new FakePos(45, 87, 45))
		);
		FakePlacement enabledFar = new FakePlacement(
				"enabled_far", true, Map.of(), Map.of(),
				new FakeBox(new FakePos(300, 50, 300), new FakePos(320, 87, 320))
		);
		LitematicaFootprintReader.ReadResult res = LitematicaFootprintReader.extractFromPlacements(
				List.of(disabledClose, enabledFar, enabledNear), null, 10, 64, 10
		);
		assertTrue(res.ok());
		assertEquals("enabled_near", res.footprint().placementName());
		assertEquals(30, res.footprint().minX());
		assertEquals(87, res.footprint().maxY());
	}

	@Test
	void reportsNoPlacementAndEmptyBoundsAndIncompatibleCleanly() {
		LitematicaFootprintReader.ReadResult notInstalled = LitematicaFootprintReader.ReadResult.failure(
				LitematicaFootprintReader.ErrorCode.NOT_INSTALLED, "");
		assertFalse(notInstalled.ok());
		assertTrue(notInstalled.userMessage().contains("未检测到投影模组"));

		LitematicaFootprintReader.ReadResult noPlacement = assertDoesNotThrow(() ->
				LitematicaFootprintReader.extractFromPlacements(List.of(), null, 0, 0, 0));
		assertEquals(LitematicaFootprintReader.ErrorCode.NO_PLACEMENT, noPlacement.errorCode());
		assertTrue(noPlacement.userMessage().contains("未找到已放置的投影"));

		FakePlacement empty = new FakePlacement("empty_schem", true, Map.of(), Map.of(), null);
		LitematicaFootprintReader.ReadResult emptyRes = assertDoesNotThrow(() ->
				LitematicaFootprintReader.extractFromPlacements(List.of(empty), empty, 0, 0, 0));
		assertEquals(LitematicaFootprintReader.ErrorCode.EMPTY_BOUNDS, emptyRes.errorCode());
		assertTrue(emptyRes.userMessage().contains("empty_schem"));

		assertThrows(LitematicaFootprintReader.IncompatibleLitematicaException.class, () ->
				LitematicaFootprintReader.extractFromPlacements(List.of(new BrokenPlacement()), null, 0, 0, 0));
	}
}
