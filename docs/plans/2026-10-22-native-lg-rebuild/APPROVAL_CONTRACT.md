# 审批与工程变更契约

本文说明本轮写入源码的 `PreparedChangeSet` 与审批协调器边界。实施进度只记
[HANDOFF](HANDOFF.md)。它不表示审批 UI、生产存储、工程写入口或 `workspace_apply` 已完成。

## 1. 实际代码与消费者

| 位置 | 已实现的责任 | 当前消费者 |
|---|---|---|
| `domain/ChangeSet.kt` | 变更对象身份、操作类型、基准对象指纹、批次摘要与规范 SHA-256 | `ApprovalRecord`、`ApprovalGrant`、变更摘要测试 |
| `domain/Approval.kt` | 审批记录、状态、授权凭据、CAS 端口与结果分类 | `ApprovalCoordinator`；只有测试提供内存适配器 |
| `engine/ApprovalCoordinator.kt` | 创建、裁决、超时、取消、单次消费与模式快照 | 引擎测试；尚未接应用服务、UI 或工具注册表 |

路径相对 `core/<module>/src/main/kotlin/com/breakyuna/noveltranslator/nativecore/`。
没有复制旧 `AgentDecisionCoordinator` 的会话对象、SSE 或 `AppViewModel` 依赖，也没有假审批进入 app 主链路。

## 2. 变更批次

- 身份是 `ChangeTarget(kind, id, parentId)`，不是列表序号、译文或行号。`ITEM`、`PDF_PAGE`、`ASSET`、四类规则与提示词分别计数。
- `CREATE` 不得携带基准指纹；`UPDATE`/`DELETE`/`REORDER` 必须携带，确保过时批次被写入口拒绝而不是覆盖新事实。
- 同一批次重复对象直接拒绝；空批次拒绝；孤立 UTF-16 代理项拒绝，避免哈希碰撞。
- `digest` 覆盖版本、批次身份、Project/Chat/Run 与逐项字段，规范编码固定字段顺序。**在用户看到差异之后再编辑内容会使 digest 不再匹配**，必须重新准备并重新审批。
- `summary` 的计数用于审批页展示；`assets` 是原生扩展字段，不冒充 LG 摘要字段。

## 3. 审批状态

```text
PENDING ──allow_once──> APPROVED ──consume──> CONSUMED
   │                       │
   ├──reject──> REJECTED   └──(不能再次消费)
   ├──deadline/恢复──> EXPIRED
   └──运行收尾──> CANCELLED
```

- 裁决只有 `ALLOW_ONCE` 与 `REJECT`，与 LG `AgentWriteApprovalDecision` 的 `allow_once`/`reject` 一致；不存在会话级长期授权。
- 每个 Chat 同时最多一个 `PENDING`；服务层预检查，存储适配器必须原子保证。
- `mode` 在创建记录时快照。记录为 `MANUAL` 时，之后把应用偏好改成 `AUTO` 不会替当前请求裁决；记录为 `AUTO` 时立即结算为 `APPROVED`，不进入待决列表。
- `AUTO` 记录不允许 deadline；`MANUAL` 超时由 `expireOverdue` 在服务恢复/前台时结算，不在协调器里起定时器。
- LG 当前没有审批倒计时；超时是原生补充终态，调用方不传 deadline 时不启用，不改变 LG 默认行为。
- `EXPIRED`、`CANCELLED`、`REJECTED` 都是终态，之后任何 `decide` 都返回已结算状态，不能改成批准。
- `cancelRun` 只结算属于真正结束的 Run 的请求，不影响同一 Chat 的下一轮。

## 4. 单次授权与提交

`consume` 返回 `ApprovalGrant`，包含请求、工程、Chat、Run、批次身份与 digest。
存储端 CAS 只能把 `APPROVED` 改成 `CONSUMED` 一次；第二次消费返回 `ALREADY_CONSUMED`。
写入口必须再次核对 grant 与自己的持久回执、对象指纹和分区修订，grant 对象本身不是授权。

重复 `request`（相同请求身份与相同内容）是幂等的；相同身份但内容、Run 或模式不同返回冲突。
这对应“传输重试复用命令身份，用户重新发送产生新尝试”。

## 5. 尚未完成

1. **生产存储适配器**：Room `AppStore` 的原子 insert/settle/consume、单 Chat 唯一 pending、崩溃恢复与审计字段。当前只有测试内存实现。
2. **真正写入口**：`ProjectWriteStore` 消费 grant、核对工程回执并提交，仍是 R08。
3. **工具接线**：`AgentToolRegistry` 继续拒绝 `PROJECT_WRITE`；审批可用后必须由真实执行层接入，不能靠模型文案或假端口放行。
4. **UI**：U02 的差异摘要、允许一次/拒绝、超时与恢复、自动偏好入口尚未实现。
5. **恢复语义**：进程死亡后的待决列表、前台恢复触发 `expireOverdue`、与 Runtime 关闭顺序的竞争测试。
6. **差异生成**：当前只保存摘要与指纹，没有把正文差异绑定到展示；实现时不得先展示一套、提交另一套。

## 6. 验证边界

新增 domain/engine 三个测试文件共 19 个用例（变更批次 7、审批记录 2、协调器 10）尚未执行。
本机只做静态审查：模块边界脚本、包名/地雷扫描、人工类型核对；未运行 Gradle、测试、Lint 或设备验证。
真实审批必须由 PC/CI 测试与后续设备流程证明，本文不登记任何功能 ID 为完成。
