package hackerrouter.perimeterdigger.client.state;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MiningPausePolicyTest {
 @Test void onlyPathingFailuresAreRetriedAutomatically() {
  assertTrue(MiningPausePolicy.recoverable(true));
  assertFalse(MiningPausePolicy.recoverable(false)); // manual pause, missing sealing blocks, block limit
 }
 @Test void retriesAreBounded() {
  assertFalse(MiningPausePolicy.exhausted(0,10));
  assertFalse(MiningPausePolicy.exhausted(9,10));
  assertTrue(MiningPausePolicy.exhausted(10,10));
 }
 @Test void progressResetsRetriesAndStallKeepsCounting() {
  assertEquals(0,MiningPausePolicy.resetOnProgress(7,1));
  assertEquals(7,MiningPausePolicy.resetOnProgress(7,0));
 }
 @Test void recoveryMovesAtLeastSixBlocks() {
  assertEquals(6,MiningPausePolicy.recoveryDistance(3));
  assertEquals(9,MiningPausePolicy.recoveryDistance(9));
 }
 @Test void blockLimitDoesNotCollapseToOneWhenUnloadIsUnconfigured() {
  assertEquals(Long.MAX_VALUE, MiningPausePolicy.miningBlockLimit(true, false, 1, 1, 64));
  assertEquals(Long.MAX_VALUE, MiningPausePolicy.miningBlockLimit(true, false, 0, 1, 64));
  assertEquals(128L, MiningPausePolicy.miningBlockLimit(true, false, 3, 1, 64));
  assertEquals(1L, MiningPausePolicy.miningBlockLimit(true, true, 1, 1, 64));
  assertEquals(Long.MAX_VALUE, MiningPausePolicy.miningBlockLimit(false, true, 3, 1, 64));
 }
}
