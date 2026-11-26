# Litematica Printer 26.2-3.2.2 Compatibility Patch

**English** | [简体中文](README.zh-CN.md)

Modified from **[aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer)** (original license: **CC0-1.0**).

## Fixes Included
1. **Slabs**: Fixes `printInAir=true` turning `BOTTOM` slabs into `TOP` slabs and failing to merge `DOUBLE` slabs.
2. **Torches & Redstone Torches**: Registers `TorchGuide` for `BaseTorchBlock` (covering `RedstoneTorchBlock` / `RedstoneWallTorchBlock`) with support-face and look-direction locking.
3. **Fences, Gates & Walls**: Adds `(0,0,0)` center hit probe, preserves `lookDirection` under `printInAir=true`, ignores `IN_WALL` / `STAIRS_SHAPE` / wall connections, and executes `Prepare -> Interact -> ReleaseShift` atomically in one tick.
4. **Signs**: Fixes standing/wall/hanging sign `canSurvive`, yaw/pitch rotation, and sign-on-sign support clicking.
5. **Logs & Stripping**: Preserves `AXIS` during placement/stripping and removes unintended secondary `useItem` calls with leftover axes or buckets.
