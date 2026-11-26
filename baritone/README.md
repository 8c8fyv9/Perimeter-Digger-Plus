# Bundled Customized Baritone Fork

**English** | [简体中文](README.zh-CN.md)

[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0-green.svg)](LICENSE)

This directory contains the customized **Baritone** fork bundled into **Perimeter Digger Plus** via Fabric Jar-in-Jar (`Minecraft 26.2`, `Fabric Loader 0.20.1+`).

## Enhancements Over Upstream
- Derived from [HackerRouter/baritone](https://github.com/HackerRouter/baritone) and [cabaletta/baritone](https://github.com/cabaletta/baritone).
- **Area Mining Engine**: Top-down strategies (`global_top_down`, `local_top_down`, `chunk_top_down`), `SealedPocketDetector`, `PlacementBackoff`, and `StuckCellPenalty`.
- **Downward Bubble-Column Awareness**: Prevents floating/swimming/jumping inside `BUBBLE_COLUMN[drag=true]` (`MovementHelper`, `MovementPillar`, `MovementAscend`, `MovementTraverse`, `MovementDescend`).
- **Safety & Protection**: `minY - 1` bottom-layer guard, `ScaffoldTracker` self-mining protection, mud walking, `HotbarSwapPlanner` / `BackpackToolManager`, and `/fly` creative flight building/mining support.

## Compatibility & License
- Provides full standalone Baritone `#` chat commands (`#goto`, `#mine`, `#stop`, etc.).
- **Cannot coexist** with any separately installed Baritone JAR.
- Licensed under **GNU Lesser General Public License v3.0** — see [`LICENSE`](LICENSE). Uses [modified nether-pathfinder](https://github.com/HackerRouter/nether-pathfinder).
