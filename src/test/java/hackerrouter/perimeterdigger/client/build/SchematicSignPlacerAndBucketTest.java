package hackerrouter.perimeterdigger.client.build;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.navigation.BubbleColumnEscapeController;
import hackerrouter.perimeterdigger.client.ui.ConfigGuiModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchematicSignPlacerAndBucketTest {
	@Test
	void standingSignYawRoundTripsExactRotationForAll16Segments() {
		for (int rot = 0; rot < 16; rot++) {
			float yaw = SchematicSignPlacer.computeStandingSignYaw(rot);
			int placed = RotationSegment.convertToSegment(yaw + 180.0F);
			assertEquals(rot, placed, "Standing sign rotation " + rot + " mismatch for yaw=" + yaw);
		}
	}

	@Test
	void wallSignYawLooksDirectlyAtSupportingWallForAllHorizontalFacings() {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			float yaw = SchematicSignPlacer.computeWallSignYaw(facing);
			Direction looked = Direction.fromYRot(yaw);
			assertEquals(facing.getOpposite(), looked,
					"Wall sign facing " + facing + " should look at " + facing.getOpposite() + " (yaw=" + yaw + ")");
			assertEquals(facing, looked.getOpposite());
		}
	}

	@Test
	void mergesScatteredEmptyBucketsPreferringHotbarDestinationAndRefusesOverflowScoop() {
		// 4 unstacked single empty buckets in hotbar slots 0..3 with 0 empty slots -> mergeable pair found
		int[] scattered = new int[36];
		scattered[0] = 1;
		scattered[1] = 1;
		scattered[2] = 1;
		scattered[3] = 1;
		int[] pair = SchematicWaterPlacer.findMergeableEmptyBucketPair(scattered, 16);
		assertNotNull(pair);
		assertNotEquals(pair[0], pair[1]);
		assertTrue(pair[1] < 9, "Destination should stay on hotbar");

		// Backpack stack + hotbar stack -> merges backpack into hotbar
		int[] mixed = new int[36];
		mixed[2] = 3;
		mixed[15] = 2;
		int[] mixedPair = SchematicWaterPlacer.findMergeableEmptyBucketPair(mixed, 16);
		assertNotNull(mixedPair);
		assertEquals(15, mixedPair[0]);
		assertEquals(2, mixedPair[1]);

		// Full stack (16) + partial stack -> nothing to merge
		int[] fullOnly = new int[36];
		fullOnly[0] = 16;
		fullOnly[1] = 3;
		assertNull(SchematicWaterPlacer.findMergeableEmptyBucketPair(fullOnly, 16));

		// Safe slot selection with 0 empty slots: NEVER pick count > 1 stack (would drop water bucket!)
		int[] stackedOnly = new int[36];
		stackedOnly[0] = 4;
		assertEquals(-1, SchematicWaterPlacer.pickSafeEmptyBucketSlot(stackedOnly, 0));
		assertEquals(0, SchematicWaterPlacer.pickSafeEmptyBucketSlot(stackedOnly, 1));

		// With 0 empty slots and both a stacked slot (0) and a single slot (5), MUST pick single slot (5)!
		int[] stackedAndSingle = new int[36];
		stackedAndSingle[0] = 4;
		stackedAndSingle[5] = 1;
		assertEquals(5, SchematicWaterPlacer.pickSafeEmptyBucketSlot(stackedAndSingle, 0));

		// Refill safety decision
		assertFalse(SchematicWaterPlacer.canSafelyRefillMore(0, 4, 0, 0, 1, 0)); // 1 stacked slot, 0 empty -> unsafe
		assertTrue(SchematicWaterPlacer.canSafelyRefillMore(0, 4, 0, 0, 2, 0));  // 2 partial stacks -> merging frees slot!
		assertTrue(SchematicWaterPlacer.canSafelyRefillMore(0, 4, 0, 1, 1, 0));  // single bucket -> in-place safe
		assertTrue(SchematicWaterPlacer.canSafelyRefillMore(0, 4, 1, 0, 1, 0));  // 1 empty slot -> safe
		assertFalse(SchematicWaterPlacer.canSafelyRefillMore(4, 4, 5, 1, 1, 0)); // already at target 4
	}

	@Test
	void rejectsRimWallTopOrOuterEyesAndAcceptsInnerTankEyesForWaterPouring() {
		BlockPos wall = new BlockPos(10, 57, 20);
		AABB fullCube = new AABB(0, 0, 0, 1, 1, 1);

		// Target water cell is EAST of wall (11, 57, 20); inner face is EAST at X=11.0.
		// Standing directly on top of the rim wall (eye X=10.5) MUST be rejected!
		assertFalse(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(10.5, 59.62, 20.5), wall, fullCube, Direction.EAST, 0.20));
		// Standing on the outside of the rim wall (eye X=9.5) MUST be rejected!
		assertFalse(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(9.5, 58.62, 20.5), wall, fullCube, Direction.EAST, 0.20));
		// Barely grazing the inner wall edge (eye X=11.05 < 11.20) MUST be rejected!
		assertFalse(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(11.05, 58.62, 20.5), wall, fullCube, Direction.EAST, 0.20));
		// Standing/swimming cleanly inside the tank (eye X=12.5) MUST be accepted!
		assertTrue(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(12.5, 58.62, 20.5), wall, fullCube, Direction.EAST, 0.20));

		// South rim wall (10, 57, 20), target water is NORTH (10, 57, 19); inner face is NORTH at Z=20.0.
		assertFalse(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(10.5, 59.62, 20.5), wall, fullCube, Direction.NORTH, 0.20));
		assertTrue(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(10.5, 58.62, 18.5), wall, fullCube, Direction.NORTH, 0.20));

		// Floor block (10, 56, 20), target water is UP (10, 57, 20); top face is UP at Y=57.0.
		BlockPos floor = new BlockPos(10, 56, 20);
		assertTrue(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(10.5, 58.62, 20.5), floor, fullCube, Direction.UP, 0.20));
		assertFalse(SchematicWaterPlacer.isEyeOnTargetSideOfFace(new Vec3(10.5, 57.10, 20.5), floor, fullCube, Direction.UP, 0.20));

		// Baritone stand-cell reachability over deep water:
		// Air at Y=57 above water at Y=56 is midair (UNREACHABLE in Baritone)!
		assertFalse(SchematicWaterPlacer.isValidBaritoneStandCell(false, true, false));
		// Swimming at Y=56 (water at feet + water below at Y=55) is REACHABLE!
		assertTrue(SchematicWaterPlacer.isValidBaritoneStandCell(false, true, true));
		// Standing on solid floor (dry or shallow wading) is REACHABLE!
		assertTrue(SchematicWaterPlacer.isValidBaritoneStandCell(true, false, false));
		assertTrue(SchematicWaterPlacer.isValidBaritoneStandCell(true, false, true));
		// Midair over air is UNREACHABLE!
		assertFalse(SchematicWaterPlacer.isValidBaritoneStandCell(false, false, false));

		// Downward bubble column (BUBBLE_COLUMN[drag=true]) stand/swim reachability:
		// Floating at Y=56 above a Y=55 downward bubble column MUST be rejected (sucks entity down to Y=55)!
		assertFalse(SchematicWaterPlacer.isValidBaritoneStandCell(false, true, true, true, true));
		assertFalse(SchematicWaterPlacer.isValidBaritoneStandCell(false, true, true, true, false));
		assertFalse(SchematicWaterPlacer.isValidBaritoneStandCell(false, true, true, false, true));
		// Standing on a solid floor (e.g. Y=54 MAGMA_BLOCK) at Y=55 is physically standable as a floor fallback:
		assertTrue(SchematicWaterPlacer.isValidBaritoneStandCell(true, false, true, false, true));
	}

	@Test
	void bubbleColumnEscapeControllerPlansConfiguredTechniqueOrders() {
		assertEquals(BubbleColumnEscapeController.Mode.AUTO, BubbleColumnEscapeController.Mode.parse(null));
		assertEquals(BubbleColumnEscapeController.Mode.AUTO, BubbleColumnEscapeController.Mode.parse(""));
		assertEquals(BubbleColumnEscapeController.Mode.AUTO, BubbleColumnEscapeController.Mode.parse("unknown"));
		assertEquals(BubbleColumnEscapeController.Mode.SCAFFOLD_FIRST, BubbleColumnEscapeController.Mode.parse("scaffold_first"));
		assertEquals(BubbleColumnEscapeController.Mode.BUCKET_FIRST, BubbleColumnEscapeController.Mode.parse("BUCKET_FIRST"));
		assertEquals(BubbleColumnEscapeController.Mode.WALK_OR_FLY, BubbleColumnEscapeController.Mode.parse("walk_or_fly"));

		// AUTO mode with all capabilities: fly -> cap_magma -> bucket_scoop -> walk_out
		assertEquals(
				List.of("fly", "cap_magma", "bucket_scoop", "walk_out"),
				BubbleColumnEscapeController.planTechniqueOrder(BubbleColumnEscapeController.Mode.AUTO, true, true, true));
		// AUTO mode without flight: cap_magma -> bucket_scoop -> walk_out
		assertEquals(
				List.of("cap_magma", "bucket_scoop", "walk_out"),
				BubbleColumnEscapeController.planTechniqueOrder(BubbleColumnEscapeController.Mode.AUTO, false, true, true));
		// BUCKET_FIRST mode without flight: bucket_scoop -> cap_magma -> walk_out
		assertEquals(
				List.of("bucket_scoop", "cap_magma", "walk_out"),
				BubbleColumnEscapeController.planTechniqueOrder(BubbleColumnEscapeController.Mode.BUCKET_FIRST, false, true, true));
		// SCAFFOLD_FIRST mode without flight: cap_magma -> bucket_scoop -> walk_out
		assertEquals(
				List.of("cap_magma", "bucket_scoop", "walk_out"),
				BubbleColumnEscapeController.planTechniqueOrder(BubbleColumnEscapeController.Mode.SCAFFOLD_FIRST, false, true, true));
		// WALK_OR_FLY mode: never includes cap_magma or bucket_scoop
		assertEquals(
				List.of("fly", "walk_out"),
				BubbleColumnEscapeController.planTechniqueOrder(BubbleColumnEscapeController.Mode.WALK_OR_FLY, true, true, true));
		assertEquals(
				List.of("walk_out"),
				BubbleColumnEscapeController.planTechniqueOrder(BubbleColumnEscapeController.Mode.WALK_OR_FLY, false, true, true));

		// Config & GUI cycle round-trip
		PerimeterConfig cfg = new PerimeterConfig();
		assertEquals("auto", cfg.bubbleColumnEscapeMode);
		cfg.bubbleColumnEscapeMode = null;
		cfg.normalize();
		assertEquals("auto", cfg.bubbleColumnEscapeMode);
		cfg.bubbleColumnEscapeMode = "bucket_first";
		PerimeterConfig copy = ConfigGuiModel.copy(cfg);
		assertEquals("bucket_first", copy.bubbleColumnEscapeMode);
		assertEquals(
				List.of("auto", "scaffold_first", "bucket_first", "walk_or_fly"),
				ConfigGuiModel.BUBBLE_COLUMN_ESCAPE_MODES);
	}

	@Test
	void patchedLitematicaPrinterContainsAllFivePlacementFixes() throws Exception {
		Path patchedJar = Path.of("build/libs/litematica-printer-26.2-3.2.2-patched.jar");
		if (!Files.exists(patchedJar)) {
			patchedJar = Path.of("perimeter-digger/build/libs/litematica-printer-26.2-3.2.2-patched.jar");
		}
		if (!Files.exists(patchedJar)) {
			return;
		}
		try (ZipFile zip = new ZipFile(patchedJar.toFile())) {
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/ActionHandler.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/actions/PrepareAction.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/implementation/actions/InteractActionImpl.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/implementation/PrinterPlacementContext.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/implementation/BlockHelperImpl.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/Guides.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/PlacementGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/GeneralPlacementGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/SlabGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/BlockReplacementGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/TorchGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/RotatingBlockGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/LogGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/interaction/LogStrippingGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/GuesserGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/placement/PropertySpecificGuesserGuide.class"));
			assertNotNull(zip.getEntry("me/aleksilassila/litematica/printer/guides/interaction/CycleStateGuide.class"));
		}

		Path patchClasses = Path.of("printer-patch/classes");
		if (!Files.exists(patchClasses)) {
			patchClasses = Path.of("perimeter-digger/printer-patch/classes");
		}
		if (Files.exists(patchClasses)) {
			try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
					new java.net.URL[]{patchClasses.toUri().toURL(), patchedJar.toUri().toURL()},
					getClass().getClassLoader())) {
				Class<?> ctxCls = Class.forName("me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext", false, loader);
				Set<String> ctxMethods = Arrays.stream(ctxCls.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
				assertTrue(ctxMethods.contains("getNearestLookingDirections"), "PrinterPlacementContext must override getNearestLookingDirections");
				assertTrue(ctxMethods.contains("getRotation"), "PrinterPlacementContext must override getRotation");
				assertTrue(ctxMethods.contains("isSecondaryUseActive"), "PrinterPlacementContext must override isSecondaryUseActive");

				Class<?> torchCls = Class.forName("me.aleksilassila.litematica.printer.guides.placement.TorchGuide", false, loader);
				Set<String> torchMethods = Arrays.stream(torchCls.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
				assertTrue(torchMethods.contains("getRequiresSupport"));
				assertTrue(torchMethods.contains("getLookDirection"));
				assertTrue(torchMethods.contains("skipOtherGuides"));
				assertTrue(torchMethods.contains("statesEqual"));

				Class<?> rotCls = Class.forName("me.aleksilassila.litematica.printer.guides.placement.RotatingBlockGuide", false, loader);
				Set<String> rotMethods = Arrays.stream(rotCls.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
				assertTrue(rotMethods.contains("getRequiresSupport"));
				assertTrue(rotMethods.contains("getLookDirection"));
				assertTrue(rotMethods.contains("getRequiredItemAsBlock"));
				assertTrue(rotMethods.contains("getRequiresExplicitShift"));
			}
		}
	}
}
