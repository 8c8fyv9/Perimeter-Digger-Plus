import test from "node:test";
import assert from "node:assert/strict";
import { PerimeterAgent } from "./client.mjs";
import { readFileSync } from "node:fs";

// Source-contract regressions: no Minecraft runtime or live endpoint is used.
const controller = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/state/AutomationController.java", import.meta.url), "utf8");
const method = (name, next) => controller.slice(controller.indexOf(name), controller.indexOf(next, controller.indexOf(name)));
test("interruption snapshots budget before destructive cancellation and rebuilds once", () => {
  const suspend = method("private void suspendForRetaliation()", "private void restoreAfterRetaliation()");
  assert.ok(suspend.indexOf("status.blockLimit() - status.minedBlocks()") < suspend.indexOf("forceCancel()"));
  // Combat must not deadlock against an open chest/furnace menu it cannot click through.
  assert.ok(suspend.indexOf("closeContainer()") < suspend.indexOf("forceCancel()"));
  assert.ok(suspend.includes("interruptedMining == null && state == AutomationState.MINING && area != null"));
  assert.ok(suspend.includes("status.blockLimit() == Long.MAX_VALUE ? Long.MAX_VALUE"));
  assert.ok(suspend.includes("Math.max(0L, status.blockLimit() - status.minedBlocks())"));
  assert.ok(suspend.includes("interruptedMining = () -> miningProcess.mineArea(interruptedArea, options)"));
  const restore = method("private void restoreAfterRetaliation()", "private void stopProcesses()");
  assert.ok(restore.indexOf("interruptedMining = null") < restore.indexOf("restoreMining.run()"));
  assert.ok(restore.includes("state != AutomationState.MINING || miningStallRecovery"));
  assert.ok(restore.includes("navigation.resumeSuspended()"));
});
test("validated repeated start discards stale contexts before new task initialization", () => {
  const start = method("public List<String> start(", "private void loadMiningConfiguration(");
  assert.ok(start.indexOf("return missing") < start.indexOf("stopProcesses()"));
  assert.ok(start.indexOf("stopProcesses()") < start.indexOf("originalTask ="));
  assert.ok(start.indexOf("stopProcesses()") < start.indexOf("navigation.bind(baritone)"));
  const stop = method("private void stopProcesses()", "private void emergencyCleanup()");
  for (const cleanup of ["interruptedMining = null", "continuation.cancel()", "retaliation.cancel()", "pausedTask = null", "deathRespawnPending = false", "navigation.stop()"]) assert.ok(stop.includes(cleanup), cleanup);
});

test("stall recovery short-circuits tick and preserves escape progression on zero-progress rebuild", () => {
  const tick = method("public void tick()", "public void stop()");
  assert.ok(tick.includes("if (miningStallRecovery) {\n\t\t\ttickMiningStallRecovery();\n\t\t\treturn;\n\t\t}")
    || tick.includes("if (miningStallRecovery) {\r\n\t\t\ttickMiningStallRecovery();\r\n\t\t\treturn;\r\n\t\t}"));
  const resumeCycle = method("private void resumeMiningCycle(boolean resetProgressTracking)", "private void beginDropCollection(");
  assert.ok(resumeCycle.includes("MiningEscapePolicy.initialBaseline()"));
  assert.ok(resumeCycle.includes("if (resetProgressTracking)"));
  const stallRecovery = method("private void tickMiningStallRecovery()", "private BlockPos randomNearbySafeTarget(");
  assert.ok(stallRecovery.includes("recoveryWalkTimeoutTicks"));
  assert.ok(stallRecovery.includes("resumeMiningCycle(false)"));
});

