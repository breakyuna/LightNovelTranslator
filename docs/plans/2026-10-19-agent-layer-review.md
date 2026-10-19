# Koog Agent 层审查报告（API 二次核实 + 测试覆盖 + 风险）

> 日期：2026-10-19 ｜ 性质：独立审查 ｜ 范围：`core/agent/koog/**` 主源码 + `app/src/test/.../core/agent/koog/**` 测试
> 说明：本审查在禁构建约束下完成，全部结论来自静态阅读 + 官方文档/GitHub tag 源码比对，**未经编译器验证**。

## 1. Koog 1.3.0 API 核对表

依据：Koog 官方文档（docs.koog.ai）+ GitHub `JetBrains/koog` tag 1.3.0 源码 + Maven Central `.module` 元数据。

| API | 代码使用位置 | 核实结论 |
|---|---|---|
| `PromptExecutor`（expect abstract class，需 override execute/executeStreaming/moderate/close） | `LntPromptExecutor.kt:49,108,117,122` | 四个 override 齐全；`models()` 有默认实现但仍显式 override 返回空表 |
| `execute(prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant` | `LntPromptExecutor.kt:49` | 签名一致；委托 `LlmGateway` 后转 `Message.Assistant` 并回填 token 用量 |
| `Message.Assistant(content, metaInfo: ResponseMetaInfo, finishReason)` | `LntPromptExecutor` 结果构造处 | 字段一致（total/input/outputTokensCount、modelId） |
| `AIAgent(promptExecutor, llmModel, strategy, toolRegistry, systemPrompt, …)` 顶层工厂 | `AgentSession` 组装处 | 1.3.0 为顶层函数形式（非 builder-only） |
| `functionalStrategy<String, AgentTurnResult>("lnt-agent-chat") { input -> … }` | `AgentTurnStrategy.kt:30-31` | receiver 为 `AIAgentFunctionalContext` |
| `llm.writeSession { requestLLM() }` / `requestLLM(message)` | `AgentTurnStrategy.kt:36-38` | 均存在于 1.3.0 |
| `HistoryCompressionStrategy.WholeHistory` + `compressHistory(strategy, preserveMemory = false)` | `AgentTurnStrategy.kt:130` | 存在；无能力门槛 |
| `LLModel` / `LLMProvider`（open class，可子类化承载 LNT provider） | `AgentModels.kt` | 1.3.0 中为 open class |
| `MissingToolsConversionStrategy.Missing` 默认行为 | 历史重建路径 | 默认只转换无定义的 Tool.Call/Result 部件；本设计历史全为 Text，透传无损 |
| 生命周期：每次 `run()` 新建 run session + context；`CancellationException` 原样上抛 | `AgentSession` 回合驱动 | 与 1.3.0 一致 |

**兼容性前提（已满足）**：koog-agents 1.3.0（含 1.2.0）的 `.module` 元数据要求 `kotlin-stdlib 2.3.10` + Kotlin metadata 2.3——项目已升级 kotlin=2.3.10、ksp=2.3.10、jvmTarget/compileOptions=17（JDK 17.0.20 本机已就绪）。Android 变体解析到 `koog-agents-android:1.3.0`。

## 2. 架构决策复核

1. **文本协议工具循环**（`AgentToolProtocol`）：因 `LlmGateway` 无原生 function calling（`LlmRequest` 无 tools 字段），采用「工具目录注入 system prompt → 模型输出 tool_call 块 → 编排层解析执行 → 结果以 tool_result 块送回」。**评判：在当前约束下是正确取舍**；解析宽容/渲染严格的不对称设计合理（格式错误作为可读回执让模型自我修正，有测试覆盖 invalid_tool_call 路径）。
2. **取消边界**：复用 `TranslationControlSignal`；已发请求自然结束、不发起新付费请求——与翻译引擎语义一致。
3. **持久化**：`RoomAgentSessionStore` 复用 v5 的 agent_sessions/agent_messages 表；跨回合历史按 `maxHistoryTurns` 截断后灌入。

## 3. 测试覆盖（已交付，未执行）

| 文件 | 测试数 | 覆盖 |
|---|---|---|
| `AgentToolProtocolTest` | 31 | 协议解析/渲染、围栏/坏 JSON/未知工具回退 |
| `AgentToolHostTest` | 19 | 9 个工具的参数校验、错误归一、顺序执行 |
| `LntPromptExecutorTest` | 17 | Prompt 展平、LlmResult→Assistant 转换、token 预算截断、失败路径 |
| `AgentSessionStoreTest` | 9 | 持久化存取与历史重建顺序（内存 fake） |
| `AgentTurnTruncateTest` | 9 | 历史截断/保留策略 |
| `AgentSessionTest` | 8 | 回合驱动、轮次上限（取消/用量累计用例未补完——低优先级缺口） |

**缺口**：`AgentSessionTest` 原计划的 PART5（取消时不产生新 LLM 调用、用量累计）未写完；建议首次构建通过后补（不影响编译，文件已闭合）。

## 4. 不可测点（需真机/集成验证）

1. Koog 内部压缩触发时机（`compressHistory` 是付费请求，需观察真实上下文增长）。
2. `ask_user` 的 UI 挂起路径（宿主接线已完成，需真机点验）。
3. `assets/agent/system-prompt.md` 的加载（已补 `promptAssetLoader` 接线，需真机确认系统提示词生效）。

## 5. 给后续阶段的风险提示

1. **Koog 传递依赖**：Ktor（Android 端 okhttp 引擎）可能顶高 OkHttp 4.10.0——首次构建若报重复类/版本冲突，在 app 加 resolutionStrategy 或接受升级后回归网络层。
2. **包体积**：koog-agents 聚合包带入 mcp/embeddings/rag/opentelemetry 等模块（LNT 未用）；release 未开 minify，后续可用 R8 规则裁剪。
3. **工具目录漂移**：`AgentToolHost` 增删方法时需同步其 `toolSpecs()` 默认实现。
4. **provider 解析**：会话启动时解析默认 provider；用户切换 provider 对已存在会话不热更新（设计取舍，KDoc 已说明）。

## 6. 结论

Koog 1.3.0 的 API 使用与官方源码一致，未发现虚构 API；架构决策在当前约束下合理；测试覆盖协议/工具/执行器/持久化主干，取消与用量两个用例待补。**可以进入首次构建验证**（见 `2026-10-19-build-readiness-checklist.md`）。
