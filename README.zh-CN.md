# Perimeter Digger Plus（空置域自动挖掘与投影建造增强版）

[English](README.md) | **简体中文**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52A535?logo=minecraft&logoColor=white)](https://minecraft.net)
[![Mod Loader](https://img.shields.io/badge/Loader-Fabric%200.20.1%2B-DBD0B4)](https://fabricmc.net)
[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00)](https://adoptium.net)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

基于 [HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger) 二次开发的 Minecraft 客户端**自动区域挖掘与逐层投影建造**模组增强版：单包整合自定义 Baritone（Jar-in-Jar），提供全功能 `F9` 图形配置界面、3D 半透明选区渲染、Litematica 投影占地一键读取与逐层自动打印、智能水体优化放置、向下气泡柱寻路规避与 4 种可配置脱困策略、`/fly` 3D 飞行挖掘、三种自顶向下挖掘策略、背包自动换工具/低耐久替换/自动换食/潜影盒补料、最底层防挖穿保护，以及全方位防卡死与受击反击加固。

> **当前支持版本：** Minecraft `26.2` · Fabric Loader `0.20.1+` · Fabric API · Java `25+`  
> **Mod ID：** `perimeter-digger-plus`（独立配置目录 `config/perimeter-digger-plus/`，已声明 `breaks: perimeter-digger` 防止与原版冲突共存）。

---

## 目录

- [核心特性与增强](#核心特性与增强)
- [安装与快速上手](#安装与快速上手)
- [`F9` 图形配置界面说明](#f9-图形配置界面说明)
- [常用命令参考](#常用命令参考)
- [配置与兼容性说明](#配置与兼容性说明)
- [可选本地 Agent HTTP 接口](#可选本地-agent-http-接口)
- [从源码构建与测试](#从源码构建与测试)
- [仓库目录结构](#仓库目录结构)
- [开发路线图](#开发路线图)
- [开源协议与致谢](#开源协议与致谢)

---

## 核心特性与增强

### 单包分发与交互体验
- **单包整合（Jar-in-Jar）**：内置随仓维护的修改版 [`baritone/`](baritone/)，只需放一个 JAR 到 `mods/` 目录即可使用，无需单独下载 Baritone。
- **全功能 `F9` 五标签图形配置界面**：
  - **短按 `F9`**：一键开始 / 暂停 / 恢复任务（`Esc` 仅在没有打开界面时停止自动化）。
  - **长按 `F9`（约 400ms）**：打开完整图形设置界面，无需手改 JSON 或背诵命令。
- **Litematica 投影占地一键导入**：
  - 在 `F9`「选区控制」页点击 **「读取投影」** 或执行 `/perimeterdig plan litematica`。
  - 零硬依赖反射接入（未安装投影模组也能正常运行）：优先读取当前选中投影，其次距玩家最近的已启用投影，自动合并多子区域旋转/镜像后的真实世界占地并校验世界高度；未安装投影、无已放置投影或数据不兼容时给出明确提示。
- **3D 半透明选区渲染**：基于原生 Fabric `LevelRenderEvents.COLLECT_SUBMITS` 与 `DrawableGizmoPrimitives`，支持矩形与探界多边形，按维度隔离显示，修改范围即时更新。

### 逐层自动放置投影（`模式: 投影打印`）
- **逐层自动建造**（零硬依赖反射联动 Litematica 与 `litematica-printer`）：
  - 在 `F9`「选区控制」页一键切换「自动挖掘 / 投影打印」。
  - 自底向上逐层切换 Litematica 单层显示并统计本层所需方块与背包缺口（可切换「先放已有材料再暂停」或「缺料立刻暂停」）；内置三视图材料计算器（当前目标层 / 所有未完成层 / 总体材料），支持箱子、木桶、已放置潜影盒与随身潜影盒递归盘点并计算可建层数。
  - 提供「跳过」与「破坏并重放（可选连带清理空气处多余块）」两种错误方块处理方式，配合 `lastAnchorPos` 连续局部性锚点防止平行墙体乒乓折返，并在停止/暂停/完成后自动还原原有 `LayerRange` 与 `PRINT_MODE`。
- **多资源点自动补料与潜影盒拆收（`SchematicResourceController`）**：
  - 缺料、缺水桶、食物低于阈值、破坏重放缺健康工具或搭路块不足时，自动前往配置箱子/潜影盒取料，或在安全地面自动临时放置、拆取并挖回随身潜影盒。
- **精准告示牌放置（`SchematicSignPlacer`）**：
  - 支持立牌 16 方向、墙牌 4 方向与悬挂告示牌的精确朝向/潜行同步与自动关闭编辑框。
- **智能水体优化放置（`WaterSourceOptimizer` + `SchematicWaterPlacer`）**：
  - 每层实体方块放完后，自动接管含水方块（`waterlogged=true`）与满格水源放置；利用原版双邻格无限水转化机制，规则矩形水域严格达到 $\lceil (W + L) / 2 \rceil$ 理论最优下界（如 10×10 仅需 10 次倒水、20×40 仅需 30 次），不规则/带柱子水域采用前瞻闭包贪心与冗余剪枝。
  - 生存模式自动合并散落空桶并从就近安全可再生水源续桶（随身带 2 桶水即可铺满任意大水池），结合内墙侧视线阈值与 5 射线抖动防外溢校验；若服务端关闭无限水规则，核验后自动回退为逐格补水。
- **可选 `litematica-printer` 26.2-3.2.2 兼容性补丁（`printer-patch/`）**：
  - 修复上游打印机在开启 `printInAir=true` 或复杂结构下的 5 类错放：半砖（上/下/双半砖）、红石火把/普通火把（`BaseTorchBlock` 全接管）、栅栏/栅栏门/石墙（中心命中与同 tick 原子执行）、告示牌（立/墙/悬挂与牌贴牌）、原木轴向保护与防误剥皮/误倒水。

### 双引擎挖掘与策略切换
- **三种可切换自顶向下策略**（地面 Baritone 与 `/fly` 飞行引擎统一支持）：
  - `global_top_down`（整体顶部）：严格优先清空整个选区当前最高层。
  - `local_top_down`（局部顶部）：优先把附近高台/塔状结构自顶向下清平，再切往下一片区域。
  - `chunk_top_down`（单区块）：按距玩家由近到远，逐区块自顶向下清完。
- **`/fly` 3D 飞行导航与挖掘**：
  - 开启飞行移动且角色拥有 `mayfly` 权限时自动启用 3D 悬停挖掘，避开复杂地形与水体寻路。
  - 顶棚/树冠角落自动探测开阔方向并持续降高绕障；飞行受阻时自动就地挖掘前方/头顶选区内的可挖方块开路。
- **方块白名单 / 黑名单 + 封闭口袋检测**：
  - 支持默认关闭的「不挖方块白名单」（保护方块）与「只挖方块黑名单」（仅挖指定方块），同步约束 Baritone 寻路并结合有界 3D 逆向 BFS（`SealedPocketDetector`）自动跳过被保护块完全包裹的不可达区域。

### 向下气泡柱寻路脱困与安全加固
- **向下气泡柱（`BUBBLE_COLUMN[drag=true]`）寻路感知与 4 种可配置脱困策略（`BubbleColumnEscapeController`）**：
  - Baritone 寻路层（`MovementHelper`、`MovementPillar`、`MovementAscend`、`MovementTraverse`、`MovementDescend`）原生识别向下气泡柱下拽物理：禁止将向下气泡柱上方悬浮格当作可站立/游泳平面，禁止在柱内规划水中上浮或原地起跳垫脚，高代价规避主动走入/落入，仅允许沿岩浆块底板自动潜行免伤通行或跨上 `<=0.5` 高下半砖台阶。
  - 在 `F9` 界面或通过 `/perimeterdig config set bubble_escape_mode <模式>` 可切换 4 种脱困策略：
    - `auto`（默认：`自动综合`）：`/fly` 上浮 → 原地放零碰撞块（脚手架/铁轨/告示牌/梯子）或邻格放下半砖/活板门封住岩浆块（5 tick 自动转为普通水并无需起跳直接跨上上浮）→ 安全空桶舀断气泡柱并起跳垫高 → 保持潜行横向走出。
    - `scaffold_first`（`垫块封泡`）：优先放零碰撞块或下半砖/活板门封住底板岩浆块转普通水上浮。
    - `bucket_first`（`空桶断流`）：优先用安全空水桶舀断脚下气泡柱并起跳垫高。
    - `walk_or_fly`（`潜行/飞行`）：绝不临时放块或舀水，仅靠 `/fly` 或保持潜行免伤横向走出。
- **智能背包工具与食物管理**：
  - 挖掘时自动从背包将最合适工具换入物品栏空槽/杂物槽（`autoSwapBackpackTool`），低耐久自动原地换入背包高耐久替代工具（`autoReplaceLowDurabilityTool`），并提供独立「工具耐久保护」开关。
  - 饥饿或低血需要进食时自动将背包允许食物安全换入物品栏（`autoSwapBackpackFood`），不顶掉工具与封堵块，吃光一组中途自动续补，借用关键槽吃完自动归位。
- **独立「搭路方块」与「填水/封堵方块」配置**：
  - 在 `F9`「方块物品」页可分别配置填水/封堵方块与寻路搭路方块（同步至 Baritone `acceptableThrowawayItems`）；搭路方块留空时自动沿用默认（圆石/泥土/石头）+ 封堵方块，填写后严格只用指定搭路方块，不再误消耗地狱岩等填水材料。
- **最底层 `minY - 1` 防挖穿与脚手架防自挖保护**：
  - 在 A\* 寻路代价（`breakCostMultiplierAt`）与执行层（`BlockBreakHelper`）双重拦截 `y < diggingMinY`；通过 `ScaffoldTracker` 保护攀爬/搭桥中的临时脚手架并在完工后自动清净。
- **防原地折返、防 A↔B 乒乓与受击反击加固**：
  - 区域挖掘使用 `REVALIDATE_GOAL_AND_PATH`、顽固目标递增冷却（`45 秒 → 180 秒 → 本周期跳过`）+ `StuckCellPenalty`、有界跨区赶路宽限。
  - 受击反击时每 tick 强制关闭打开的箱子/潜影盒/背包/设置等 GUI 界面并重切剑/斧头反击，不可达怪快速脱战，低血进食先吃完本口再反击。

---

## 安装与快速上手

### 环境要求
- Minecraft **26.2**
- **Fabric Loader 0.20.1+** 与 **Fabric API**
- **Java 25+**
- *（可选）* **Litematica**（与 `litematica-printer`，用于一键读取投影或逐层自动建造；**如使用逐层投影打印，强烈建议使用我们维护的补丁版打印机** `litematica-printer-26.2-3.2.2-patched.jar`，可在 Releases 下载或由 [`printer-patch/`](printer-patch/) 构建，已修复半砖、红石火把、栅栏/栅栏门/石墙、告示牌与原木剥皮 5 类错放）

### 使用步骤
1. 将 `perimeter-digger-plus-<version>.jar` 放入 `.minecraft/mods/` 目录。
   > **重要说明（Baritone 不共存）：** 本模组内置并自行维护了定制版 Baritone（衍生自上游 `perimeter-digger` 的 Baritone 分支），包含完整 Baritone 功能（可直接正常使用 `#goto`、`#mine` 等全部 `#` 指令）**且与其他任何单独安装的 Baritone 模组互不共存**（也不与原版 `perimeter-digger` 共存）。安装前请务必移除 `mods/` 中单独的 Baritone JAR。
2. 启动游戏并进入世界。
3. **长按 `F9`**（约 400ms）打开图形配置界面：
   - 在「选区控制」页切换「自动挖掘 / 投影打印」。
   - 自动挖掘模式下，填写角点或点击「角点 1/2=脚下」「主手探界」或 **「读取投影」** 一键导入 Litematica 投影占地，设置最小/最大 Y 高度。
   - 按需调整挖掘策略、液体策略、气泡脱困模式、填水/搭路方块、允许食物及各项开关，点击 **「保存并关闭」**。
4. **短按 `F9`**（或执行 `/perimeterdig start`）开始自动挖掘或投影建造。
5. 再次 **短按 `F9`** 可暂停/恢复；无打开界面时按 **`Esc`** 或执行 `/perimeterdig stop` 可停止任务。

---

## `F9` 图形配置界面说明

| 标签页 | 可配置内容 |
| --- | --- |
| **选区控制** | 模式切换（自动挖掘 / 投影打印）、实时状态显示、开始/暂停/停止按钮、矩形角点与 `最小Y/最大Y`、读取投影、挖掘/液体/修复策略、投影错块/缺料策略、水体优化、清理多余块、资源点设置、逐层物品计算器与**气泡脱困模式**。 |
| **功能开关** | 掉落收集、自动卸货、自动进食、工具耐久保护、耐久恢复、跨维度维修、自动补给、鞘翅导航、自动睡觉、3D 选区渲染、`/fly` 飞行移动、停滞脱困、背包自动换工具、低耐久自动替换工具与**气泡脱困模式**。 |
| **方块物品** | 填水/封堵方块、**搭路方块**（寻路垫高/搭桥）、允许食物（含自动换食开关）、卸货保留白名单、不挖方块白名单（含开关）、只挖方块黑名单（含开关），每项均带 `+主手` 快捷录入按钮。 |
| **坐标点位** | 固定点位管理（补给点、耐久补给点、床、主世界/下界传送门、熔炉排起止点）、多命名卸货点增删改，以及 4 项单流程调试入口（卸货 / 补给 / 维修 / 睡觉）。 |
| **数值参数** | 共 6 个子页，覆盖全部 32 项 `AdvancedConfig` 高级参数（耐久阈值、饥饿/血量阈值、停滞判定超时、掉落收集、寻路/传送门/维修超时等）。 |

---

## 常用命令参考

所有命令均位于 `/perimeterdig` 下：

```text
# 启停与状态
/perimeterdig start
/perimeterdig pause
/perimeterdig resume
/perimeterdig stop
/perimeterdig reload
/perimeterdig status [clear | history [all | clear]]

# 选区设置
/perimeterdig plan litematica
/perimeterdig plan rectangle <x0> <z0> <x1> <z1> <minY> <maxY>
/perimeterdig detect <block>

# 核心配置
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

# 方块与物品名单（添加 / 移除）
/perimeterdig config add|remove sealing_block <block>
/perimeterdig config add|remove food <item>
/perimeterdig config add|remove unloading_whitelist <item>
/perimeterdig config add|remove mining_whitelist <block>
/perimeterdig config add|remove mining_blacklist <block>

# 单流程调试
/perimeterdig debug unload
/perimeterdig debug supply <consumables|durability>
/perimeterdig debug repair
/perimeterdig debug sleep
```

---

## 配置与兼容性说明

- **分世界/分服独立配置**：配置保存在 `config/perimeter-digger-plus/worlds/` 下，不与上游 `perimeter-digger` 冲突，也不跨服串配置。
- **单人与多人服**：均基于客户端原生交互与服务端允许权限（如 `/fly`）运行。隔墙填水会在 4.5 格交互距离内合成点击面，若遇到极严反作弊服务器拒绝，可改用 `avoid` 液体策略或手动封边。
- **Litematica 兼容性**：采用无硬依赖反射适配，已核对 `litematica-fabric-26.2-0.28.8` 与 `litematica-printer-26.2-3.2.2`；未安装投影模组时完全不影响正常挖掘使用。

---

## 可选本地 Agent HTTP 接口

出于安全考虑**默认关闭**。仅当用户主动开启以下任一项时，才会在本机回环地址 `127.0.0.1:18769` 启动带 Bearer Token 鉴权的只读/控制接口（连接信息写入 `config/perimeter-digger-plus/agent-api/connection.json`）：
- 创建空标记文件 `config/perimeter-digger-plus/agent-api/enabled`；或
- 启动参数加入 `-Dperimeterdig.api.enabled=true`。

配套 Node.js 客户端（`client.mjs`）与源码契约测试（`client.test.mjs`）见 [`agent/`](agent/)。

---

## 从源码构建与测试

### 1. Node.js 源码契约测试（Node.js 18+，无需 Minecraft）
```bash
node --test agent/client.test.mjs
```

### 2. Windows 本地一键构建、测试与安装（JDK 25 + 本地 Fabric 26.2 实例）
```powershell
# 编译内置 Baritone 分支并打包单包 Jar-in-Jar
powershell -ExecutionPolicy Bypass -File scripts/build-local.ps1 -MinecraftDir "<你的.minecraft路径>"

# 运行 Node 契约测试 + Baritone JUnit + Perimeter Digger Plus JUnit
powershell -ExecutionPolicy Bypass -File scripts/test-local.ps1 -MinecraftDir "<你的.minecraft路径>"

# 安装单包 JAR 到 mods/（自动禁用旧版 Perimeter / 独立 Baritone JAR）
powershell -ExecutionPolicy Bypass -File scripts/install-local.ps1 -ModsDir "<你的mods目录>"
```

---

## 仓库目录结构

```text
Perimeter-Digger-Plus/
├── src/                # 主模组、客户端与 JUnit 单测源码（modid: perimeter-digger-plus）
├── baritone/           # 随仓维护的自定义 Baritone 分支（LGPL-3.0）
├── printer-patch/      # 可选的 litematica-printer-26.2-3.2.2 兼容性源码补丁
├── agent/              # 可选本地 HTTP 接口 Node.js 客户端与契约测试
├── scripts/            # 可移植 PowerShell 构建、测试与安装脚本
├── .github/workflows/  # CI 工作流配置
├── build.gradle        # Fabric Loom 构建脚本
├── gradle.properties   # Minecraft / Fabric / 模组版本属性
├── README.md           # 英文说明文档（主文档）
├── README.zh-CN.md     # 简体中文说明文档
└── LICENSE             # GPL-3.0-or-later
```

---

## 开发路线图

- [x] Minecraft 26.2 + Fabric Loader 0.20.1+ 单包 Jar-in-Jar 整合版
- [x] F9 五标签配置页、3D 选区渲染、Litematica 投影读取、`/fly` 飞行挖掘、搭路/填水分离与全链路防卡死加固
- [x] 逐层投影自动打印、水体优化放置、多点/潜影盒补料与向下气泡柱寻路脱困策略
- [ ] 适配更多 Minecraft 版本
- [ ] 适配更多模组加载器（NeoForge / Forge 等）
- [ ] 完善界面多语言本地化

---

## 开源协议与致谢

- **Perimeter Digger Plus** 基于 **GNU General Public License v3.0 or later** 协议开源，详见 [`LICENSE`](LICENSE)。
- 上游项目：[HackerRouter/Perimeter-Digger](https://github.com/HackerRouter/Perimeter-Digger)（`Copyright (C) 2026 HackerRouter`，GPL-3.0-or-later）。
- 内置修改版 Baritone（[`baritone/`](baritone/)）基于 **GNU Lesser General Public License v3.0** 协议开源，详见 [`baritone/LICENSE`](baritone/LICENSE)，衍生自 [cabaletta/baritone](https://github.com/cabaletta/baritone) 与 [HackerRouter/baritone](https://github.com/HackerRouter/baritone)。
- 可选兼容性补丁（[`printer-patch/`](printer-patch/) 与 `litematica-printer-26.2-3.2.2-patched.jar`）修改自 [aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer)（原协议：**CC0-1.0**）。
