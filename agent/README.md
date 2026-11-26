# Local Agent HTTP API

**English** | [简体中文](README.zh-CN.md)

Loopback-only (`127.0.0.1:18769`) Bearer-token HTTP API exposing `/perimeterdig` Brigadier commands and cuboid task control. **Disabled by default.**

## Enable

Create an empty file `config/perimeter-digger-plus/agent-api/enabled` or add JVM flag `-Dperimeterdig.api.enabled=true` (optional port override: `-Dperimeterdig.api.port=18769`).
On startup, the active URL and random token are written to `config/perimeter-digger-plus/agent-api/connection.json` (local secret — do not share or commit).

## Node.js Client (`client.mjs`, Node 18+)

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
console.log(await api.plan(task)); // Geometry preview only
// await api.start(task);
// await api.pause(task.taskId);
// await api.resume(task.taskId);
// await api.stop(task.taskId);
```

## Endpoints

All requests require `Authorization: Bearer <token>`.

| Method & Path | Description |
| --- | --- |
| `GET /v1/commands` | List all `/perimeterdig` command syntax from the live Brigadier tree. |
| `POST /v1/commands` | Execute a mod command (`{"command": "config show"}`). |
| `GET /v1/status` | Current automation state, world, dimension, and task progress. |
| `POST /v1/tasks/plan` | Cuboid geometry preview (non-destructive). |
| `POST /v1/tasks` | Start a cuboid task (`taskId`, `world`, `dimension`, `from`, `to` inclusive). |
| `POST /v1/tasks/pause \| resume \| stop` | Control an active cuboid task (`{"taskId": "..."}`). |

## Notes & Tests

- Executes `perimeter-digger-plus` commands on the client thread only (never arbitrary chat/server/OS commands).
- `POST /v1/commands` and `POST /v1/tasks` do not auto-retry; check `GET /v1/status` on timeout.
- Contract tests: `node --test agent/client.test.mjs`.
