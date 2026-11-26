# Perimeter Digger Plus

**English** | [简体中文](README.zh-CN.md)

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52A535?logo=minecraft&logoColor=white)](https://minecraft.net)
[![Mod Loader](https://img.shields.io/badge/Loader-Fabric%200.20.1%2B-DBD0B4)](https://fabricmc.net)
[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00)](https://adoptium.net)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

An enhanced fork of [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) for Minecraft — an all-in-one client-side **automated area-mining & layer-by-layer schematic-building mod** bundling its customized Baritone fork via Jar-in-Jar. Includes a full in-game `F9` configuration GUI, translucent 3D selection overlay, one-click Litematica footprint import, `/fly` 3D aerial mining & printing, optimized water placement, configurable downward bubble-column escape, smart backpack tool/food/shulker resupply, bottom-layer protection, and hardened anti-stall & combat recovery.

> **中文简介：** 本仓库为基于 [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) 增强开发的 **空置域自动挖掘与逐层投影建造增强版（Perimeter Digger Plus）**，单包内置自定义 Baritone（无需额外下载）。完整中文文档请阅读 **[简体中文 README](README.zh-CN.md)**。
>
> **Current Support:** Minecraft `26.2` · Fabric Loader `0.20.1+` · Fabric API · Java `25+`  
> **Mod ID:** `perimeter-digger-plus` (uses its own config directory `config/perimeter-digger-plus/` and declares `breaks: perimeter-digger` to prevent conflicts with upstream).

---

## Table of Contents

- [Highlights](#highlights)
- [Installation & Quick Start](#installation--quick-start)
- [In-Game Configuration GUI (`F9`)](#in-game-configuration-gui-f9)
- [Command Reference](#command-reference)
- [Configuration & Compatibility Notes](#configuration--compatibility-notes)
- [Optional Local Agent HTTP API](#optional-local-agent-http-api)
- [Building & Testing from Source](#building--testing-from-source)
- [Repository Layout](#repository-layout)
- [Roadmap](#roadmap)
- [License & Attribution](#license--attribution)

---

## Highlights

### Distribution & User Interface
- **Single All-in-One JAR (Jar-in-Jar)**: Bundles the customized [`baritone/`](baritone/) fork inside the mod JAR. Drop one file into `mods/` — no separate Baritone download needed.
- **Full 5-Tab In-Game Configuration Screen (`F9`)**:
  - **Tap `F9`**: Start / pause / resume automation (`Esc` stops automation only when no GUI screen is open).
  - **Hold `F9` (~400 ms)**: Open the full settings UI without touching JSON files or commands.
- **One-Click Litematica Footprint Import**:
  - Click **Read Litematica (`读取投影`)** in the `F9` Area tab or run `/perimeterdig plan litematica`.
  - Zero hard dependency on Litematica (optional runtime reflection): automatically selects the active placement (or nearest enabled placement), merges rotated/mirrored sub-regions into exact world bounds, clamps to world height, and reports clear messages if Litematica is not installed, no placement exists, or data is incompatible.
- **Translucent 3D Selection Overlay**:
  - Native Fabric `LevelRenderEvents.COLLECT_SUBMITS` + `DrawableGizmoPrimitives` rendering for both rectangles and scanned polygons, isolated per dimension and updated live when bounds change.

### Layer-by-Layer Automated Schematic Building (`模式: 投影打印`)
- **Automated Schematic Printing** (zero hard dependency on Litematica / `litematica-printer`):
  - Switch between **Auto Mining** and **Schematic Printing** directly inside the `F9` screen.
  - Steps Litematica's single-layer render range from `minY` to `maxY`, tallies per-layer materials & buildable-layer sufficiency (including chests, barrels, placed shulker boxes, and nested/carried shulkers via a 3-view materials screen), and supports configurable missing-material & wrong-block policies (`skip` vs. `break_and_place`, plus optional clearing of extra blocks).
  - Automatically navigates via `/fly` or Baritone ground walking (with `lastAnchorPos` locality weighting to prevent corridor ping-pong) and restores original `LayerRange` and `PRINT_MODE` on pause/stop/completion.
  - **Multi-Point Chest & Shulker Resupply (`SchematicResourceController`)**: Automatically restocks missing blocks, water buckets, food, healthy tools, and scaffold blocks from configured resource chests/shulker boxes or safely places/unloads/re-mines carried shulker boxes.
  - **Exact Sign Placement (`SchematicSignPlacer`)**: Directly places standing signs (16 rotations), wall signs (4 facings), and hanging signs with synchronized yaw/pitch packets and automatic sign-edit screen dismissal.
- **Optimized Water Placement (`WaterSourceOptimizer` + `SchematicWaterPlacer`)**:
  - Handles waterlogged blocks (`waterlogged=true`) and water source pools after each layer's solid containment blocks finish.
  - Uses Minecraft's native 2-neighbor source conversion to hit the exact $\lceil (W + L) / 2 \rceil$ perimeter lower bound on rectangular pools (e.g., 10 buckets for 10×10, 30 buckets for 20×40) and lookahead closure greedy + pruning on irregular pools.
  - Automatically merges scattered empty buckets and refills from nearby safe self-regenerating water sources in Survival (2 water buckets suffice for arbitrarily large pools), with inner-wall eye-side clearance and 5-ray quantized aim safety checks to prevent outer spills.
- **Optional `litematica-printer` 26.2-3.2.2 Compatibility Patch (`printer-patch/`)**:
  - Fixes 5 upstream placement bugs when `printInAir=true` or when building complex redstone/farm schematics: top/bottom/double slabs (`SlabGuide`), redstone/wall torches (`TorchGuide` registered for `BaseTorchBlock`), fences/gates/walls (`GuesserGuide` + atomic `ActionHandler`), wall/standing/hanging signs (`RotatingBlockGuide`), and log stripping axis preservation (`LogGuide` / `LogStrippingGuide`).

### Mining Engines & Strategies
- **Three Switchable Top-Down Clearing Strategies** (shared by both ground Baritone & `/fly` engines):
  - `global_top_down`: Strictly clears the highest remaining Y layer across the entire selection first.
  - `local_top_down`: Clears local peaks/towers layer-by-layer before advancing to the next region.
  - `chunk_top_down`: Clears the nearest chunk within the selection top-down before moving to the next chunk.
- **`/fly` 3D Aerial Navigation & Mining**:
  - Automatically engages when enabled and the player has `mayfly` permission, avoiding terrain and liquid pathing issues.
  - Smart open-direction obstacle detouring under overhangs/canopies, and automatic in-place clearing of blocking in-area blocks if flight stalls against walls or foliage.
- **Block Whitelist / Blacklist + Sealed-Pocket Detection**:
  - Optional **Mining Whitelist** (protected blocks to skip) and **Mining Blacklist** (mine only explicitly listed blocks), synced into Baritone pathing costs and paired with a bounded 3D reverse-BFS `SealedPocketDetector` so enclosed/unreachable pockets do not deadlock the job.

### Downward Bubble-Column Pathfinding & Escape + Safety Hardening
- **Downward Bubble-Column (`BUBBLE_COLUMN[drag=true]`) Pathfinding & Configurable Escape (`BubbleColumnEscapeController`)**:
  - Baritone pathfinding explicitly accounts for downward bubble-column drag physics (`canWalkOnPosition`, `MovementPillar`, `MovementAscend`, `MovementTraverse`, `MovementDescend`): refuses to treat floating cells above downward columns as swim-walkable, forbids impossible in-column swimming/jumping, and applies high hazard penalties while allowing passive sneak-walking on magma floors.
  - Selectable escape strategy (`bubbleColumnEscapeMode` in `F9` GUI or `/perimeterdig config set bubble_escape_mode <mode>`):
    - `auto` *(default)*: `/fly` lift -> zero-collision / bottom-slab magma capping (converts column to normal water in 5 ticks without jumping) -> safe empty-bucket scoop + pillar -> horizontal sneak walk.
    - `scaffold_first`: prioritizes capping magma floor with zero-collision blocks or `<=0.5` step-up slabs/trapdoors.
    - `bucket_first`: prioritizes scooping the bubble column with an empty bucket to break drag and pillar out.
    - `walk_or_fly`: never places temporary blocks or scoops water; exits strictly via `/fly` or horizontal magma-sneak walking.
- **Smart Backpack Tool & Food Management**:
  - Automatic best-tool swapping from backpack to hotbar (`autoSwapBackpackTool`), automatic replacement of low-durability tools with healthier backpack tools (`autoReplaceLowDurabilityTool`), and a standalone `durabilityProtection` toggle.
  - Automatic backpack-to-hotbar food swapping when hungry or below health threshold (`autoSwapBackpackFood`) without displacing tools or sealing blocks, with mid-meal refill and slot restoration.
- **Independent Scaffold Blocks & Sealing Blocks**:
  - Configure **Sealing Blocks** (for water/lava sealing) and **Scaffold Blocks** (synced to Baritone `acceptableThrowawayItems` for pillaring/bridging) independently in the `F9` Blocks & Items tab; leave Scaffold Blocks empty to use Baritone defaults + sealing blocks.
- **Bottom-Layer `minY - 1` & Scaffold Self-Mining Protection**:
  - Enforced in both A\* path planning (`breakCostMultiplierAt`) and execution (`BlockBreakHelper`) so bottom-layer clearing never digs below `diggingMinY`.
  - Tracks throwaway scaffold blocks (`ScaffoldTracker`), protects them while climbing/bridging, and cleans them up once no other targets remain.
- **Anti-Stall, Anti-Ping-Pong & Retaliation Hardening**:
  - Uses `REVALIDATE_GOAL_AND_PATH` for area mining, escalating target deferrals (`45s -> 180s -> skip`) + `StuckCellPenalty`, bounded cross-area transit grace, and hardened liquid handling.
  - Mob retaliation automatically closes open container/inventory/GUI screens (`closeContainer()` + `setScreen(null)`), re-equips swords/axes, aborts unreachable attackers quickly, and finishes critical low-health bites before engaging.

---

## Installation & Quick Start

### Prerequisites
- Minecraft **26.2**
- **Fabric Loader 0.20.1+** and **Fabric API**
- **Java 25+**
- *(Optional)* **Litematica** (and `litematica-printer` for automated schematic printing — **we recommend using our maintained patched version** `litematica-printer-26.2-3.2.2-patched.jar` from Releases or [`printer-patch/`](printer-patch/), which fixes slabs, torches, fences/gates/walls, signs, and log stripping)

### Setup
1. Place `perimeter-digger-plus-<version>.jar` into your `.minecraft/mods/` directory.
   > **Important (Baritone Compatibility):** This mod bundles and maintains its own customized Baritone fork (derived from the upstream `perimeter-digger` Baritone fork) via Jar-in-Jar. It includes full Baritone functionality (including `#` commands) and **cannot coexist with any separately installed Baritone JAR** (or upstream `perimeter-digger`). Remove any standalone Baritone mod from `mods/`.
2. Launch the game and enter your world.
3. **Hold `F9`** (~400 ms) to open the configuration screen:
   - Choose **Auto Mining** or **Schematic Printing** in the Area & Control tab.
   - For mining: define the digging area by typing coordinates, clicking **Corner 1 / 2 = Feet**, **Mainhand Detect**, or **Read Litematica (`读取投影`)**, and set your `Min Y` / `Max Y`.
   - Adjust strategy, liquid policy, bubble-column escape mode, sealing/scaffold blocks, foods, and feature toggles as needed, then click **Save & Close**.
4. **Tap `F9`** (or run `/perimeterdig start`) to start automated clearing or schematic building.
5. **Tap `F9`** again to pause/resume, or press **`Esc`** (with no GUI open) / run `/perimeterdig stop` to stop.

---

## In-Game Configuration GUI (`F9`)

| Tab | What You Can Configure |
| --- | --- |
| **Area & Control (`选区控制`)** | Work mode (`mine` / `build_schematic`), live status readout, Start/Pause/Stop buttons, rectangular corners & `Min Y / Max Y`, Read Litematica, Mining Strategy, Liquid Policy, Durability Recovery Mode, Schematic policies (`wrong_block_policy`, `missing_material_policy`, `optimize_water`, `clear_extra_blocks`), Resource Points & Per-Layer Material Calculator, and **Bubble Escape Mode**. |
| **Feature Toggles (`功能开关`)** | Drop Collection, Auto Unload, Auto Eat, Tool Durability Protection, Durability Recovery, Cross-Dimension Repair, Auto Resupply, Elytra Navigation, Auto Sleep, 3D Area Overlay, `/fly` Movement, Stall Detection, Auto Swap Backpack Tool, Auto Replace Low-Durability Tool, and **Bubble Escape Mode**. |
| **Blocks & Items (`方块物品`)** | Sealing Blocks, **Scaffold Blocks** (bridging/pillaring), Allowed Foods (+ Auto Swap Food toggle), Unload Keep Whitelist, Mining Whitelist (skip blocks + toggle), Mining Blacklist (only mine blocks + toggle), each with a one-click `+Mainhand` button. |
| **Waypoints & Debug (`坐标点位`)** | Named points (Consumable Supply, Durability Supply, Bed, Overworld/Nether Portals, Furnace Row Start/End), multi-unloading-point management, and single-flow debug triggers (Unload, Supply, Repair, Sleep). |
| **Advanced (`数值参数`)** | 6 sub-pages covering all 32 `AdvancedConfig` numeric parameters (durability thresholds, food/health thresholds, stall detection timeouts, drop collection, navigation/portal/repair timeouts, etc.). |

---

## Command Reference

All commands live under `/perimeterdig`:

```text
# Lifecycle & Status
/perimeterdig start
/perimeterdig pause
/perimeterdig resume
/perimeterdig stop
/perimeterdig reload
/perimeterdig status [clear | history [all | clear]]

# Area Selection
/perimeterdig plan litematica
/perimeterdig plan rectangle <x0> <z0> <x1> <z1> <minY> <maxY>
/perimeterdig detect <block>

# Core Configuration
/perimeterdig config show
/perimeterdig config clear detected_area
/perimeterdig config set digging_min_y <y>
/perimeterdig config set digging_max_y <y>
/perimeterdig config set liquid_policy <seal_boundary|replace|avoid>
/perimeterdig config set mining_strategy <global_top_down|local_top_down|chunk_top_down>
/perimeterdig config set durability_recovery_mode <repair_portal|supply_point|furnace_row>
/perimeterdig config set work_mode <mine|build_schematic>
/perimeterdig config set wrong_block_policy <skip|break_and_place>
/perimeterdig config set missing_material_policy <place_available|pause_immediately>
/perimeterdig config set clear_extra_blocks <true|false>
/perimeterdig config set optimize_water <true|false>
/perimeterdig config set bubble_escape_mode <auto|scaffold_first|bucket_first|walk_or_fly>
/perimeterdig config set unloading_point <name> <x> <minY> <maxY> <z>
/perimeterdig config set|remove resource_point <name> [<x> <y> <z>]

# Block & Item Lists (add / remove)
/perimeterdig config add|remove sealing_block <block>
/perimeterdig config add|remove food <item>
/perimeterdig config add|remove unloading_whitelist <item>
/perimeterdig config add|remove mining_whitelist <block>
/perimeterdig config add|remove mining_blacklist <block>

# Sub-Flow Debug Triggers
/perimeterdig debug unload
/perimeterdig debug supply <consumables|durability>
/perimeterdig debug repair
/perimeterdig debug sleep
```

---

## Configuration & Compatibility Notes

- **Per-World Config Storage**: World and server configs are stored under `config/perimeter-digger-plus/worlds/` so settings never collide across worlds or with upstream `perimeter-digger`.
- **Single-Player vs. Multiplayer**: Works on both single-player worlds and multiplayer servers (within vanilla client mechanics and server permissions such as `/fly`). Through-wall liquid sealing uses synthesized face clicks within 4.5-block reach; if a strict anti-cheat server rejects it, switch liquid policy to `avoid` or seal boundaries manually.
- **Litematica Compatibility**: Optional soft dependency via reflection, verified against `litematica-fabric-26.2-0.28.8` and `litematica-printer-26.2-3.2.2`. If Litematica is absent, the mod runs normally and informs you only if you click Read Litematica or start schematic building.

---

## Optional Local Agent HTTP API

Disabled by default for security. When explicitly enabled via either:
- creating an empty marker file `config/perimeter-digger-plus/agent-api/enabled`, or
- launching with `-Dperimeterdig.api.enabled=true`

the mod binds a loopback-only (`127.0.0.1:18769`) Bearer-token-authenticated HTTP API (`config/perimeter-digger-plus/agent-api/connection.json`) for external automation/monitoring. See [`agent/`](agent/) for the reference Node.js client (`client.mjs`) and source-contract tests (`client.test.mjs`).

---

## Building & Testing from Source

### 1. Node.js Source-Contract Tests (Node.js 18+, No Minecraft Required)
```bash
node --test agent/client.test.mjs
```

### 2. Local Windows Build, Unit Tests & Install (JDK 25 + Local Fabric 26.2 Instance)
```powershell
# Build embedded Baritone fork + single Jar-in-Jar mod artifact
powershell -ExecutionPolicy Bypass -File scripts/build-local.ps1 -MinecraftDir "<path-to-.minecraft>"

# Run Node contract tests + Baritone JUnit + Perimeter Digger Plus JUnit
powershell -ExecutionPolicy Bypass -File scripts/test-local.ps1 -MinecraftDir "<path-to-.minecraft>"

# Install single JAR into mods/ (automatically disables older Perimeter/Baritone JARs)
powershell -ExecutionPolicy Bypass -File scripts/install-local.ps1 -ModsDir "<path-to-mods>"
```

---

## Repository Layout

```text
Perimeter-Digger-Plus/
├── src/                # Main, client, and JUnit test sources (modid: perimeter-digger-plus)
├── baritone/           # Bundled customized Baritone fork (LGPL-3.0)
├── printer-patch/      # Optional source patch for litematica-printer-26.2-3.2.2
├── agent/              # Optional local HTTP API Node.js client & contract tests
├── scripts/            # Portable PowerShell build, test, and install scripts
├── .github/workflows/  # CI workflow
├── build.gradle        # Fabric Loom build definition
├── gradle.properties   # Minecraft / Fabric / Mod version properties
├── README.md           # English documentation (Primary)
├── README.zh-CN.md     # Simplified Chinese documentation
└── LICENSE             # GPL-3.0-or-later
```

---

## Roadmap

- [x] Minecraft 26.2 + Fabric Loader 0.20.1+ all-in-one Jar-in-Jar release
- [x] Full `F9` 5-tab GUI, 3D selection overlay, Litematica footprint import, `/fly` mining, scaffold/sealing separation & anti-stall hardening
- [x] Layer-by-layer schematic printing, optimized water source placement, shulker/chest resupply & downward bubble-column escape strategies
- [ ] Support additional Minecraft versions
- [ ] Support additional mod loaders (NeoForge / Forge)
- [ ] Expanded English/localized UI strings alongside Chinese labels

---

## License & Attribution

- **Perimeter Digger Plus** is licensed under the **GNU General Public License v3.0 or later** — see [`LICENSE`](LICENSE).
- Upstream project: [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) (`Copyright (C) 2026 HackerRouter`, GPL-3.0-or-later).
- The bundled customized Baritone fork in [`baritone/`](baritone/) is licensed under the **GNU Lesser General Public License v3.0** — see [`baritone/LICENSE`](baritone/LICENSE), derived from [cabaletta/baritone](https://github.com/cabaletta/baritone) and [HackerRouter/baritone](https://github.com/HackerRouter/baritone).
- The optional compatibility patch in [`printer-patch/`](printer-patch/) (and `litematica-printer-26.2-3.2.2-patched.jar`) is modified from [aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer) (original license: **CC0-1.0**).
