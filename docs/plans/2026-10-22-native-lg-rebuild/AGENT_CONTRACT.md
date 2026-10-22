# Agent 原生消息与单次请求边界

本文说明本轮已写入源码的端口与原型约束；实施进度只记 [HANDOFF](HANDOFF.md)。
它不是 Koog 已迁移、SDK 检查点已可用或原生产品功能已完成的证明。

## 1. 实际代码与消费者

| 位置 | 已实现的责任 | 当前消费者 |
|---|---|---|
| domain/AgentMessage.kt | 原生角色、稳定调用身份、供应商调用 ID、工具结果、图片固化引用、思考续传数据、配对验证 | AgentModelRequest、请求指纹、AgentRequestBoundary |
| domain/JsonObjectValue.kt | 有界对象解析、转义同名键拒绝、数字 token 保留、脱敏默认字符串 | 工具参数、Schema、工具结果与供应商续传数据 |
| domain/AgentModelPort.kt | 不可变请求、配置修订身份、响应、用量来源、错误与终止分类 | AgentRequestBoundary；只有测试提供假模型实现 |
| domain/AgentPersistencePort.kt | 请求内容核对、发送权 CAS、响应/用量事务回执的端口契约 | AgentRequestBoundary；只有测试提供内存假存储 |
| domain/AgentRequestFingerprint.kt | 版本化 SHA-256 请求内容指纹 | 测试存储及未来持久化适配器共用；不输出正文 |
| domain/AgentToolPort.kt | 标量参数定义同源生成 Schema 和校验器 | AgentToolRegistry |
| agent/AgentRequestBoundary.kt | 单次模型调用、回执重放、不确定拒绝、停止再校验、已知响应保留 | 端口组合测试；尚未接 Koog PromptExecutor |
| agent/AgentToolRegistry.kt | 只读工具分发、参数校验、真实运行 lease 核对、错误脱敏 | 两个测试自有工具；没有假冒工程生产工具 |

所有路径相对 `core/<module>/src/main/kotlin/com/breakyuna/noveltranslator/nativecore/`。
Agent 主源码只依赖 domain、协程与 JSON 树 API；engine 仅作为测试装配依赖，生产源码不跨模块调用 engine。
没有复制旧 LlmGateway、Provider Entity、AgentToolHost、Book/Edition 或全局 ViewModel。

## 2. 历史与参数不变量

- system 只在会话内容之前出现；用户、助手、工具结果角色独立，不转写成一段 user 文本。
- 工具调用包含内部稳定 ID 与可选供应商原始 ID；供应商 ID 可以只在单次请求内唯一，不能拿它覆盖跨回合内部身份。
- 工具结果按内部调用 ID 与工具名配对。请求不能夹带缺结果的工具前缀、重复结果/调用或重复消息 ID；检查点准备可以显式允许尾部待完成调用，但不直接用于发请求。
- 图片只保存固化产物 ID、MIME 和内容摘要，不能靠可变 URI 重建已发送内容。此轮未实现真实图片准备或工具图片结果协议。
- 不透明供应商续传数据与可见正文分开；请求指纹包含它，不能压缩时丢掉后仍声称历史等价。
- 输入 JSON 由调用方给出字符预算和深度预算，另有 128 层解析安全上限；这是解析保护，不是已测设备容量。拒绝转义后重复键、非对象、非法数字和超预算数据。
- 数字原始 token 不经 Double；整数工具参数在类型、整数性和范围全部通过后才转换。布尔/字符串不隐式转数字。
- 当前参数 Schema 原型只支持整数、文本、布尔与 optional，生成的 Schema 与实际收窄同源。文本长度以 Unicode code point 计数；未知字段拒绝。嵌套/union 等完整 LG 工具 Schema 尚未实现，不宣称通用 JSON Schema 引擎。
- 请求构造冻结消息/工具集合，指纹规范对象键顺序，保留数组顺序、数字词法、模型修订、附件摘要与续传字段。拒绝无法无损 UTF-8 编码的孤立代理项，避免内容指纹碰撞。
- Token 的 actual/estimated/unknown 分开，unknown 不能伪装成 0；未实现费用估算与累计统计服务。

## 3. 请求与回执顺序

