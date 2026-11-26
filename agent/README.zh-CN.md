# 本地 Agent HTTP 接口

[English](README.md) | **简体中文**

仅监听本机回环地址（`127.0.0.1:18769`）的 Bearer Token 鉴权 HTTP 接口，用于调用 `/perimeterdig` 命令树与精确长方体任务控制。**默认关闭。**

## 启用方式

在游戏实例 `config/perimeter-digger-plus/agent-api/` 目录下创建空文件 `enabled`，或添加 JVM 参数 `-Dperimeterdig.api.enabled=true`（可用 `-Dperimeterdig.api.port=18769` 指定端口）。
启动后会将当前地址与随机令牌写入 `config/perimeter-digger-plus/agent-api/connection.json`（本机控制凭据，请勿泄露或提交）。
周围已加载区块与实体快照接口（`/v1/world`、`/v1/chunks`、`/v1/chunk`、`/v1/entities`）会随 Agent API 一并启用，也可通过 `-Dperimeterdig.api.world=false` 或创建空文件 `world-disabled` 单独关闭。

## Node.js 调用示例（`client.mjs`，Node 18+）

```js
import { PerimeterAgent } from "./client.mjs";

const api = await PerimeterAgent.connect("<minecraft>/config/perimeter-digger-plus/agent-api/connection.json");
console.log(await api.commands());
console.log(await api.command("config show"));

const { context } = await api.status();
const task = {
  taskId: "area-001",
  world: context.world,
  dimension: context.dimension,
  from: [100, 40, 200],
  to: [119, 59, 209]
};
console.log(await api.plan(task)); // 仅几何预览
// await api.start(task);
// await api.pause(task.taskId);
// await api.resume(task.taskId);
// await api.stop(task.taskId);
```

## 接口一览

所有请求均需携带 `Authorization: Bearer <token>`。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /v1/commands` | 从实时 Brigadier 命令树读取全部可用 `/perimeterdig` 语法。 |
| `POST /v1/commands` | 执行模组命令（`{"command": "config show"}`）。 |
| `GET /v1/status` | 查询当前自动化状态、世界、维度与任务进度。 |
| `GET /v1/world` | 获取当前世界、维度、角色坐标/区块与 Y 范围 *（可选）*。 |
| `GET /v1/chunks?radius=<r>` | 获取客户端当前已加载的 `[cx, cz]` 区块列表（可按角色周围半径过滤）*（可选）*。 |
| `GET /v1/chunk?cx=<x>&cz=<z>` | 获取指定已加载区块的方块调色板、群系与方块实体快照 *（可选）*。 |
| `GET /v1/entities?items=true` | 获取角色周围已加载实体或掉落物列表 *（可选）*。 |
| `POST /v1/tasks/plan` | 长方体几何预览（不执行挖掘）。 |
| `POST /v1/tasks` | 启动精确长方体任务（`taskId`、`world`、`dimension`、`from`、`to`，含端点）。 |
| `POST /v1/tasks/pause \| resume \| stop` | 暂停 / 恢复 / 停止运行中的任务（`{"taskId": "..."}`）。 |

## 说明与测试

- 仅在客户端线程执行 `perimeter-digger-plus` 模组命令，不执行任意聊天指令、系统命令或其他模组指令。
- `POST /v1/commands` 与 `POST /v1/tasks` 不自动重试；超时后请先查询 `GET /v1/status`。
- 契约测试：`node --test agent/client.test.mjs`。
