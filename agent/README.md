# Perimeter Digger Agent API（1.3.0 / Minecraft 26.2）

## 设计
复用模组原有Brigadier命令树，不另造配置协议或后勤任务框架。所有现有/perimeterdig命令均可由统一接口调用，新增命令也会自然出现在接口中。保留精确长方体任务接口，便于Agent直接提交坐标。

## 启用
在实际游戏实例的config/perimeter-digger/agent-api目录创建空文件enabled，或添加JVM参数：

	-Dperimeterdig.api.enabled=true

端口可用-Dperimeterdig.api.port=18769调整。显式传false可覆盖enabled文件并关闭服务。
仅监听127.0.0.1，随机Bearer令牌每次启动刷新。connection.json记录url/token，是本机控制凭据，不要上传。端口占用只会禁用API并写日志。正常重启游戏才会加载新模组；复制文件不等于运行中加载。

## Node调用（Node18+，无第三方依赖）
```js
import { PerimeterAgent } from "./client.mjs";
const api = await PerimeterAgent.connect(connectionFile);
console.log(await api.commands());
console.log(await api.command("config show"));
console.log(await api.command("function disable elytra_navigation"));
// 下行是实际挖掘，请确认目标后调用
// await api.command("plan rectangle 100 200 119 209 40 59");
// await api.command("start");

const { context } = await api.status();
const task = {taskId:"basement-001", world:context.world,
  dimension:context.dimension, from:[100,40,200], to:[119,59,209]};
console.log(await api.plan(task)); // 仅几何预览
// await api.start(task);
// await api.pause(task.taskId);
// await api.resume(task.taskId);
// await api.stop(task.taskId);
```
connectionFile替换为实际实例config/perimeter-digger/agent-api/connection.json路径。示例长方体20×20×10，共4000方块位置。

## 接口
| 方法与路径 | 请求/结果 |
|---|---|
| GET /v1/commands | 从真实命令树生成全部可用命令语法；需进入世界 |
| POST /v1/commands | {"command":"config show"}；返回result、success、output文本数组 |
| GET /v1/status | 当前任务、世界、维度、控制器详情与批次进度 |
| POST /v1/tasks/plan | 长方体几何预览，不检查路径库存、不执行 |
| POST /v1/tasks | 启动精确长方体任务 |
| POST /v1/tasks/pause、resume、stop | {"taskId":"..."} |

所有请求带Authorization: Bearer <token>。POST为JSON对象。命令可写短格式、perimeterdig前缀或/perimeterdig前缀。仅执行该模组命令，不执行任意服务端聊天指令、系统命令或其他模组指令。

## 全部现有能力
- start/stop/pause/resume：挖掘控制。
- plan rectangle：保存精确长方体选区；detect：按边界方块探测不规则区域。
- config：读取/设置选区、Y范围、流体策略、封堵材料、食物、卸货点/白名单、补给点、床、传送门、经验熔炉、耐久恢复策略等。
- function：掉落物收集、卸货、进食、耐久恢复、跨维度维修、补给、鞘翅导航、睡觉开关。
- config_advanced：耐久阈值、补给阈值、收集半径、背包保留槽、导航超时/重试与交互参数。
- debug unload/supply consumables/supply durability/repair/sleep：独立运行真实后勤流程。
- status/history/clear/reload：状态、历史和配置重载。
具体参数以GET /v1/commands返回的实际树为准，不维护另一套重复列表。

## 任务语义与边界
长方体字段：taskId/world/dimension/from/to，其中from/to为三个整数，包含端点，支持倒序、负坐标、跨区块；不按区块扩大。volume是几何体积，不是实心方块数。
去除128任务、65536行和1亿体积的自加上限；大型选区仍需分配每个Z行并实际扫描，调用方应按机器性能拆分。坐标合法性、整数溢出和实际维度Y边界仍检查。
同一taskId同一参数重提不重复执行；不同参数复用报冲突。去重只在游戏进程内，不持久恢复。命令接口没有去重，不自动重试，响应丢失后先查询状态/配置，尤其不要盲目重发debug动作。
任务接口克隆配置，不覆盖保存选区；普通config命令则按原逻辑保存配置。任务运行时保留原控制器占用约束，使用任务resume恢复其临时选区；原生后勤阶段不支持暂停。
命令success仅表示命令被成功执行/受理，不代表后台挖掘或后勤已经完成。用status持续查询。COMPLETE仅控制器报告，不是逐方块空气验收。
精确目标不等于范围外零修改：seal_boundary可能在侧边及顶部一格放置方块，寻路可能挖放，后勤可能离开选区。stop不回滚地形；unload是扔物，不是存箱。不能保证不可达、受保护或有基岩/流体的区域完全挖空。
保留本机鉴权及HTTP请求大小/等待超时以处理传输错误。超时结果不明先查status，不自动重试。所有游戏操作在客户端线程执行。
未新增创造式/权限飞行，仍沿用原有步行与鞘翅导航。

## 验证
22项Java定向测试、2项Node测试通过。包含真实HTTP+假游戏后端以及Brigadier命令执行、输出捕获与语法错误测试。编译测试不代表真实Mixin加载、寻路或挖掘验收。
