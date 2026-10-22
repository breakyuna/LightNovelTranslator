# 原生 Agent 结构化对话与远程模型切片

源码契约；实际进度与验证只记入 [HANDOFF](HANDOFF.md) §25。用户要求暂不验证、继续修改：本轮没有构建/测试、CI、实机或真实服务商调用证据。

## 接线

原生 Agent 页 → NativeProjectViewModel → NativeKoogConversation（core/agent）→ Koog functionalStrategy/PromptExecutor → AgentRequestBoundary → NativeModelGateway（core/engine）→ CompatibleAgentModel → 用户配置的 HTTPS Chat Completions 端点。

同一个 NativeModelGateway 实例同时装配前轮 TXT 本地模拟和远程 Agent 端口。core/agent 只依赖领域模型端口，不导入 HTTP 客户端；网络适配在 core/engine。原有本地 Koog 查询/审批演练仍单独标注为离线操作，不能把本地回答冒充语言模型。

界面新增模型基址、模型 ID 和 Key 设置；保存不会请求服务商。只有用户点击“发送 · 可能计费”后才发起对话，文案明确本会话与请求读取的工程文本会交给服务商。用户授权开发不等于 Agent 在本机执行真实付费联调，本轮没有读取用户 Key 或发送模型请求。

## 配置与凭据

独立应用 Room v2 的目标 schema 增加 native_model_settings，按修订保留不可变配置。请求固定 ModelConfigurationRef，执行时按精确修订读取，不使用最新配置替代已接受意图。认证写保留阻止活动运行期间改配置。

Key 以新的 Android Keystore alias / AES-GCM 加密，AAD 绑定配置身份/修订，密文保存于独立应用数据库。新路径不调用旧 ApiKeyCipher，也不读取旧 provider/阅读库。UI 状态只包含基址、模型和配置引用，Key 输入只在临时密码字段中，退出/保存清空，不写 Bundle/SavedStateHandle、历史、提示词或日志。修改配置须重新输入 Key；密钥轮换、删除/凭据迁移与所有供应商能力矩阵仍待实现。

基址仅允许无 userinfo/query/fragment 的 HTTPS，用户填写 API 基址（需要时包含 /v1），适配器追加 /chat/completions。只实现兼容的非流式文本/工具形状，不是所有协议/供应商。禁止自动连接重试及 HTTP/HTTPS 重定向；90 秒请求超时，输出读取受 2 MiB 字节预算约束，取消回收 Call 和响应资源，异常分类不携带原始请求/正文/Key。HTTP 400 当前统一归 CONTENT，尚未逐一识别供应商上下文错误；RPM/并发队列、Retry-After、代理、价格和自动探测仍未实现。

## 结构化历史与工具循环

AgentMessageCodec v1 分别保存 system/user/assistant/tool 原生角色、工具稳定 ID 和 provider ID、参数/结果、thinking 和 continuation、冻结图片身份。Agent 请求回执升级为版本化的完整 assistant/stop/usage envelope，不再仅保存拼接纯文本；未知 usage 保持 UNKNOWN，不推算费用或伪造零值。HTTP 适配只支持文本、工具和 reasoning_content；图片及不认识的 continuation 显式拒绝，不静默降级。

每一轮保存用户消息后清理已接受输入草稿；模型回执由 AgentRequestBoundary 持久化，随后把 assistant 与逐条工具结果保存到同一实例/代际/Chat 的结构化历史。provider 消息保持原生角色，不把工具回执拼成用户文字。Koog 提供功能策略调度；执行器向共享网关传递独立的规范化 native history，而不是从 Koog 展示 prompt 重建角色。

工具只开放 project_summary、read_item、prepare_text_edit。参数严格整数/范围验证；后者只保存不可变待审批差异，不执行工程写入。每轮最多 8 次模型响应、单响应最多 8 次工具调用，相同工具/规范化参数重复调用熔断。成功准备差异后停止后续模型请求，等待用户裁决。下一次请求由 host system context 提供持久审批结果，避免把准备成功当成工程提交。通用项目写、工作区写、认证或额外付费工具仍关闭。

取消时为本轮尚未结算的工具保存 cancelled 结果，不执行它们；进程死亡留下未配对工具时阻止继续该历史，不自动重放。DISPATCHED 未结算请求仍是 Uncertain，绝不自动重试。请求失败的长期分类账/对账 UI 仍不完整，不能把未收到响应等同于零费用。

## 会话与预算

新增 native_agent_chats 及显式选择状态，按实例/代际保存当前会话，可新建和切换，旧历史不删除。当前每工程最多 50 个会话；选中会话重启后可恢复，列表历史读取最近 50 条预览。用户输入草稿有工程/Chat 身份，最多 8000 字符；存在未清输入时禁止新建/切换，避免默默丢弃。

规范化历史最多 256 条、累计约 2 MiB UTF-8 字节、单消息 512 Ki 字符；发起新回合前限制已有历史条数，模型请求另有 1 Mi 字符历史与 2 MiB 字节正文预算。超限或未配对历史必须显式新建会话，不自动截断工具链。这些是首轮防护值，不是设备实测预算。会话分支、归档/删除、历史分页/上下文压缩和完整多输入队列仍待实现。

## 数据版本与验证

Room 仍处于未生成/未发布的 v2 目标：本轮扩展 v1→v2 建表迁移，加入模型配置、会话和结构化 payload 字段，保留已提交 v1 schema 与工程索引。不能把这个目标当作已经发布的 v2 再绕过迁移；后续获授权 PC/CI 生成 2.json 并审阅，当前不手写。旧阅读库和 .lnt v1 不变。

新增 codec 原生字段往返、结构化工具循环等待审批、会话切换/重启隔离等用例，仅落地源码，未运行。KeyStore、HTTPS/真实协议、Koog 新依赖、Room v2 迁移、端到端审批及新增界面全部尚待验证；S1/S2 不关闭验收。当前非流式、无后台 Service/持久 Run 恢复，真实模型请求只在前台 ViewModel 所有的回合中执行。