test("baritone fork allows walking on mud and scans area mining top-down with unactionable fallback", () => {
  const movementHelper = readFileSync(new URL("../baritone/src/main/java/baritone/pathing/movement/MovementHelper.java", import.meta.url), "utf8");
  const movementTraverse = readFileSync(new URL("../baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java", import.meta.url), "utf8");
  const builder = readFileSync(new URL("../baritone/src/main/java/baritone/process/BuilderProcess.java", import.meta.url), "utf8");
  assert.ok(movementHelper.includes("block == Blocks.SOUL_SAND || block == Blocks.MUD"));
  assert.ok(movementHelper.includes("state.getBlock() == Blocks.MUD"));
  assert.ok(movementTraverse.includes("srcDownBlock == Blocks.SOUL_SAND || srcDownBlock == Blocks.MUD"));
  assert.ok(movementTraverse.includes("standingOn.equals(Blocks.SOUL_SAND) || standingOn.equals(Blocks.MUD)"));
  assert.ok(builder.includes("schematic.heightY() - 1 : 0"));
  assert.ok(builder.includes("isImmediatelyActionableAreaTarget"));
});

test("missing unloading points do not crash mining and auto-restart surfaces the cause", () => {
  assert.ok(controller.includes("private boolean hasConfiguredUnload() {\n\t\treturn unloadEnabled && !unloadingPoints.isEmpty();\n\t}")
    || controller.includes("private boolean hasConfiguredUnload() {\r\n\t\treturn unloadEnabled && !unloadingPoints.isEmpty();\r\n\t}"));
  const reqUnload = method("private void requestUnload()", "private void beginUnloadApproach()");
  assert.ok(reqUnload.includes("if (!unload.debugOnly && !hasConfiguredUnload())"));
  const autoRestart = method("private boolean tickAutoRestart()", "private void suspendForRetaliation()");
  assert.ok(autoRestart.includes("String reason = detail == null"));
  assert.ok(autoRestart.includes("自动重启已达上限，停止原因："));
});

