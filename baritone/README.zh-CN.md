# 内置定制版 Baritone 分支

[English](README.md) | **简体中文**

[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0-green.svg)](LICENSE)

本目录为随 **Perimeter Digger Plus** 通过 Fabric Jar-in-Jar 一并打包的定制版 **Baritone** 分支（适配 `Minecraft 26.2`、`Fabric Loader 0.20.1+`）。

## 相对上游的主要增强
- 衍生自 [HackerRouter/baritone](https://github.com/HackerRouter/baritone) 与 [cabaletta/baritone](https://github.com/cabaletta/baritone)。
- **区域挖掘引擎**：支持 `global_top_down` / `local_top_down` / `chunk_top_down` 三种自顶向下策略、封闭口袋检测（`SealedPocketDetector`）、放置退避（`PlacementBackoff`）与卡点惩罚（`StuckCellPenalty`）。
- **向下气泡柱寻路感知**：在 `MovementHelper`、`MovementPillar`、`MovementAscend`、`MovementTraverse`、`MovementDescend` 中禁止在 `BUBBLE_COLUMN[drag=true]` 内悬浮游泳或原地起跳。
- **安全与保护**：`minY - 1` 防挖穿守卫、`ScaffoldTracker` 脚手架防自挖、泥地通行、背包换工具（`HotbarSwapPlanner` / `BackpackToolManager`）及 `/fly` 创造飞行破放支持。

## 兼容性与协议
- 完整保留 Baritone 原生 `#` 聊天指令（`#goto`、`#mine`、`#stop` 等）。
- **与其他单独安装的 Baritone 模组互不共存**。
- 基于 **GNU Lesser General Public License v3.0** 协议开源，详见 [`LICENSE`](LICENSE)；使用 [修改版 nether-pathfinder](https://github.com/HackerRouter/nether-pathfinder)。
