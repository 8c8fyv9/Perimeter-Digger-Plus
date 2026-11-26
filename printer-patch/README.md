# Litematica Printer 26.2-3.2.2 Compatibility Patch

Modified from **[aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer)** (original license: **CC0-1.0**).

## What This Patch Fixes
1. **Slabs (`SlabGuide`, `GeneralPlacementGuide`, `BlockReplacementGuide`, `PlacementGuide`)**: Fixes `printInAir=true` turning `BOTTOM` slabs into `TOP` slabs and failing to merge `DOUBLE` slabs.
2. **Torches & Redstone Torches (`Guides`, `TorchGuide`)**: Registers `TorchGuide` for `BaseTorchBlock` (covering `RedstoneTorchBlock` / `RedstoneWallTorchBlock`) with support-face and look-direction locking.
3. **Fences, Gates & Walls (`ActionHandler`, `GuesserGuide`, `PropertySpecificGuesserGuide`, `CycleStateGuide`, `BlockHelperImpl`)**: Adds center hit probe `(0,0,0)`, preserves `lookDirection` under `printInAir=true`, ignores `IN_WALL` / `STAIRS_SHAPE` / wall connections during state checks, and executes `Prepare -> Interact -> ReleaseShift` atomically within the same tick.
4. **Signs (`RotatingBlockGuide`, `PrinterPlacementContext`)**: Fixes wall sign / standing sign / hanging sign `canSurvive`, yaw/pitch rotation, and sign-on-sign support clicking.
5. **Logs & Stripping (`LogGuide`, `LogStrippingGuide`, `PrepareAction`, `InteractActionImpl`)**: Preserves `AXIS` during placement/stripping and avoids unintended secondary `useItem` calls with leftover axes or buckets.

---

# Litematica Printer 26.2-3.2.2 兼容性补丁

修改自 **[aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer)**（原协议：**CC0-1.0**）。

修复了上游打印机在开启 `printInAir=true` 或复杂结构下的 5 类放置问题：半砖（上/下/双）、红石火把/普通火把、栅栏/栅栏门/石墙、告示牌（立/墙/悬挂）以及原木轴向保护与防误剥皮/误倒水。
