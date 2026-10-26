# S2 原生 Agent 单条 TXT 审批切片

后续源码边界：持久执行记录/中断核对、Room v18 与检查点源码已在 `1d3dd1c` 构建/测试，见 [HANDOFF §3](HANDOFF.md)。契约见 [RUN_RECOVERY](RUN_RECOVERY.md)；完整后台执行接续仍未完成。

当前验证边界：单条审批/Koog 切片已由 #158 覆盖；完整 S2 仍未验收，详见 [HANDOFF §3](HANDOFF.md)。§24 原始开发轮的未执行说明是历史记录。

本文记录 §24 单条审批基线；§25 已进一步新增结构化对话、完整 assistant 回执、模型配置及远程传输源码，替代本文“仅纯文本/无远程入口”等当轮限制，详见 [AGENT_CONVERSATION](AGENT_CONVERSATION.md)。相关源码已由 #158 覆盖，真实供应商尚未联调，完整 S2 验收保持开放。

本文件描述当前源码的有限接线，唯一进度和验证记录见 [HANDOFF](HANDOFF.md) §24。用户要求暂不验证、继续推进 Agent；本轮不运行构建/测试、不触发或监控 CI，不把源码接线登记为 S2 验收完成。

## 1. 原生入口与 Koog

Agent 页提供明确标注的本地流程：通过 Koog 查询当前工程，或在当前页条目中选择对象、手动填写拟写译文，进入 Koog 的准备审批回合。没有自由对话、真实语言模型或付费请求，不能把手工填写内容称为模型生成。

调用链：NativeAgentPage → NativeProjectViewModel → nativecore.agent.NativeKoogWorkspace → Koog AIAgent/functionalStrategy/PromptExecutor → AgentRequestBoundary → 本地固定操作后端 → AgentToolRegistry。

`project_summary` 读取绑定实例的真实工程信息；`prepare_text_edit` 经严格整数/范围/字符串 Schema 校验，只产生固定待审批正文。新增 APPROVAL_PREPARATION 效果与 READ_ONLY 一样可进入注册表，但 PROJECT_WRITE/WORKSPACE_WRITE/PAID_REQUEST/AUTHENTICATION 仍拒绝执行。模型文案不能替代“允许一次”。Koog 代码放在 core/agent，UI 不导入 Koog 或旧业务实现；依赖继续使用 catalog 的 1.3.0 和与 app 相同的 Jackson 排除项。

这是保留框架的首个原生固定操作适配，不是完整工具循环迁移。当前 PromptExecutor 只服务其绑定的固定操作，请求的配置、条目参数和正文都参与持久指纹；不能直接拿它执行任意 Prompt 或宣称已支持模型原生工具/思考/图片/分支继续。

## 2. 独立应用 Room v2

NativeAppDatabase 从 v1 升至 v2，保留 project_instances，新增：

| 表 | 事实 |
|---|---|
| native_agent_messages | 实例/代际/Chat 绑定的可见本地历史；列表只取最近 50 条摘要 |
| native_agent_requests | attempt、完整请求指纹、请求身份、PREPARED/DISPATCHED/COMPLETED、纯文本回执及 usage |
| native_agent_approvals | 不可变单条 payload、实例/代际/Project/Chat/Run/命令、摘要、修订/对象指纹和裁决结果 |

MIGRATION_1_2 只新增表/索引，不清库，不访问旧阅读 Room v6。该切片当时原生 `.lnt` 仍为 schema v1，当前工程已升至 v8。已提交 Room 1.json 保留；**2—18.json 必须由后续获授权 PC/CI 的 KSP 生成和审阅，本机未生成、不手写，不声称迁移已验证。**

请求准备比较 AgentRequestFingerprint；DISPATCHED 只允许一个 CAS 获胜者，重启看到未结算请求返回 Uncertain，不自动重发。纯文本响应与完整五类 usage 在同一 Room 更新中保存。本切片显式拒绝工具调用、思考或 provider continuation 响应，避免默默丢弃字段；后续完整 codec 仍待实现。模拟用量为 UNKNOWN，不报告伪造的零 Token/费用。

## 3. 差异、允许一次与唯一写入口

待审批对象包含实际行号、原状态、已保存译文和拟写译文，明确会改为 PROCESSED。正文、工程实例/代际、Project/Chat/Run、固定 command、条目 ID、预期工程修订、基准指纹和 REJECT_BATCH 策略全部参与 SHA-256；UI 与执行读取同一不可变记录，不能从可编辑表单重新生成批准正文。

每个实例/代际/Chat 只允许一个 PENDING/CONSUMED：查询与插入在同一 Room 事务中完成。`允许一次` 在有效真实 AGENT 租约下执行，原子把 PENDING 改为 CONSUMED；该行中已冻结的唯一命令和 payload 同时成为消费绑定，没有“先消费再另建命令”的窗口。`拒绝` 只结算 PENDING，不改工程。

项目写回由 NativeAgentStore 读取并核对持久记录，再调用 storage 内部 approvedEdit → ProjectWriteStore；该内部方法不对 UI/Agent 模型公开。项目事务保留既有命令摘要/对象指纹/修订/回执校验；重复同意返回终态，不再次写入。Agent 没有调用 TextProjectPort.edit 的人工旁路，也没有开放通用 PROJECT_WRITE 工具。

当前只支持单条 TXT 译文/状态替换，不实现完整 ProjectWritePort 的所有对象/部分接受。原生 v1 的单条修订使用既有事务编码，完整审批正文留在本机 Room；导入/复制 `.lnt` 不携带这些本机执行授权。

## 4. 停止与跨库核对

- 允许前仍须有效不透明租约，Stop 先改变运行状态再取消协程；停止阶段不能取得项目写租约。取消准备回合会结算其尚未消费的待审批。
- 项目内容与回执原子提交，Room 最终结果另行补记；不持有 Room 事务等待项目 IO。明确冲突也持久化，不借旧授权重试。
- 消费后异常或取消先按完整命令意图读取回执：已提交则记 COMMITTED，冲突记 CONFLICT，确认未提交记 CANCELLED；读不到或摘要不一致则保留 CONSUMED，不冒充成功。
- 打开/刷新时，恢复取得运行协调器的写保留，只核对已有项目回执。核对或正文验证失败保留未决记录并报告不可核实；界面隔离各项加载失败，继续展示可读取的消息/设置等，并在成功刷新前禁用发送、准备和审批，不展示旧的可执行审批卡。消费后无回执会终止，不自动执行；已存在回执只补记。结果列表显示最近 10 项已保存/拒绝/冲突/终止，避免把消费状态当成已保存。
- 待审批正文可在重启后重新展示，用户明确允许时建立匹配的进程内审批执行租约；不将 UI 本地变量或导入记录当作授权。完整持久 Run/队列/暂停恢复仍未实现，不能称为完整 §5 恢复协议。

## 5. 未完成与验证边界

已写真实 Room/SQLite 的持久差异、幂等同意、拒绝、冲突、跨库故障窗口、payload 篡改、实例隔离、v1→v2 迁移、请求不确定状态，以及 Koog 查询/准备链路的测试；另有 UI 允许一次回调用例。相关源码已由 #158 覆盖，实际范围见 HANDOFF §3；不据此宣称完整 S2 验收。

本地表单不是完整聊天编辑器；自由文本输入、多 Chat/分支、附件、真实模型配置/凭据/统一付费网关、模型生成差异、完整 native tool/continuation codec、完整审批/批次写工具、Run/后台 Service 与所有故障竞争验收仍待后续实现。本轮只有静态审查，未实际构建，不继承 S1 手工版的 CI/真机结论。
