# Perimeter Digger Plus

**English** | [简体中文](README.zh-CN.md)

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52A535?logo=minecraft&logoColor=white)](https://minecraft.net)
[![Mod Loader](https://img.shields.io/badge/Loader-Fabric%200.20.1%2B-DBD0B4)](https://fabricmc.net)
[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00)](https://adoptium.net)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

Enhanced client-side **automated area-mining & layer-by-layer schematic-building** mod for Minecraft `26.2` (Fabric), forked from [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger). Bundles a customized Baritone fork via Jar-in-Jar (`modid: perimeter-digger-plus`, `config/perimeter-digger-plus/`).

> **中文简介：** 基于 [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) 增强开发的 **空置域自动挖掘与逐层投影建造** 模组，单包内置定制版 Baritone。完整中文说明请见 **[简体中文 README](README.zh-CN.md)**。

---

## Key Features

- **All-in-One Jar-in-Jar**: Bundles our maintained Baritone fork (derived from `perimeter-digger`'s Baritone fork) with full `#` commands and pathfinding fixes.
- **`F9` In-Game GUI & 3D Overlay**: Tap `F9` to start/pause/resume; hold `F9` (~400 ms) for the 5-tab settings screen; `Esc` (with no GUI open) stops. Translucent 3D selection rendering per dimension.
- **Area Mining**:
  - One-click **Litematica** footprint import (no hard dependency) or rectangle / boundary-block selection.
  - 3 top-down strategies (`global_top_down`, `local_top_down`, `chunk_top_down`) across ground Baritone & `/fly` 3D aerial mining.
  - Block whitelist/blacklist with sealed-pocket detection, `minY - 1` bottom protection, scaffold self-mining protection, auto tool/food/elytra management, multi-point unload, repair/resupply, and mob retaliation (auto-closes open GUIs).
- **Layer-by-Layer Schematic Building (`build_schematic`)**:
  - Steps Litematica's single-layer range bottom-up (`skip` or `break_and_place`), with a 3-view material sufficiency calculator, multi-chest/shulker auto-resupply, and exact sign placement.
  - **Optimized Water Placement**: Hits $\lceil (W+L)/2 \rceil$ on rectangular pools, auto-merges/refills empty buckets in Survival, and guards against outer spills.
  - **Downward Bubble-Column Pathfinding & Escape**: Baritone avoids floating/jumping inside `BUBBLE_COLUMN[drag=true]` and supports 4 configurable escape modes (`auto`, `scaffold_first`, `bucket_first`, `walk_or_fly`).

---

## Installation

### Requirements
- Minecraft **26.2**, **Fabric Loader 0.20.1+**, **Fabric API**, **Java 25+**
- *(Optional)* **Litematica** & **`litematica-printer`** — for schematic printing, **we recommend our maintained patched printer** (`litematica-printer-26.2-3.2.2-patched.jar` in Releases or [`printer-patch/`](printer-patch/)), which fixes slabs, torches, fences/gates/walls, signs, and log stripping.

### Important Notes
1. Drop `perimeter-digger-plus-<version>.jar` into `.minecraft/mods/`.
2. **Baritone Non-Coexistence**: This mod maintains its own Baritone fork inside the JAR (full `#goto` / `#mine` support) and **cannot coexist with any separately installed Baritone JAR** or upstream `perimeter-digger`. Remove standalone Baritone mods from `mods/`.

---

## Quick Usage & Commands

- **Tap `F9`**: Start / Pause / Resume · **Hold `F9`**: Open GUI · **`Esc`**: Stop (when no GUI is open)
- Common `/perimeterdig` commands:
  - `/perimeterdig start | pause | resume | stop | status`
  - `/perimeterdig plan litematica | rectangle <x0> <z0> <x1> <z1> <minY> <maxY>`
  - `/perimeterdig config set work_mode <mine|build_schematic>`
  - `/perimeterdig config set mining_strategy <global_top_down|local_top_down|chunk_top_down>`
  - `/perimeterdig config set liquid_policy <seal_boundary|replace|avoid>`
  - `/perimeterdig config set bubble_escape_mode <auto|scaffold_first|bucket_first|walk_or_fly>`

> Optional loopback HTTP API (`127.0.0.1:18769`) is **disabled by default** (enable via `config/perimeter-digger-plus/agent-api/enabled` or `-Dperimeterdig.api.enabled=true`). See [`agent/`](agent/).

---

## Build & Test

```bash
node --test agent/client.test.mjs
```
```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-local.ps1 -MinecraftDir "<path-to-.minecraft>"
powershell -ExecutionPolicy Bypass -File scripts/test-local.ps1 -MinecraftDir "<path-to-.minecraft>"
```

---

## License & Attribution

- **Perimeter Digger Plus**: **GPL-3.0-or-later** ([`LICENSE`](LICENSE)), forked from [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) (`Copyright (C) 2026 HackerRouter`, GPL-3.0-or-later).
- **Bundled Baritone** ([`baritone/`](baritone/)): **LGPL-3.0** ([`baritone/LICENSE`](baritone/LICENSE)), derived from [cabaletta/baritone](https://github.com/cabaletta/baritone) & [HackerRouter/baritone](https://github.com/HackerRouter/baritone).
- **Printer Patch** ([`printer-patch/`](printer-patch/)): Modified from [aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer) (original license: **CC0-1.0**).