test("selection overlay uses Fabric COLLECT_SUBMITS with native Gizmos and /fly flight is wired", () => {
  const clientEntry = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/PerimeterDiggerClient.java", import.meta.url), "utf8");
  const areaRenderer = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/ui/AreaSelectionRenderer.java", import.meta.url), "utf8");
  const navService = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/navigation/NavigationService.java", import.meta.url), "utf8");
  const builder = readFileSync(new URL("../baritone/src/main/java/baritone/process/BuilderProcess.java", import.meta.url), "utf8");
  assert.ok(clientEntry.includes("LevelRenderEvents.COLLECT_SUBMITS.register"));
  assert.ok(areaRenderer.includes("DrawableGizmoPrimitives"));
  assert.ok(areaRenderer.includes("depthTested.addQuad(v0, v3, v2, v1, FILL_ARGB)"));
  assert.ok(navService.includes("CreativeFlightNavigator"));
  assert.ok(controller.includes("CreativeFlightMiner"));
  assert.ok(controller.includes("BaritoneAPI.getSettings().itemSaver.value = durabilityProtectionEnabled"));
  assert.ok(controller.includes("effectiveToolDurabilityThreshold()"));
  assert.ok(controller.includes("syncProtectedBlocksSetting()"));
  assert.ok(controller.includes("restoreProtectedBlocksSetting()"));
  assert.ok(builder.includes("ctx.player().getAbilities().flying"));
  assert.ok(builder.includes("isSealedByProtectedBlocks"));
  assert.ok(builder.includes("deferBlockedAreaTargets"));
  assert.ok(builder.includes("AreaMiningStrategy.CHUNK_TOP_DOWN"));
  assert.ok(builder.includes("AreaMiningStrategy.LOCAL_TOP_DOWN"));
  assert.ok(builder.includes("areaTargetBlocksOnly"));
  assert.ok(controller.includes("updateMiningStrategy"));
  assert.ok(controller.includes("updateMiningBlacklist"));
  assert.ok(controller.includes("reverseBacktrackTarget("));
  assert.ok(controller.includes("isOscillatingConfined("));
  assert.ok(controller.includes("EATING_TIMEOUT_TICKS"));
  assert.ok(controller.includes("UNLOAD_POSITIONING_TIMEOUT_TICKS"));
  assert.ok(controller.includes("eatingPausedCreativeMiner"));
  assert.ok(controller.includes("public void syncConfig(PerimeterConfig config)"));
  assert.ok(controller.includes("usesElytraNavigation()"));
  assert.ok(controller.includes("mining_blacklist"));
  assert.ok(controller.includes("if (state != AutomationState.ERROR) return false;"));
  assert.ok(clientEntry.includes("mc.gui.screen() != null"));
  assert.ok(areaRenderer.includes("config.detectedArea.dimension"));
  assert.ok(areaRenderer.includes("scanlinesHash"));
  assert.ok(navService.includes("flyToBlock"));
  assert.ok(navService.includes("creativeFlight.updateTarget"));
  const flightMiner = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/flight/CreativeFlightMiner.java", import.meta.url), "utf8");
  assert.ok(flightMiner.includes("public void resume(ConfiguredColumnarArea area, AreaMiningOptions options)"));
  assert.ok(flightMiner.includes("isProtectedLowDurabilityTool"));
  assert.ok(flightMiner.includes("touchesSideOrTopBoundary"));
  assert.ok(flightMiner.includes("isLava("));
  assert.ok(flightMiner.includes("clearCooldownsInBounds"));
  assert.ok(flightMiner.includes("BackpackToolManager.ensureBestToolOnHotbar"));
  assert.ok(controller.includes("autoSwapBackpackTools"));
  assert.ok(controller.includes("autoReplaceLowDurabilityTools"));
  assert.ok(controller.includes("findBestHealthyReplacement"));
  const movementHelper = readFileSync(new URL("../baritone/src/main/java/baritone/pathing/movement/MovementHelper.java", import.meta.url), "utf8");
  assert.ok(movementHelper.includes("BackpackToolManager.ensureBestToolOnHotbar"));
  const astar = readFileSync(new URL("../baritone/src/main/java/baritone/pathing/calc/AStarPathFinder.java", import.meta.url), "utf8");
  assert.ok(astar.includes("StuckCellPenalty.snapshot()"));
  assert.ok(controller.includes("StuckCellPenalty.penalizeNeighborhood"));
  assert.ok(controller.includes("deferCurrentTargets("));
  assert.ok(controller.includes("beginManualUnstuck("));
  assert.ok(controller.includes("isNearRecentStallSpot("));
  assert.ok(controller.includes("planBackpackFoodSwap"));
  assert.ok(controller.includes("restoreAfterEating"));
  assert.ok(builder.includes("y < BuilderProcess.this.areaMiningArea.minY()"));
  assert.ok(builder.includes("public boolean isBlockBreakForbidden(BlockPos pos)"));
  const breakHelper = readFileSync(new URL("../baritone/src/main/java/baritone/utils/BlockBreakHelper.java", import.meta.url), "utf8");
  assert.ok(breakHelper.includes("breakForbidden.test(hitPos)"));
  const inputHandler = readFileSync(new URL("../baritone/src/main/java/baritone/utils/InputOverrideHandler.java", import.meta.url), "utf8");
  assert.ok(inputHandler.includes("isBlockBreakForbidden(pos)"));
  const movementTraverse = readFileSync(new URL("../baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java", import.meta.url), "utf8");
  assert.ok(movementTraverse.includes("if (MovementHelper.isBlockNormalCube(pb0) && (MovementHelper.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))"));
  assert.ok(builder.includes("areaObservedSolid"));
  assert.ok(controller.includes("miningStallMinedBaseline"));
  assert.ok(controller.includes("miningStatus.state() == AreaMiningStatus.State.CANCELLED"));
  const retaliation = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/state/Retaliation.java", import.meta.url), "utf8");
  assert.ok(retaliation.includes("ensureWeaponEquipped("));
  assert.ok(retaliation.includes("ItemTags.AXES"));
  assert.ok(retaliation.includes("shouldAbortCombat("));
  assert.ok(retaliation.includes("shouldSwitchTarget("));
  const guiScreen = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/ui/PerimeterConfigScreen.java", import.meta.url), "utf8");
  assert.ok(guiScreen.includes("readLitematicaFootprint()"));
  const litematicaReader = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/detect/LitematicaFootprintReader.java", import.meta.url), "utf8");
  assert.ok(litematicaReader.includes("getEclosingBox"));
  assert.ok(litematicaReader.includes("NOT_INSTALLED"));
  assert.ok(litematicaReader.includes("INCOMPATIBLE"));
  assert.ok(builder.includes("recordPathScaffold("));
  assert.ok(builder.includes("scaffoldTracker.releaseAllActive()"));
  assert.ok(builder.includes("scaffoldTracker.consumeCycleScaffold(posKey)"));
  const placeHelper = readFileSync(new URL("../baritone/src/main/java/baritone/utils/BlockPlaceHelper.java", import.meta.url), "utf8");
  assert.ok(placeHelper.includes("onBlockPlaced.accept(placedPos)"));
  assert.ok(inputHandler.includes("recordPathScaffold(pos)"));
  assert.ok(builder.includes("flowingLiquidSealGoal("));
  assert.ok(builder.includes("hasAnySolidNeighbor("));
  assert.ok(flightMiner.includes("findBlockingAreaBlock("));
  const flightNav = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/flight/CreativeFlightNavigator.java", import.meta.url), "utf8");
  assert.ok(flightNav.includes("startDetour("));
  assert.ok(flightNav.includes("isBlockedOrStalled()"));
  assert.ok(controller.includes("syncScaffoldBlocksSetting()"));
  assert.ok(guiScreen.includes("scaffoldBox"));
  assert.ok(builder.includes("PathingCommandType.REVALIDATE_GOAL_AND_PATH"));
  assert.ok(builder.includes("escalatingDeferralMillis("));
  assert.ok(controller.includes("MiningEscapePolicy.isActiveTransit("));
  assert.ok(controller.includes("startSchematicBuild("));
  assert.ok(controller.includes("AutomationState.BUILDING"));
  assert.ok(guiScreen.includes("workModeLabel()"));
  assert.ok(guiScreen.includes("wrongBlockPolicyLabel()"));
  const printerBridge = readFileSync(new URL("../src/client/java/hackerrouter/perimeterdigger/client/build/LitematicaPrinterBridge.java", import.meta.url), "utf8");
  assert.ok(printerBridge.includes("getRenderLayerRange"));
  assert.ok(printerBridge.includes("PRINT_MODE"));
  assert.ok(printerBridge.includes("restoreLayerRange"));
});

