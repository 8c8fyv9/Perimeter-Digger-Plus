# Perimeter Digger Plus（空置域自动挖掘与投影建造增强版）

[English](README.md) | **简体中文**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52A535?logo=minecraft&logoColor=white)](https://minecraft.net)
[![Mod Loader](https://img.shields.io/badge/Loader-Fabric%200.20.1%2B-DBD0B4)](https://fabricmc.net)
[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00)](https://adoptium.net)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

基于 [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) 二次开发的 Minecraft `26.2`（Fabric）客户端**自动区域挖掘与逐层投影建造**模组。单包内置定制版 Baritone（`modid: perimeter-digger-plus`，独立配置目录 `config/perimeter-digger-plus/`）。

---

## 核心功能

- **单包整合（Jar-in-Jar）**：内置自维护 Baritone 分支（衍生自 `perimeter-digger` 的 Baritone 分支），保留全部 `#` 指令并修复寻路问题。
- **`F9` 图形界面与 3D 选区**：短按 `F9` 开始/暂停/恢复，长按 `F9`（约 400ms）打开 5 标签配置页，无界面时按 `Esc` 停止；支持分维度 3D 半透明选区渲染。
- **自动区域挖掘**：
  - 一键读取 **Litematica** 投影占地（零硬依赖）或手动矩形/探界选区。
  - 支持 `global_top_down` / `local_top_down` / `chunk_top_down` 三种自顶向下策略，兼容地面 Baritone 与 `/fly` 3D 飞行挖掘。
  - 方块黑白名单与封闭口袋检测、`minY - 1` 防挖穿、脚手架防自挖、自动换工具/换食/修补/卸货，以及受击自动关界面反击。
- **逐层自动投影打印（`build_schematic`）**：
  - 自底向上逐层切换 Litematica 单层显示（支持「跳过」或「破坏重放」），内置三视图材料盘点、箱子/潜影盒自动补料、精准告示牌放置。
  - **智能水体优化**：矩形水域达 $\lceil (W+L)/2 \rceil$ 理论最优倒水次数，生存自动合并空桶就地续水并防外溢。
  - **向下气泡柱寻路与脱困**：Baritone 原生规避在向下气泡柱中悬浮/起跳，支持 `auto` / `scaffold_first` / `bucket_first` / `walk_or_fly` 4 种可配置脱困策略。

---

## 安装说明

### 环境要求
- Minecraft **26.2**、**Fabric Loader 0.20.1+**、**Fabric API**、**Java 25+**
- *（可选）* **Litematica** 与 **`litematica-printer`** —— 如使用逐层投影打印，**强烈建议使用我们维护的补丁版打印机**（Releases 中的 `litematica-printer-26.2-3.2.2-patched.jar` 或由 [`printer-patch/`](printer-patch/) 构建，已修复半砖、火把、栅栏/栅栏门/石墙、告示牌与原木剥皮 5 类错放）。

### 注意事项
1. 将 `perimeter-digger-plus-<version>.jar` 放入 `.minecraft/mods/` 目录。
2. **Baritone 不共存**：本模组内置并自行维护定制版 Baritone（可直接使用 `#goto`、`#mine` 等全部 `#` 指令），**与其他单独安装的 Baritone 模组及原版 `perimeter-digger` 互不共存**，安装前请移除 `mods/` 中的独立 Baritone JAR。

---

## 快捷操作与常用命令

- **短按 `F9`**：开始 / 暂停 / 恢复 · **长按 `F9`**：打开设置界面 · **`Esc`**：停止（无界面时）
- 常用 `/perimeterdig` 命令：
  - `/perimeterdig start | pause | resume | stop | status`
  - `/perimeterdig plan litematica | rectangle <x0> <z0> <x1> <z1> <minY> <maxY>`
  - `/perimeterdig config set work_mode <mine|build_schematic>`
  - `/perimeterdig config set mining_strategy <global_top_down|local_top_down|chunk_top_down>`
  - `/perimeterdig config set liquid_policy <seal_boundary|replace|avoid>`
  - `/perimeterdig config set bubble_escape_mode <auto|scaffold_first|bucket_first|walk_or_fly>`

> 可选本地 HTTP 接口（`127.0.0.1:18769`）**默认关闭**（需创建 `config/perimeter-digger-plus/agent-api/enabled` 或加 `-Dperimeterdig.api.enabled=true` 开启），详见 [`agent/`](agent/)。

---

## 从源码构建与测试

```bash
node --test agent/client.test.mjs
```
```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-local.ps1 -MinecraftDir "<你的.minecraft路径>"
powershell -ExecutionPolicy Bypass -File scripts/test-local.ps1 -MinecraftDir "<你的.minecraft路径>"
```

---

## 开源协议与致谢

- **Perimeter Digger Plus**：**GPL-3.0-or-later**（[`LICENSE`](LICENSE)），衍生自 [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger)（`Copyright (C) 2026 HackerRouter`，GPL-3.0-or-later）。
- **内置 Baritone**（[`baritone/`](baritone/)）：**LGPL-3.0**（[`baritone/LICENSE`](baritone/LICENSE)），衍生自 [cabaletta/baritone](https://github.com/cabaletta/baritone) 与 [HackerRouter/baritone](https://github.com/HackerRouter/baritone)。
- **打印机补丁**（[`printer-patch/`](printer-patch/)）：修改自 [aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer)（原协议：**CC0-1.0**）。
