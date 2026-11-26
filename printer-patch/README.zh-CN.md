# Litematica Printer 26.2-3.2.2 兼容性补丁

[English](README.md) | **简体中文**

修改自 **[aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer)**（原协议：**CC0-1.0**）。

## 修复内容
1. **半砖**：修复开启 `printInAir=true` 时下半砖全被放成上半砖、以及双半砖第二块无法原地合并的问题。
2. **红石火把与普通火把**：将 `TorchGuide` 注册到 `BaseTorchBlock`（覆盖红石火把/墙火把），锁定支撑面与朝向。
3. **栅栏、栅栏门与石墙**：补充 `(0,0,0)` 中心点击点、在 `printInAir=true` 下保留朝向、忽略 `IN_WALL` / `STAIRS_SHAPE` / 石墙连接属性，并改为同 tick 原子执行潜行/朝向/交互。
4. **告示牌**：修复立牌/墙牌/悬挂告示牌的生存校验、俯仰与偏航角，并支持牌贴牌。
5. **原木与剥皮**：放置与剥皮时严格校验 `AXIS` 轴向，并移除残留斧头/水桶导致的误操作 `useItem` 调用。