test("loopback only", () => {
  assert.throws(() => new PerimeterAgent("http://example.com", "t"));
  assert.throws(() => new PerimeterAgent("https://127.0.0.1", "t"));
});
test("all semantic actions, auth, task body and no retries", async () => {
  const original = globalThis.fetch; const calls = [];
  globalThis.fetch = async (url, options) => { calls.push({url, ...options}); return {ok:true,json:async()=>({ok:true})}; };
  try {
    const api = new PerimeterAgent("http://127.0.0.1:18769", "secret");
    const task = {taskId:"t", from:[1,2,3], to:[4,5,6]};
    await api.status(); await api.plan(task); await api.start(task); await api.pause("t"); await api.resume("t"); await api.stop("t");
    assert.deepEqual(calls.map(c=>new URL(c.url).pathname), ["/v1/status","/v1/tasks/plan","/v1/tasks","/v1/tasks/pause","/v1/tasks/resume","/v1/tasks/stop"]);
    assert.equal(calls[0].headers.Authorization,"Bearer secret"); assert.deepEqual(JSON.parse(calls[2].body),task);
    await api.commands(); await api.command("config show");
    assert.equal(calls[6].method,"GET"); assert.equal(new URL(calls[6].url).pathname,"/v1/commands");
    assert.deepEqual(JSON.parse(calls[7].body),{command:"config show"});
    let failures=0; globalThis.fetch=async()=>{failures++;throw new Error("timeout");};
    await assert.rejects(api.start(task), /timeout/); assert.equal(failures,1);
    globalThis.fetch=async()=>({ok:false,status:409,json:async()=>({error:"BUSY"})});
    await assert.rejects(api.start(task), e=>e.status===409 && e.message==="BUSY");
  } finally { globalThis.fetch=original; }
});