| 阶段 | 行为 | 失败或重复的处理 |
|---|---|---|
| 预留运行资源 | 检查 Project/Chat/Run 与真实 Agent lease | 不匹配/伪造/已停止均不得发起调用 |
| reserveRequest | 持久端口核对完整内容指纹 | completed 重放响应；conflict 拒绝；uncertain 要求核对 |
| markDispatched | prepared → in-flight 的持久 CAS | 仅获胜者可继续；竞争失败不发请求 |
| 再检查停止 | 持久化挂起后重查协程与运行门禁 | 可能留下保守的不确定记录，但不为了“看似可恢复”自动重发 |
| 模型调用 | 仅调用注入的共享 AgentModelPort | 没有 HTTP 客户端旁路，没有 400/SSE 自动付费回退 |
| 保存已知响应 | 在 NonCancellable 区域调用响应/用量原子提交端口 | 已收到响应而写入失败，保留响应对象供核对；不重新请求 |
| 响应可用性校验 | 验证历史身份、工具停止原因、截断、内容过滤与空答复 | 无效响应仍已计入回执，向调用方返回分类失败，不能拿截断调用执行工具 |
| 释放 | 释放自己持有的资源并结束自己的预览 | 竞争失败/回执重放不清掉另一条仍在进行的预览 |

发送标记不是收费证明。标记后进程死亡、网络失败或取消时，此原型保持不确定，不自作主张重试。
确认失败、请求恢复、输入命令去重、审批、公开历史/压缩与 Koog 策略检查点仍由后续任务完成。
已取消但同时收到了响应时，先保存实际回执，再传播取消，不返回一个可以继续执行工具的正常结果。

存储端口没有生产默认实现。生产适配器必须在应用记录事务中实现内容指纹核对、发送 CAS 和响应/用量一次写入，保存指纹格式版本，并限制 IO 时间；测试 FakeRequestStore 不可进入 app 主链路。
事件 sink 必须非阻塞并按完整请求身份处理预览；展示失败不重试付费请求。此轮没有 Compose 消费者。

## 4. 工具权限

注册表仅公开并执行 `READ_ONLY` 工具；PROJECT_WRITE、WORKSPACE_WRITE、PAID_REQUEST、AUTHENTICATION 在真实审批/回执路径缺失时全部拒绝。
模型说“已确认”不影响执行层判定。只读工具可重复查询变化后的事实，不迁入旧“同参数第二次一律熔断”的机制。
审批与变更批次的契约见 [APPROVAL_CONTRACT](APPROVAL_CONTRACT.md)：本轮已有端口和协调器原型，但存储、写入口与 UI 未接，注册表仍一律拒绝写入/付费/认证工具。
Kotlin 宿主 handler 是受信任的应用实现，不是脚本沙箱；脚本隔离仍属于 R03。

此轮未提供工程数据库写工具、假审批、联网或真实模型请求。工具失败只回传固定错误码与已知字段，不回显异常正文；取消继续抛出。

## 5. 外部证据与待验证

新增 JSON 依赖在 version catalog 声明 `kotlinx-serialization-json:1.9.0`，使用库自带 JSON 树 API，不为自己的类生成 serializer。
API 符号静态核对依据为该版本的官方 [Json](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/formats/json/commonMain/src/kotlinx/serialization/json/Json.kt)、[JsonElement](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/formats/json/commonMain/src/kotlinx/serialization/json/JsonElement.kt) 和 [builders](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/formats/json/commonMain/src/kotlinx/serialization/json/JsonElementBuilders.kt) 源码；不是已完成依赖解析或 Android 打包的证据。

Koog 1.3.0 本机缓存当前未找到，旧 API 审查是历史记录；未用新版网站示例猜测并提交 SDK 适配，也未为了绕过这一点改成自制 Agent 工具循环。
当前端口组合测试手动拼接一次合成请求—两个工具—第二次请求，只验证边界契约，不能充当 Koog functional strategy、typed tools、SDK 持久化或真机恢复验收。

仅 PC/获授权 CI 执行：

```sh
./gradlew :core:domain:test :core:engine:test :core:agent:testDebugUnitTest lintDebug assembleDebug
```

本机未实际构建、测试、Lint、安装、调用供应商或迁移用户数据。G1/G5 门槛仍未通过。
