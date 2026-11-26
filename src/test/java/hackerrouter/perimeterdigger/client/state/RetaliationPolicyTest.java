package hackerrouter.perimeterdigger.client.state;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RetaliationPolicyTest {
 @Test void pursuitHasNoDistanceLimitButEscapesUnreachableTargets() {
  // No distance or elapsed-time cap: the fight continues while the bot keeps closing in.
  assertTrue(RetaliationPolicy.pursue(0));
  assertTrue(RetaliationPolicy.pursue(599));
  // Only a target that never gets closer (no progress for 30s) is abandoned.
  assertFalse(RetaliationPolicy.pursue(RetaliationPolicy.UNREACHABLE_TICKS));
 }
 @Test void pendingContinuationCancellationWinsAndExecutionIsOneShot() {
  var pending = new PendingContinuation();
  int[] calls = {0};
  pending.schedule(() -> calls[0]++);
  assertTrue(pending.pending());
  pending.cancel(); pending.run();
  assertEquals(0,calls[0]);
  pending.schedule(() -> calls[0]++);
  pending.run(); pending.run();
  assertEquals(1,calls[0]);
  assertFalse(pending.pending());
 }

 @Test void onlyRealLocalHostileDamageAcquires() {
  assertTrue(RetaliationPolicy.acquire(true,true,true));
  assertFalse(RetaliationPolicy.acquire(true,true,false)); // environment, player, passive mob
  assertFalse(RetaliationPolicy.acquire(true,false,true)); // somebody else's damage
  assertFalse(RetaliationPolicy.acquire(false,true,true)); // idle/paused
 }
 @Test void eachNativeAttackGateIsRequired() {
  assertTrue(RetaliationPolicy.attack(true,true,true,true,1));
  assertFalse(RetaliationPolicy.attack(false,true,true,true,1));
  assertFalse(RetaliationPolicy.attack(true,false,true,true,1));
  assertFalse(RetaliationPolicy.attack(true,true,false,true,1)); // approach first, never attack beyond native range
  assertFalse(RetaliationPolicy.attack(true,true,true,false,1));
  assertFalse(RetaliationPolicy.attack(true,true,true,true,.99F));
 }
 @Test void cancellationAndRespawnRequireActiveAutomation() {
  for (var state : new AutomationState[]{AutomationState.IDLE,AutomationState.PAUSED,AutomationState.COMPLETE,AutomationState.ERROR,AutomationState.VALIDATING})
   assertFalse(RetaliationPolicy.active(state),state.name());
  for (var state : new AutomationState[]{AutomationState.MINING,AutomationState.EATING,AutomationState.NAVIGATING_TO_UNLOAD,AutomationState.REPAIRING})
   assertTrue(RetaliationPolicy.active(state),state.name());
 }
 @Test void abortsUnreachableCombatOnPathFailureOrNoProgressUnlessInRange() {
  assertFalse(RetaliationPolicy.shouldAbortCombat(50, 0, 50, false));
  assertTrue(RetaliationPolicy.shouldAbortCombat(40, RetaliationPolicy.PATH_FAIL_ABORT_COUNT, 40, false));
  assertTrue(RetaliationPolicy.shouldAbortCombat(RetaliationPolicy.NO_PROGRESS_ABORT_TICKS, 0, RetaliationPolicy.NO_PROGRESS_ABORT_TICKS, false));
  assertFalse(RetaliationPolicy.shouldAbortCombat(RetaliationPolicy.NO_PROGRESS_ABORT_TICKS, 0, 20, false));
  assertFalse(RetaliationPolicy.shouldAbortCombat(RetaliationPolicy.NO_PROGRESS_ABORT_TICKS, 5, RetaliationPolicy.NO_PROGRESS_ABORT_TICKS, true));
  assertTrue(RetaliationPolicy.shouldAbortCombat(RetaliationPolicy.UNREACHABLE_TICKS, 0, 0, true));
 }
 @Test void switchesToCloserAttackerOnlyWhenCurrentTargetIsOutOfRange() {
  assertTrue(RetaliationPolicy.shouldSwitchTarget(false, 64.0, 9.0));
  assertFalse(RetaliationPolicy.shouldSwitchTarget(false, 16.0, 15.0));
  assertFalse(RetaliationPolicy.shouldSwitchTarget(true, 9.0, 4.0));
 }
}
