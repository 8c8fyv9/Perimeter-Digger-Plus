package hackerrouter.perimeterdigger.client.state;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MiningEscapePolicyTest {
 @Test void progressTimeoutUsesMonitorScans() {
  assertFalse(MiningEscapePolicy.stalled(0));
  assertFalse(MiningEscapePolicy.stalled(39)); // 20s minus one scan
  assertTrue(MiningEscapePolicy.stalled(40));
 }
 @Test void escapeDistanceEscalatesAndRebuildsFirst() {
  assertEquals(0,MiningEscapePolicy.escapeDistance(1));
  for (int attempt = 2; attempt <= MiningEscapePolicy.ESCAPE_LIMIT; attempt++) {
   assertTrue(MiningEscapePolicy.escapeDistance(attempt) > MiningEscapePolicy.escapeDistance(attempt - 1));
  }
 }
 @Test void directionRotatesSoTheBotDoesNotWaltzTheSameWay() {
  assertEquals(0,MiningEscapePolicy.directionIndex(1));
  assertEquals(1,MiningEscapePolicy.directionIndex(2));
  assertEquals(2,MiningEscapePolicy.directionIndex(3));
  assertEquals(3,MiningEscapePolicy.directionIndex(4));
  assertEquals(0,MiningEscapePolicy.directionIndex(5));
 }
 @Test void churnOverTheSameBlocksIsDetectedFasterThanIdling() {
  assertTrue(MiningEscapePolicy.looping(16)); // 8s of mining without a new position
  assertFalse(MiningEscapePolicy.looping(15));
  assertFalse(MiningEscapePolicy.stalled(16)); // plain idling still waits 20s
  assertTrue(MiningEscapePolicy.shouldEscape(16,true));
  assertFalse(MiningEscapePolicy.shouldEscape(16,false));
  assertTrue(MiningEscapePolicy.shouldEscape(40,false));
 }
 @Test void givesUpOnlyAtTheLimit() {
  assertFalse(MiningEscapePolicy.giveUp(1));
  assertFalse(MiningEscapePolicy.giveUp(MiningEscapePolicy.ESCAPE_LIMIT - 1));
  assertTrue(MiningEscapePolicy.giveUp(MiningEscapePolicy.ESCAPE_LIMIT));
 }
 @Test void zeroMinedBlocksAfterCycleRebuildDoesNotCountAsProgress() {
  long baseline = MiningEscapePolicy.initialBaseline();
  assertFalse(MiningEscapePolicy.hasProgressed(0L, baseline));
  assertTrue(MiningEscapePolicy.hasProgressed(1L, baseline));
 }
 @Test void recoveryWalkTimeoutHasSensibleLowerBound() {
  assertEquals(200, MiningEscapePolicy.recoveryWalkTimeoutTicks(10.0));
  assertEquals(100, MiningEscapePolicy.recoveryWalkTimeoutTicks(0.0));
  assertEquals(100, MiningEscapePolicy.recoveryWalkTimeoutTicks(-5.0));
 }
 @Test void activeTransitGrantsBoundedGraceOnlyForGenuineProgress() {
  assertTrue(MiningEscapePolicy.isActiveTransit(4.0, true, false, false, 0));
  assertFalse(MiningEscapePolicy.isActiveTransit(1.0, true, false, false, 0), "slow shuffling is not transit");
  assertFalse(MiningEscapePolicy.isActiveTransit(4.0, true, true, false, 0), "confined oscillation is not transit");
  assertFalse(MiningEscapePolicy.isActiveTransit(4.0, true, false, true, 0), "place/dig churn is not transit");
  assertFalse(MiningEscapePolicy.isActiveTransit(4.0, false, false, false, 0), "not pathing is not transit");
  assertFalse(MiningEscapePolicy.isActiveTransit(4.0, true, false, false, MiningEscapePolicy.MAX_TRANSIT_CREDITS), "transit grace is bounded");
 }
 @Test void idleScansAfterStallRecoveryCapsWithoutResettingLowValues() {
  assertEquals(6, MiningEscapePolicy.idleScansAfterStallRecovery(6));
  assertEquals(MiningEscapePolicy.POST_STALL_RECOVERY_IDLE_CAP, MiningEscapePolicy.idleScansAfterStallRecovery(35));
 }
}
