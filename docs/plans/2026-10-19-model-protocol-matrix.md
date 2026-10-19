# 主流模型工具调用协议与参数矩阵（LNT AI 助手层调研）

> 日期：2026-10-19 ｜ 性质：只读调研 ｜ 范围：OpenAI 兼容 / Anthropic / Gemini 三协议的 tools 与思考强度参数，以及 LNT 用户最可能配置的"便宜模型"端点（DeepSeek、Qwen/DashScope、GLM、Kimi、MiniMax、StepFun、豆包、vLLM/LM Studio/Ollama 自托管）。
> 方法：全部结论来自 2026-10 前后的官方文档（api-docs.deepseek.com、help.aliyun.com、docs.bigmodel.cn、platform.kimi.com、platform.stepfun.com、volcengine.com/docs/ark、docs.vllm.ai、docs.ollama.com、platform.claude.com、ai.google.dev、developers.openai.com）与可公开检索的厂商变更日志/社区 issue。**未能从官方渠道核实的条目一律标注"待验证"，不做猜测。**
> 关联代码：`app/src/main/java/com/breakyuna/noveltranslator/core/llm/LlmClient.kt`（三协议实现、`reasoningEffort` 已有雏形映射）、`core/agent/koog/**`（Koog 1.3.0 Agent 层，当前为文本协议工具循环，见 `docs/plans/2026-10-19-agent-layer-review.md`）。

---

## 0. 对 LNT 的实现建议（摘要）

### 0.1 分层策略：原生 tools 优先 + 文本协议兜底，按 provider 能力开关路由

```
AgentToolHost（9 个工具的唯一事实源）
   ├─ 能力开关层 ProviderToolCapabilities（每个 ApiProviderEntity 一套，可手改）
   │    ├─ nativeTools=true   → 走 LlmGateway 原生 tools 通道（本调研的形状）
   │    └─ nativeTools=false  → 走现有 AgentToolProtocol 文本协议（保持不变）
   └─ 运行时降级：原生调用返回 400（参数/协议不被接受）或 tool_calls 解析失败
        → 自动回落文本协议重试一次（与 LlmClient 现有的 reasoning_effort 400 回退同模式）
```

理由：

1. **2026 年现状：主流便宜模型端点全部支持 OpenAI 兼容原生 tools**（见 §2 矩阵），原生通道有 `tool_choice`、并行调用、服务端 schema 约束（strict）等文本协议没有的保证，长会话下 token 效率也更高（工具定义注入 system message 由服务端模板化，见 Anthropic 计费说明）。
2. **但"支持"与"可靠"是两回事**：便宜/自托管模型在原生 tools 下的失败模式（参数格式错、tool_calls 混进 reasoning_content、并行调用丢失）在公开 issue 里大量存在（§5）。文本协议的价值在于**失败时可读回执、模型可自我修正**，这是现有 `AgentToolProtocol` 已经设计好的（解析宽容/渲染严格）。
3. 两者不是二选一：**同一套工具定义双轨渲染**——原生轨渲染成各协议的 tools 数组，文本轨渲染成 system prompt 工具目录。`AgentToolHost.toolSpecs()` 已经提供单一事实源，扩展成本低。

### 0.2 per-provider 能力开关设计建议

在 `ApiProviderEntity`（或派生配置）上增加一组能力字段，默认值按 provider 类型猜、用户可覆盖：

| 开关 | 取值 | 作用 |
|---|---|---|
| `toolProtocol` | `NATIVE_OPENAI` / `NATIVE_ANTHROPIC` / `NATIVE_GEMINI` / `TEXT` / `AUTO` | 决定序列化器；`AUTO`=按 providerType 推断，失败降级 TEXT |
| `toolChoiceSupport` | `AUTO_ONLY` / `AUTO_NONE` / `FULL` | 是否允许发 `required`/指定函数（Qwen 系不支持 required；Kimi K2 系只支持 auto/none；GLM 官方称仅 auto） |
| `parallelToolCalls` | `DEFAULT_ON` / `NEEDS_FLAG` / `UNSUPPORTED` | Qwen 默认单调用、需显式 `parallel_tool_calls=true`；vLLM/Llama3 自托管常不支持并行 |
| `thinkingStyle` | 见 §0.3 映射表 | 思考强度如何落地 |
| `temperatureLocked` | Boolean | Kimi K3/K2.7 固定 temperature=1.0，**传入其他值直接 400**；DeepSeek thinking 模式下静默忽略。为 true 时干脆不发 temperature |
| `maxTokensParam` | `max_tokens` / `max_completion_tokens` | 按模型而非按 URL 猜测（现 `LlmClient.kt:375` 用 `api.openai.com + o*/gpt-5*` 启发式，建议改为开关） |
| `reasoningEchoRequired` | Boolean | 为 true 时，**带 tools 的后续请求必须原样回传上一轮 `reasoning_content`/thinking 块**（DeepSeek thinking+tools、Kimi 全系、MiniMax 交错思考、Qwen3.8-max preserve_thinking 默认 true）。这对 Room 持久化是硬需求：`agent_messages` 需增列存 reasoning 内容，否则多轮工具循环直接 400 或质量塌陷 |

**关键落地项（超出协议本身）**：`reasoningEchoRequired=true` 的 provider（DeepSeek/Kimi/MiniMax/新 Qwen）要求 agent 历史里保存并回传思考内容。当前 Koog 历史全是 Text（`2026-10-19-agent-layer-review.md` 记录的设计取舍），接入原生 tools 时必须先补这条持久化，否则这些模型的多轮工具调用会以 400 收场。

### 0.3 思考强度参数在各协议下的映射表

LNT 内部统一四档：`off / low / medium / high`（与现有 `reasoningEffort` 字符串对齐）。各协议落地方式：

| LNT 档位 | OpenAI 兼容（官方） | DeepSeek（V4.1-Flash/flash） | Qwen/DashScope | 智谱 GLM | Kimi | MiniMax | StepFun | 豆包 | Anthropic | Gemini（generateContent） |
|---|---|---|---|---|---|---|---|---|---|---|
| `off` | 不发送 reasoning_effort（GPT-5.6+ 可发 `"none"`，但兼容性差，建议不发） | `extra_body: {"thinking":{"type":"disabled"}}` | `enable_thinking:false` | `thinking:{"type":"disabled"}`（4.5+；5.x 部分模型不支持关闭，待验证） | K3 无法关闭（常开）；K2 系 `thinking:{"type":"disabled"}` | `enable_thinking:false` | 无关闭档（待验证；step-3.5-flash-2603 只有 low/high） | 关闭深度思考（官方建议函数调用场景关闭） | `thinking:{"type":"disabled"}`（4.x）；5.x 常开自适应，用 `output_config.effort:"low"` 近似 | `thinkingConfig.thinkingBudget:0`（2.5 系有效） |
| `low` | `reasoning_effort:"minimal"` 或 `"low"` | `reasoning_effort:"low"` | `enable_thinking:true`（无 effort 档位） | `reasoning_effort:"low"`（5.2+；5.2 上 low→实际 high） | `reasoning_effort:"low"`（K3） | `enable_thinking:true` | `reasoning_effort:"low"` | 思考默认档 | `output_config.effort:"low"`（自适应）；手动模式 `thinking.budget_tokens:1024` | `thinkingBudget:1024` 或 `thinkingLevel:"low"` |
| `medium` | `reasoning_effort:"medium"` | `reasoning_effort:"high"`（**官方把 medium 映射为 high**，无等价中档） | 同上 | `reasoning_effort:"medium"`（5.2 映射为 high；5.3 不支持 medium，用 high） | `reasoning_effort:"high"`（K3 只有 low/high/max） | 同上 | `reasoning_effort:"medium"` | 默认档 | `output_config.effort:"medium"`（Opus 5.5/Haiku 5.5 默认值） | `thinkingBudget:4096` 或默认（2.5 系默认开启） |
| `high` | `reasoning_effort:"high"`（xhigh/max 仅新模型） | `reasoning_effort:"max"`（或 high） | 同上 | `reasoning_effort:"max"`（5.3 支持） | `reasoning_effort:"max"`（K3 默认） | 同上 | `reasoning_effort:"high"` | 深度思考档 | `output_config.effort:"high"`（Sonnet 4.6 默认） | `thinkingBudget:8192` 或 `thinkingLevel:"high"` |

配套注意：

- **temperature 与思考互斥/锁定**：DeepSeek thinking 模式静默忽略 `temperature/presence_penalty/frequency_penalty`（不报错，最坑）；Kimi K3/K2.7-Code 直接 400；Gemini 3 官方建议保持 1.0。能力开关里带上 `temperatureLocked`。
- **thinking 内容回传**：映射表只管"发什么"，不管"回什么"。DeepSeek 在**带 tools 的请求**里必须回传全部历史 `reasoning_content`（否则 400，官方明示）；Kimi 全系要求原样回传完整 assistant message；MiniMax M3 交错思考（模型在工具调用之间输出 think 标签思考）同样必须回传。
- **Anthropic 4.7+  Breaking change**：`thinking.type:"enabled" + budget_tokens` 在 Claude 4.7 及以后（Opus 5.5、Sonnet 5.5、Haiku 5.5）**直接 400**；4.6 弃用但可用。LNT 现有代码只对 claude-3.7/4 发 `thinking:{type:disabled}`，建议改为按模型代次发 `adaptive + output_config.effort`，并保留 400 回退。
- **OpenAI Chat Completions 的边界**：GPT-6 Astra / GPT-6.1 Sol 的工具调用**只支持 Responses API**；Chat Completions 示例已改用 GPT-5.6 Terra。LNT 的 OpenAI 兼容通道继续用 Chat Completions，等价于把模型范围限在 GPT-5.x 及兼容端点——对本应用（用户自配便宜模型）是可接受取舍，但应在文档/UI 注明。

---

## 1. OpenAI 兼容 tools 协议（以 Chat Completions 为准）

### 1.1 请求体

```jsonc
{
  "model": "...",
  "messages": [ ... ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Retrieves current weather for the given location.",
        "parameters": {                     // JSON Schema（draft 2020-12 子集）
          "type": "object",
          "properties": { "location": {"type":"string","description":"City and country"} },
          "required": ["location"],
          "additionalProperties": false      // strict 模式必需
        },
        "strict": true                       // 可选；OpenAI 建议默认开
      }
    }
  ],
  "tool_choice": "auto",                     // "auto"(默认) | "none" | "required" | {"type":"function","function":{"name":"..."}} | {"type":"allowed_tools","mode":"auto","tools":[{"type":"function","name":"..."}]}
  "parallel_tool_calls": true                // 可选；false = 最多一个工具调用
}
```

要点（[OpenAI Function Calling 指南](https://developers.openai.com/api/docs/guides/function-calling)）：

- **Responses API 与 Chat Completions 的工具定义形状不同**：Responses 把 `name/description/parameters/strict` 平铺在 tool 对象上；Chat Completions 包一层 `function`。DeepSeek/StepFun/Qwen/GLM/Kimi/MiniMax/豆包/vLLM/Ollama 全部跟随 Chat Completions 形状。
- `strict:true` 用结构化输出保证参数合 schema，但要求**每个 object `additionalProperties:false` 且所有 properties 都进 `required`**（可选字段用 `"type":["string","null"]` 表达）。不满足会 400。
- `tool_choice:"allowed_tools"` 是 2026 年新增形态：限制模型只能从 tools 的子集里选（节省 prompt cache 场景有用）。
- 工具定义计入输入 token（官方：functions 被注入 system message）；工具多时官方建议单轮可用函数 <20 个，或用 tool_search（仅 gpt-5.4+，LNT 用不到）。
- `parallel_tool_calls:false` 保证 0 或 1 个调用；GPT-5 及以后在同时带内置工具时才支持并行函数调用。**注意：社区反馈 o3/4o 等老模型可能不识别 `parallel_tool_calls` 参数而 400**（[社区讨论](https://community.openai.com/t/what-models-support-parallel-tool-calls-and-when-to-use-it/1310788)）——按能力开关发送。

### 1.2 响应（非流式）

```jsonc
"choices": [{
  "index": 0,
  "message": {
    "role": "assistant",
    "content": "",                                   // 可能是 "" 或 null
    "tool_calls": [
      { "id": "call_abc123",                          // 回传时作为 tool_call_id
        "type": "function",
        "index": 0,                                   // 并行调用时递增
        "function": { "name": "get_weather", "arguments": "{\"location\":\"Paris\"}" } }
    ]
  },
  "finish_reason": "tool_calls"                       // 注意：StepFun 实际返回 "stop"（见 §2）
}]
```

- **`function.arguments` 是字符串化的 JSON**，必须 `JSONObject(arguments)` 解析；空参数时是 `"{}"`。
- 回传：assistant 消息原样入 history（含 tool_calls），每个调用追加 `{"role":"tool","tool_call_id":"...","content":"结果"}`。结果通常是字符串（JSON 或纯文本），无返回值时返回 `"success"` 之类的状态串。

### 1.3 流式增量拼接

每个 chunk 的 `choices[0].delta.tool_calls[]`：

- **首个 chunk**：`{"index":0,"id":"call_...","type":"function","function":{"name":"get_weather","arguments":""}}`（id+name 只出现一次）；
- **后续 chunk**：同 `index`，只有 `function.arguments` 的**字符串片段**（可能按字符/片段切分，甚至一个 JSON 引号一个 chunk）；
- 结束：`finish_reason:"tool_calls"`（或 "stop"）。

聚合算法：以 `index` 为 key 建 map；见 name 则记录；见 arguments 片段则 `sb.append`；最终 `JSONObject(sb.toString())`。（OpenAI 官方 Responses API 的等价事件是 `response.output_item.added` + `response.function_call_arguments.delta`，聚合逻辑相同；社区与 Kimi/DeepSeek 文档均给出 Chat Completions 版accumulator 示例。）

### 1.4 reasoning_effort（OpenAI 自家）

| 模型族 | 支持值 | 备注 |
|---|---|---|
| o1（不含 o1-mini） | low / medium / high | o1-mini 完全不支持 reasoning_effort |
| o3 / o3-mini / o4-mini | low / medium / high | |
| GPT-5 | minimal / low / medium(默认) / high | minimal 为 GPT-5 新增 |
| GPT-5.1 ～ Codex Max | none / low / medium / high / xhigh | none 起自 GPT-5.1 |
| GPT-5.1 Codex Max 之后 | none / low / medium / high / xhigh（Responses 还有 max） | 值集合随模型扩张，[官方 reasoning 指南](https://developers.openai.com/api/docs/guides/reasoning)明示"model-dependent" |
| GPT-6.x | Responses API：none/minimal/low/medium/high/xhigh/max，每模型子集不同（GPT-6.1 Sol 默认 medium 且不接受 none/minimal） | GPT-6 Astra/Sol 的工具调用仅 Responses API |
| gpt-oss（开源权重） | low / medium / high | 也可用 system message 指定 effort |
| 非推理模型（gpt-5-chat-latest 等） | 不支持，传参报错 | |

对 LNT 的含义：`reasoning_effort` 只能在确认是推理模型时发；`LlmClient.kt` 现有的"按模型名猜 + 400 回退"可以保留，但建议把结果沉淀为 provider 能力字段（用户配一次，避免每次试探性请求）。另注意 GPT-5.2 起 temperature/top_p 重新可用（此前 GPT-5/5.1 社区反馈不支持，[社区矩阵请求帖](https://community.openai.com/t/request-for-compatibility-matrix-reasoning-effort-sampling-parameters-across-gpt-5-series/1371738)），所以 temperature 也要按模型开关。

---

## 2. 各便宜模型端点：tools 支持与坑（2026-10 现状）

总表（★=需要注意的坑）：

| 端点 | 原生 tools | tool_choice | 并行调用 | 思考参数 | 典型 max output | 主要坑 |
|---|---|---|---|---|---|---|
| DeepSeek `deepseek-flash`（V4.1-Flash） | ✓ | auto/none/指定函数；strict（beta，`/beta` base_url） | ✓（tool_calls 数组） | `{"thinking":{"type":"enabled/disabled"}}` + `reasoning_effort`(low/high/max) | **384K**（上下文 1M） | ★thinking 默认开且静默忽略 temperature；★带 tools 时必须回传全部 reasoning_content 否则 400；medium→high 映射 |
| DeepSeek `deepseek-v4-pro` | ✓ | 同上 | ✓ | 同上 | 384K | ★2026-09-14 起 `deepseek-v4-pro` 请求被路由到 V4.1-Flash 计费；`deepseek-chat`/`deepseek-reasoner` 已于 2026-07-24 退役 |
| Qwen / DashScope 兼容模式（qwen-plus/flash/max 3.5–3.8） | ✓（全系，含开源权重） | auto/none/指定函数；**不支持 required** | **需显式 `parallel_tool_calls=true`**（默认单调用） | `enable_thinking:true/false` + `reasoning_content` 回传（`preserve_thinking`，qwen3.8-max 默认 true） | qwen3.7-plus：131K（上下文 1M） | ★思考模式模型不支持强制指定工具；★百炼上的 GLM 必须传 `tool_stream:true` 否则不返回 tool_calls（glm-5.3 起默认 true） |
| 智谱 GLM（glm-4.6/4.7/5.x；glm-4-flash/air） | ✓ | 官方文档：**默认且仅支持 auto** | 流式需 `tool_stream=true` 按 index 拼 arguments | `thinking:{"type":"enabled/disabled"}`（4.5+）；5.2+ 增加 `reasoning_effort` | 4.6/4.7/5.x：128K（131072）；4.5：96K；**glm-4-plus 仅 4K**；glm-4-flash 128K 上下文、输出 4K~16K（快照不一，待验证） | ★tool_choice 面很窄；★5.2 的 low/medium 都映射为 high；5.3 只认 low/high/max；★temperature 默认 1.0（5.x/4.6/4.7） |
| Moonshot Kimi（kimi-k3 / k2.6 / k2.7-code） | ✓ | kimi-k3：auto/none/**required**（K3 新增）；K2 系：仅 auto/none | ✓（多调用一轮返回） | K3：`reasoning_effort`(low/high/max，默认 max)，**thinking 常开不可关**；K2 系：`thinking` 开关 | K3：`max_completion_tokens` 默认 131072、最大 1048576（上下文 1M）；K2.x：256K 上下文，默认输出 32K（待验证） | ★**temperature=1.0/top_p=0.95/n=1/penalties=0 固定，传其他值 400**；★必须原样回传完整 assistant message（reasoning_content）；★K3 不接受 K2 式 `thinking` 参数 |
| MiniMax（M3 / M2.5） | ✓（OpenAI 兼容 tools/tool_choice；另有 Anthropic、Gemini 兼容端点） | OpenAI 兼容语义 | ✓ | `enable_thinking`（默认 true） | M3：524288（上下文 1M） | ★交错思考（think 标签）必须每轮回传，剥掉即多轮退化（官方文档要求 + 社区头号集成 bug） |
| StepFun（step-5-preview / step-3.7-flash / step-3.5-flash） | ✓ | OpenAI 兼容（文档未单列限制） | 文档未明示（待验证） | `reasoning_effort`(low/medium/high；step-3.5-flash-2603 仅 low/high)；`reasoning_format:general/deepseek-style` | `max_tokens` 默认 INF（不限），受上下文约束；1M 上下文 | ★**finish_reason 文档自相矛盾**：tool-call 示例返回 `"stop"`，参数参考却列了 `"tool_calls"`——必须以 `tool_calls` 是否存在判断；★step-5-preview 不支持内置 web_search 工具；★函数名 ≤64 且须匹配 `^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$`；★自托管 Step3-VL 有过 hermes parser 解析失败 issue |
| 豆包 Doubao（方舟，doubao-seed-2-1-pro 等） | ✓（Chat API + Responses API） | OpenAI 兼容 | **`parallel_tool_calls` 默认 true**（false 时限制为最多一个，部分模型支持该开关） | 深度思考开关（Responses API：`thinking:{"type":"disabled"}`）；**官方建议函数调用场景关闭 thinking 提效** | 256K 上下文；最大输出以模型列表为准（待验证） | ★finish_reason 用 `"tool_calls"`；★方舟同时托管 DeepSeek-V4/GLM-5.1/Kimi-K2.5 等三方模型，能力按实际模型变 |
| vLLM 自托管 | ✓（模型+parser 依赖） | auto/none/required(≥0.8.3)/指定函数 | parser 相关（Llama3 不支持、Llama4 支持；mimo 支持 parallel_tool_calls=false） | `--reasoning-parser`（模型相关）；gpt-oss 用 system message 指定 effort | 取决于 `--max-model-len` | ★**不传 `--enable-auto-tool-choice` + `--tool-call-parser` 时 `tool_choice:"auto"` 直接 400**；★named/required 走结构化输出，首次请求有 FSM 编译延迟；★parser 不匹配是静默失败（tool_calls 为空）；★`hf` parser 拒绝 strict/required/named/parallel=false |
| LM Studio（/v1/chat/completions） | ✓（模型/模板依赖；0.3.x 起持续修模板） | OpenAI 兼容 | 模型依赖 | reasoning content 在 `choices.message.reasoning`；Responses 端点支持 `reasoning.effort`（gpt-oss-20b） | 模型依赖 | ★工具名会被 normalize 成 snake_case；★工具解析依赖 chat template，老模板静默失效 |
| Ollama（/v1/chat/completions 或原生 /api/chat） | ✓（模型依赖，需工具能力模型+模板） | OpenAI 兼容端点支持；`think` 开关仅在原生 API（待验证） | 官方文档有并行工具调用示例 | 原生 API `think:true/false`；思考模型有 reasoning summaries | 模型依赖 | ★OpenAI 兼容端点是"子集"实现，高级参数（tool_choice 细粒度、reasoning）支持有限 |

### 2.1 DeepSeek（重点，LNT 用户高频）

- 2026 现状：`deepseek-chat`/`deepseek-reasoner` **已于 2026-07-24 退役**；现役为 `deepseek-flash`（= DeepSeek-V4.1-Flash，552B MoE，1M 上下文，最大输出 384K）与 `deepseek-v4-pro`（**2026-09-14 起请求被路由到 V4.1-Flash 计费**）。base_url 不变，另提供 Anthropic 格式端点。
- tools：OpenAI 兼容，`message.tool_calls` + `role:"tool"` 回传；**thinking 模式自 V3.2 起支持 tools**（V3.1 及更早两者互斥，网上旧教程仍在传播，勿采信）。
- 思考控制：`extra_body={"thinking":{"type":"enabled/disabled"}}`（默认 enabled、默认 effort high）；`reasoning_effort` 取 low/high/max，**用户请求值按官方表映射：minimal→low, low→low, medium→high, high→high, xhigh→max, max→max**。
- 坑 1：**thinking 模式下 temperature/presence_penalty/frequency_penalty 被静默忽略**（不报错）；`top_p` 行为还分模型（flash 只在 thinking 模式生效且有效域 0.95–1.0；v4-pro thinking 模式下固定 0.95/1.0）。
- 坑 2（对 agent 层最关键）：**带 tools 的请求，后续每一轮都必须完整回传历史 `reasoning_content`，哪怕某轮没有工具调用，否则 API 返回 400**。官方示例即把整个 `response.choices[0].message`（含 reasoning_content、tool_calls）直接 append。
- 坑 3：Chat Completion API **不支持在对话中段插入非模型生成的 tool_call**（Anthropic/Responses 格式才支持）——agent 回放历史时注意。
- `strict` 模式（beta）：`base_url=https://api.deepseek.com/beta` + 每个 function `strict:true`；schema 要求同 OpenAI strict（全 required + additionalProperties:false）；支持 object/string/number/integer/boolean/array/enum/anyOf/$ref，**不支持 minLength/maxLength/minItems/maxItems**。

来源：[Tool Calls](https://api-docs.deepseek.com/guides/tool_calls)、[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)、[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)、[Change Log](https://api-docs.deepseek.com/updates)、[迁移指南（第三方整理）](https://wavespeed.ai/blog/posts/blog-deepseek-v4-model-name-migration)。

### 2.2 阿里 DashScope / Qwen

- tools 全系支持（Qwen Max/Plus/Flash/Coder/Turbo、Qwen2.5/3/3.5/3.6/3.8 开源系、VL/Omni），并托管 DeepSeek/GLM/Kimi/MiniMax 三方模型。
- 响应：`tool_calls[{id:"call_...", function:{name, arguments(字符串JSON)}, type, index}]`；无调用时为 `null`（不是空数组）。
- **并行调用需显式 `parallel_tool_calls=true`**：默认行为一轮只返回一个调用，官方文档明确将此作为进阶用法。
- **`tool_choice` 不支持 `"required"`**（官方：非思考模式下无法保证、思考模式下不支持）；支持 auto/none/指定函数；**思考模式模型不支持强制指定工具**。
- 思考：`enable_thinking`（OpenAI SDK 里放 extra_body 或顶层均可）；`reasoning_content` 随响应返回；`preserve_thinking` 控制历史 reasoning_content 是否拼接（qwen3.8-max 默认 true 且**必须回传全部 reasoning_content，不能拼进 content**）。
- 容量：qwen3.7-plus 上下文 1M、最大输出 131K（思考模式同）、思维链上限 262K；qwen3.8-max/flash 已在百炼上架（具体上限待验证）。
- **百炼托管 GLM 的专属坑**：调用 GLM 系 Function Calling 必须 `extra_body={"tool_stream": True}`，否则**不返回 tool_calls**（glm-5.3 起默认 true）。
- 百度/腾讯云等第三方通道的 GLM/Qwen 兼容层各有裁剪（如腾讯 TokenHub 的 GLM 指南），以通道文档为准。

来源：[Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling)、[DashScope API 参考](https://www.alibabacloud.com/help/zh/model-studio/qwen-api-via-dashscope)、[深度思考模型用法](https://help.aliyun.com/zh/model-studio/deep-thinking)、[qwen3.7-plus 模型页](https://www.qianwenai.com/models/qwen3.7-plus-2026-05-26)。

### 2.3 智谱 GLM

- 现役：glm-4.6/4.7（200K 上下文、128K 输出）、glm-5/5.1/5.2/5.3(+flash/flashx)（200K、131072 输出）、免费 glm-4.5-flash（128K/96K，混合思考）；老 glm-4 系：plus 128K/4K、air 128K/16K、flash 免费（128K，输出 4K~16K，快照间不一致，**待验证**）。
- tools：OpenAI 标准协议；**官方能力页称 `tool_choice` "默认且仅支持 auto"**——设计 LNT 能力开关时按 `AUTO_ONLY` 处理（第三方通道可能放宽，需实测）。
- 流式工具调用：`tool_stream=true` 时 `delta.tool_calls[].function.arguments` 分片返回，按 `index` 聚合（GLM-4.6 迁移清单明示）。
- 思考：`thinking:{"type":"enabled/disabled"}`（GLM-4.5+；4.6/4.7 默认开）；GLM-5.2+ 增加 `reasoning_effort`：5.2 上 none/minimal=关闭思考、low/medium→high、xhigh→max；**GLM-5.3/5.3-Flash 只支持 low/high/max**。`clear_thinking` 建议设 false。
- temperature：5.x/4.7/4.6 默认 1.0（GLM-4.5 默认 0.6，GLM-4 默认 0.75），取值范围 (0,1]。
- 默认 max_tokens 65536，最大 131072（4.5 为 98304）。

来源：[工具调用](https://docs.bigmodel.cn/cn/guide/capabilities/function-calling)、[核心参数](https://docs.bigmodel.cn/cn/guide/start/concept-param)、[迁移至 GLM-4.6](https://docs.bigmodel.cn/cn/guide/start/migrate-to-glm-4.6)、[GLM-4 模型页](https://docs.bigmodel.cn/cn/guide/models/text/glm-4)。

### 2.4 Moonshot Kimi

- 现役：kimi-k3（2026-07-16 发布，2.8T MoE/104B 激活、1M 上下文、原生视觉）、kimi-k2.7-code、kimi-k2.6。
- tools：OpenAI 兼容；多轮循环官方示例（while finish_reason=="tool_calls"）；**K3 新增 `tool_choice:"required"`**（K2 系只有 auto/none；指定函数路径官方标注与思考不兼容）。
- 采样参数**锁定**：K3/K2.7-Code 的 `temperature=1.0、top_p=0.95、n=1、presence_penalty=0、frequency_penalty=0` 固定，**传其他值报错，建议根本不传**（K2.6 为 thinking 时 1.0/非思考 0.6）。这是"客户端默认 temperature=0" harness 的头号 400 来源。
- 思考：K3 常开、用顶层 `reasoning_effort`（low/high/max，默认 max），**不接受 K2 式 `thinking` 参数**；K2 系用 `thinking` 开关。`max_completion_tokens` 默认 131072、最大 1048576。
- 历史回传：多轮与工具场景**必须原样回传完整 assistant message**（含 reasoning_content），剥掉即报错或退化。
- 视觉输入不支持公网 URL（base64 或 `ms://`）；联网搜索官方提示近期勿用于生产。
- 自托管：vLLM `--tool-call-parser kimi_k2 --reasoning-parser kimi_k2`。

来源：[Kimi K3 Quickstart](https://platform.kimi.com/docs/guide/kimi-k3-quickstart)、[K3 迁移指南（TheRouter）](https://therouter.ai/blog/kimi-k3-api-integration-guide)、[K2.7 Code 解析（aimlapi）](https://aimlapi.com/blog/kimi-k2-7-code-the-complete-guide-to-moonshot-ais-new-open-weight-coding-model)、[Kimi-K2 tool_call_guidance](https://github.com/MoonshotAI/Kimi-K2/blob/main/docs/tool_call_guidance.md)。

### 2.5 MiniMax

- 现役：MiniMax-M3（428B MoE，1M 上下文，最大输出 524288，文本+图像+视频输入，2026-06 发布）、M2.5（ legacy）、M2.7。
- 端点齐全：`/v1/chat/completions`（OpenAI 兼容）、`/v1/responses`、`/v1/messages`（Anthropic 兼容）、`/v1beta/models/minimax-m3:generateContent`（Gemini 兼容）——LNT 若接 MiniMax 官方端点，甚至可以直接复用现有 Anthropic/Gemini 序列化器（需实测保真度）。
- tools：OpenAI 兼容 tools/tool_choice；`enable_thinking` 默认 true；结构化输出 `response_format`（JSON schema）。
- 坑：**交错思考必须回传**——M3 在工具调用之间以 think 标签思考，官方要求每轮把 reasoning 喂回去，剥掉会让多轮工具使用退化（被社区称为"M3 集成头号 bug，M2 遗传"）。

来源：[MiniMax M3 解析（Morph）](https://www.morphllm.com/minimax-m3)、[M2.5 tool_calling_guide](https://github.com/MiniMax-AI/MiniMax-M2.5/blob/main/docs/tool_calling_guide.md)。

### 2.6 StepFun（阶跃星辰）

- 现役：`step-5-preview`（2026-09-20 发布，1M 上下文，图/视频输入，工具调用+结构化输出+推理强度；权重 2026-10-15 开源）、`step-3.7-flash`、`step-3.5-flash`（vLLM 有专用 `step3p5` parser）。老 step-1/step-2 系已被产品线替换（step-1o-turbo-vision 等仍列"其他兼容"）。
- tools：OpenAI 兼容；响应 `tool_calls[{id:"call_...", type:"function", function:{name, arguments}}]`；结果以 `role:"tool"` + `tool_call_id` 回传。
- **格式服从性坑**：
  1. **finish_reason 不一致**：工具调用文档示例返回 `"finish_reason":"stop"`，而参数参考列了 `tool_calls` 取值。**实现时必须以 `tool_calls` 非空判断，不要依赖 finish_reason。**
  2. 函数名约束 `^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$`（≤64 字符）——LNT 工具名（如 `translate_segment`）满足，但动态生成名前要校验。
  3. `step-5-preview` 的 Chat Completions **不支持内置 `web_search` 工具**（要搜索走独立 `/v1/search`）。
  4. 自托管 Step3-VL-10B 在 vLLM 上有 hermes parser 解析 tool call 失败的历史 issue（[HF 讨论](https://huggingface.co/stepfun-ai/Step3-VL-10B/discussions/19)）；3.5/3.7 Flash 请用官方 `step3p5` parser。
- 思考：`reasoning_effort` low/medium/high（step-3.5-flash-2603 仅 low/high）；思考内容双字段返回（`reasoning` 与 DeepSeek 风格 `reasoning_content`，由 `reasoning_format` 控制）；`usage.completion_tokens_details.reasoning_tokens` 可分账。
- 容量：`max_tokens` 默认 INF（不限制），温度默认 0.5（0–2），top_p 默认 0.9。

来源：[工具调用](https://platform.stepfun.com/docs/zh/api-reference/tool-call)、[Chat Completions 参数](https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create)、[Step 5 Preview 发布](https://www.stepfun.com/news/step-5-preview)。

### 2.7 字节豆包 Doubao（火山方舟）

- tools：Chat API 与 Responses API 均支持；`finish_reason:"tool_calls"`；并行 `parallel_tool_calls` 默认 true（false 限制最多一个，"对于支持并行工具调用控制的模型"）。
- **官方优化建议：函数调用场景关闭深度思考可提高效率**（方舟 Function Calling 文档"推荐配置"第一条相关）。
- 模型面：doubao-seed-2-1-pro-260915（256K 上下文）等；方舟同时托管 DeepSeek-V4.1-Flash、GLM-4.7/5.1、Kimi-K2.5 等三方模型（Coding Plan 含 ark-code-latest 自动路由）——用户选"豆包端点"时实际模型能力可能完全不同，能力开关要允许按模型覆盖。
- 提示词规范：官方错误示例表强调函数命名/描述质量（反例 `GPT1`），与 OpenAI 最佳实践一致。
- 最大输出 token：256K 上下文明确；输出上限以[模型列表](https://www.volcengine.com/docs/82379/1554679)为准（**待验证**）。

来源：[Function Calling](https://www.volcengine.com/docs/ark/function-calling)、[工具概述](https://www.volcengine.com/docs/ark/tools-overview)。

### 2.8 自托管：vLLM / LM Studio / Ollama

**vLLM**（当前文档，2026-10）：

- 服务端硬性要求：`--enable-auto-tool-choice` + `--tool-call-parser <name>`（+ 可选 `--tool-parser-plugin`、`--chat-template`）。**缺任一项，客户端发 `tool_choice:"auto"` 直接 400**（"'auto' tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set"）。
- parser 与模型对应：`llama3_json`（Llama 3.1/3.2/4）、`hermes`（Hermes、Qwen2.5）、`mistral`、`deepseek_v3`/`deepseek_v31`、`kimi_k2`、`glm45`/`glm47`、`openai`（gpt-oss）、`step3p5`（Step-3.5/3.7）、`qwen3_xml`（Qwen3-Coder）、`mimo`、`granite`、`xlam`、`pythonic`、`hf`/`beam`（从 checkpoint `response_template` 元数据解析）等。
- `tool_choice`：auto / none / **required（vllm≥0.8.3）** / 指定函数（named）；named 与 required 走结构化输出后端（首次请求有 FSM 编译延迟，之后缓存）。
- 严格模式：tool 级 `strict:true` 或服务端 `--tool-strict-level function|parameter`；`VLLM_ENFORCE_STRICT_TOOL_CALLING` 全局开关（默认 true）。
- 已知问题：Llama 3 不支持并行调用（Llama 4 支持）；Mistral 7B 并行调用不可靠；小 Llama 经常以错误格式发射工具调用；**`hf` parser 拒绝 strict/required/named/parallel_tool_calls=false**。
- 对 LNT 的含义：Ollama_LOCAL/自托管 provider 的能力开关默认应保守（`AUTO_ONLY`、并行关、thinking 依赖 `--reasoning-parser`），并给出用户可配的服务端启动参数提示。

**LM Studio**：OpenAI 兼容 `/v1/chat/completions` 支持 tools（依赖模型 chat template；0.3.x 持续修 Mistral v13/Seed-OSS/Nemotron 模板问题）；工具名会被 normalize 为 snake_case；gpt-oss 的 reasoning content 在 `choices.message.reasoning`；另有 `/v1/responses`（stateful、custom tools、`reasoning.effort`）与 **Anthropic 兼容端点**。用量统计把 system prompt 和工具定义计入。

**Ollama**：`/v1/chat/completions`（OpenAI 兼容子集）支持 tools，模型需在 Tools 类别（Llama 3.1、Mistral Nemo、Firefunction v2、Command-R+ 等）；原生 `/api/chat` 支持并行工具调用与 `think` 开关；`/v1/responses` 自 v0.13.3 起支持（仅非 stateful）。官方明示"supports a subset of the OpenAI API"——高级参数按实测。

---

## 3. Anthropic 工具协议

### 3.1 请求

```jsonc
{
  "model": "claude-opus-5-5",
  "max_tokens": 1024,                       // 必填
  "system": "...",                          // 字符串或 content block 数组（cache_control 挂这里）
  "tools": [
    {
      "name": "get_weather",                // 必须匹配 ^[a-zA-Z0-9_-]{1,128}$
      "description": "...",                 // 官方：越详细越好，3-4 句起步
      "input_schema": { "type":"object", "properties": {...}, "required": [...] },  // JSON Schema
      "input_examples": [ {...} ],          // 可选，schema 合法示例（每个约 20-200 token）
      "strict": true                        // 可选，保证输入合 schema
    }
  ],
  "tool_choice": {"type": "auto", "disable_parallel_tool_use": true},  // auto(默认) | any | tool | none
  "thinking": {"type": "adaptive"}          // 或 {"type":"enabled","budget_tokens":N}（4.6 及以前）
}
```

### 3.2 响应与回传

- 响应 `content[]` 混合 block：`text` / `tool_use` / `thinking`。`tool_use`：`{id:"toolu_...", name, input:{...}}`——**input 是对象，不是字符串**（与 OpenAI 的 arguments 字符串相反，序列化层要注意）。
- 回传：assistant 消息**原样带全部 content blocks**；结果以 user 消息承载：`{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_...","content":"结果字符串或 block 数组","is_error":false}]}`。
- `stop_reason:"tool_use"`；一轮可多个 tool_use（并行）。
- `tool_choice` 四态：`auto`（默认，可带 `disable_parallel_tool_use`）、`any`（必须调至少一个）、`tool`（指定名字）、`none`。**注意 any/tool 会预填 assistant 消息，模型不再输出自然语言前言，且在接受强制调用的模型上会跳过 thinking**。
- **强制调用限制（2026）**：手动 extended thinking 下 any/tool 报错；**Claude Opus 5.5 / Sonnet 5.5 / Fable 5.1 / Mythos 5.1 无论思考设置如何都拒绝 any/tool（400）**——这些新模型要用 `auto` + strict 工具或结构化输出。
- 工具计费：tools 参数注入特殊 system prompt，auto/none 约 286 token（Opus 5.5）、any/tool 约 406 token 起，按模型不同。
- **extended thinking 与 tools 共存规则**：
  - `thinking:{type:"enabled", budget_tokens:N}`：N≥1024 且 < max_tokens（interleaved 例外可超）；**Claude 4.6 弃用（仍可用）、4.7 及以后直接 400**。
  - 新范式：`thinking:{type:"adaptive"}` + `output_config:{effort:"low|medium|high"}`（Sonnet 4.6 默认 high；Opus 5.5/Haiku 5.5 默认 medium）；低 effort 下简单输入可能完全不思考。
  - 手动模式下**最终 assistant 轮必须以 thinking 块开头**；跨工具调用要保留 thinking 块（Opus 4.5/4.6+ 保留并计费）；改 budget/模式会使缓存失效。
- 历史插入：DeepSeek 文档侧面印证 Anthropic `/messages` **支持在对话中段插入 tool_call/tool_result 与 system 消息**（Chat Completions 不支持）。

来源：[Tool use overview](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)、[Define tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)、[Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)。

---

## 4. Gemini function calling

Gemini 2026 有**两套接口**，LNT 现用经典 `generateContent`，但官方主线已迁往 Interactions API，两边形状不同，建议都掌握。

### 4.1 经典 generateContent（LNT 现用）

```jsonc
// 请求
{
  "systemInstruction": {"parts":[{"text":"..."}]},
  "tools": [{"functionDeclarations": [
      {"name":"get_weather","description":"...","parameters":{"type":"object","properties":{...},"required":[...]}}
  ]}],
  "toolConfig": {"functionCallingConfig": {"mode":"AUTO", "allowedFunctionNames":["get_weather"]}},  // AUTO(默认)|ANY|NONE
  "generationConfig": {
    "temperature": 1.0,
    "maxOutputTokens": 8192,
    "thinkingConfig": {"thinkingBudget": 1024, "includeThoughts": false}   // 2.5 系；0=关闭
  },
  "contents": [ {"role":"user","parts":[{"text":"..."}]} ]
}
// 响应：candidates[0].content.parts[] 里的 functionCall
{ "functionCall": { "name": "get_weather", "args": { "location": "Paris" } } }   // args 是对象
// 回传：contents 里追加 role="function" 的 functionResponse part
{ "role": "function", "parts": [ { "functionResponse": { "name": "get_weather", "response": { "result": "..." } } } ] }
```

要点：

- `parameters` 是 OpenAPI 3.0.3 风格的 JSON Schema（REST 里类型大写 `STRING/OBJECT`，SDK 接受小写）。
- **functionCall 的 args 是对象**（同 Anthropic 的 input，不同于 OpenAI 的 arguments 字符串）。
- functionResponse 的 `response` 是结构化 JSON 对象；role 用 `"function"`（API 参考原文：next turn may contain a FunctionResponse with the Content.role "function"）。
- **思考与 function calling 共存**：2.5 系用 `thinkingConfig.thinkingBudget`（0=关）；`thinkingLevel`（minimal/low/medium/high，模型相关：2.5-flash/pro 支持 low/medium/high；2.5-flash-lite 默认关；3.8-flash low/medium/high；3-pro-preview 仅 low/high）。**`max_output_tokens` 含思考 token**，设小了会在思考中截断（status incomplete 且照常计费）。
- **Thought signatures**：经典 API 没有独立 thought block，签名作为元数据可挂在任意 part 上——**包括 functionCall part**。多轮/工具场景需原样回传携带签名的 part，否则推理连续性受损（官方明示）。
- Gemini 4 Argon（2026 最新）：1M 上下文，同步请求 max_output_tokens 默认且最大 65536（超过 400），要更长输出需 `background=true`（long decode 至 1M）。

### 4.2 Interactions API（新主线，`POST /v1beta/interactions`）

- tools 形状与 OpenAI 看齐：`[{"type":"function","name","description","parameters"}]`；返回 `steps[]` 里 `type:"function_call"` 的 step（`id/name/arguments`）；回传 `input:[{"type":"function_result","name","call_id","result":[{"type":"text","text":"..."}]}]` + `previous_interaction_id`。
- `generation_config.tool_choice.allowed_tools.mode`：`auto/any/none/validated`（validated=保证 schema 服从）。
- 思考：`generation_config.thinking_level`（low/medium/high，模型相关）+ `thinking_summaries`；thought step 带 `signature`，**stateless 模式必须原样回传全部 thought block**。
- 对 LNT 的意义：若未来接 Gemini 新模型（3.8/4 Argon）的高级 agent 能力，需要新增一套序列化器；短期继续用 generateContent + thinkingBudget 即可覆盖 2.5/3.x。

来源：[Function calling](https://ai.google.dev/gemini-api/docs/function-calling)、[GenerateContent API 参考](https://ai.google.dev/api/generate-content)、[Thinking](https://ai.google.dev/gemini-api/docs/thinking)。

---

## 5. 弱模型适配经验（公开资料 + 实践共识）

### 5.1 原生 function calling vs 自定义文本协议：可靠性差异

- **基准事实**：BFCL（Berkeley Function Calling Leaderboard）v4 是事实标准，权重向 agentic（40%）/multi-turn（30%）倾斜；2026 年的共识是"**单轮调用已接近饱和，多轮、记忆、长程决策仍未解决**"（[Zylos Research 2026-04](https://zylos.ai/research/2026-04-07-tool-use-function-calling-standards)、[BFCL 官方](https://gorilla.cs.berkeley.edu/blogs/8_berkeley_function_calling_leaderboard.html)）。开源便宜模型（Qwen2.5-72B、DeepSeek V3 级）在多个 BFCL 类目已追平/超过 GPT-4o，但"追平"限于单轮。
- **小模型的原生 tools 失败模式**（vLLM 官方 known issues + issue 区）：
  - Llama 3 系不支持并行调用；Mistral 7B 并行不可靠；
  - "Llama's smaller models frequently fail to emit tool calls in the correct format"（vLLM 原文警告）；
  - **thinking 与工具解析互相踩**：Qwen3.5 + reasoning-parser 在 `enable_thinking=true` 时工具调用以 XML 漏进 `content`、`tool_calls` 为空（[vLLM #42021](https://github.com/vllm-project/vllm/issues/42021)，关 thinking 即恢复）；qwen3 reasoning parser 把 <think> 块之后的内容全交给 reasoning，工具调用埋在思考里永远到不了 parser（[vLLM #39056，2026-08 仍开放](https://particula.tech/blog/empty-tool-calls-reasoning-content-vllm-parser)）；Step3-VL-10B hermes parser JSON 解析失败（[HF 讨论](https://huggingface.co/stepfun-ai/Step3-VL-10B/discussions/19)）。
- **文本协议的定位**：没有服务端 grammar 保证，但 (a) 不依赖服务端开启 tool choice/parser；(b) 解析失败时可以把原始文本作为可读回执喂回模型自我修正（LNT 现有 `AgentToolProtocol` 的 invalid_tool_call 路径正是此设计）；(c) 对"模型只会聊天、完全没有工具训练"的端点（老 Ollama 模型、奇异兼容层）是唯一可行方案。
- **成本侧反直觉的一点**：工具schema 每轮都进 prompt（400–800 token prefill，[Spheron 2026](https://www.spheron.network/blog/tool-calling-benchmarks-bfcl-tau-bench-latency-optimization)）；把工具定义塞 system prompt 走文本协议反而**前缀缓存命中更好**，但失去 `tool_choice`/grammar 强制。LNT 的翻译场景（单章长上下文 + prompt cache）对此敏感，值得在能力开关里让用户按 provider 实测选择。

### 5.2 提高服从性的手段（按优先级）

1. **能力协商优先于提示词**：先确认服务端支持（vLLM 的两个 flag、GLM 的 tool_stream、Qwen 的 parallel_tool_calls），再谈提示词。服务端没开就发 tools，是最常见的"模型不听话"假象。
2. **工具描述写法**（Anthropic/OpenAI/方舟官方一致）：每个工具 3–4 句以上；写清"何时用/何时不用/每个参数含义/边界"；合并相关操作减少工具数量（官方建议单轮 <20 个）；用 enum 消歧义；别让模型填你已知参数（Anthropic 原文："Don't make the model fill arguments you already know"）。
3. **temperature**：便宜模型工具调用普遍低温（0–0.3，如 MiniMax M3 基准用 0.1）；但**思考模型锁温度**（Kimi 1.0、DeepSeek 忽略）——先查锁不锁，再谈调低。
4. **few-shot 谨慎**：对**推理模型**，OpenAI 官方明示"Adding examples may hurt performance for reasoning models"；DeepSeek 侧同样有"Few-shot prompting consistently degrades R1 performance"的旧结论。few-shot 主要留给小非推理模型。
5. **约束解码/结构化输出的可用性**：OpenAI strict（additionalProperties:false + 全 required）；vLLM named/required + strict；DeepSeek strict（beta）；Gemini `validated` mode；Qwen/MiniMax 的 `response_format` json_schema。**自托管优先开 strict/grammar**，这是对小模型最有效的服从性手段。
6. **system prompt 指引**：Qwen 官方示例在 system message 里明说"什么问题调什么工具"以提高准确率；Anthropic 给出强弱两档措辞（"Use the tools to investigate before responding." vs "Always call a tool first."）。文本协议轨同理。
7. **解析宽容 + 重试**：容忍前后缀噪声、围栏代码块、单引号/尾逗号；失败时把错误作为 tool_result 回传让模型自我修正（而非直接抛给用户）。

---

## 6. 待验证清单（实现前建议实测）

| # | 事项 | 验证方式 |
|---|---|---|
| 1 | GLM 官方 tool_choice 是否真的只有 auto（第三方通道可能放宽） | 对 glm-4.6/5.2 发 `tool_choice:"required"` 观察 400 |
| 2 | 豆包各模型最大输出 token、Chat API 思考参数确切形状 | 方舟模型列表页 + 实测 |
| 3 | glm-4-flash 最大输出（4K vs 16K，快照不一致） | bigmodel 模型页逐个快照确认 |
| 4 | StepFun 并行工具调用支持度、finish_reason 实际返回值 | 实测 step-5-preview 双工具 prompt |
| 5 | Kimi K2.x 默认 max output（32K 说法来自第三方） | Moonshot 官方文档 |
| 6 | Ollama OpenAI 兼容端点对 `think`/细粒度 tool_choice 的支持 | 本地 Ollama 实测 |
| 7 | 百炼/DashScope 兼容模式下 `preserve_thinking` 默认值随模型变化 | 逐模型确认（qwen3.8-max 已确认默认 true） |
| 8 | DeepSeek `/beta` strict 模式对 LNT 工具 schema 的兼容性（anyOf/$defs 支持边界） | 用真实工具 schema 试传 |
| 9 | Gemini functionCall part 的 thoughtSignature 在 generateContent 多轮中的回传要求 | 2.5-flash + tools 多轮实测 |
| 10 | 各端点在"思考+工具"同开时 reasoning_content 回传的 400 严格程度 | 按 §2 矩阵逐端一轮回归 |

---

## 附：主要来源

- OpenAI：[Function calling](https://developers.openai.com/api/docs/guides/function-calling)、[Reasoning](https://developers.openai.com/api/docs/guides/reasoning)、[并行调用支持讨论](https://community.openai.com/t/what-models-support-parallel-tool-calls-and-when-to-use-it/1310788)、[reasoning_effort 矩阵请求](https://community.openai.com/t/request-for-compatibility-matrix-reasoning-effort-sampling-parameters-across-gpt-5-series/1371738)
- DeepSeek：[Tool Calls](https://api-docs.deepseek.com/guides/tool_calls)、[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)、[Pricing](https://api-docs.deepseek.com/quick_start/pricing)、[Change Log](https://api-docs.deepseek.com/updates)
- 阿里百炼：[Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling)、[DashScope API 参考](https://www.alibabacloud.com/help/zh/model-studio/qwen-api-via-dashscope)、[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking)
- 智谱：[工具调用](https://docs.bigmodel.cn/cn/guide/capabilities/function-calling)、[核心参数](https://docs.bigmodel.cn/cn/guide/start/concept-param)、[GLM-4.6 迁移](https://docs.bigmodel.cn/cn/guide/start/migrate-to-glm-4.6)、[GLM-4 模型](https://docs.bigmodel.cn/cn/guide/models/text/glm-4)
- Moonshot：[Kimi K3 Quickstart](https://platform.kimi.com/docs/guide/kimi-k3-quickstart)、[K2 tool guidance](https://github.com/MoonshotAI/Kimi-K2/blob/main/docs/tool_call_guidance.md)
- MiniMax：[M3 分析](https://www.morphllm.com/minimax-m3)、[M2.5 tool guide](https://github.com/MiniMax-AI/MiniMax-M2.5/blob/main/docs/tool_calling_guide.md)
- StepFun：[Tool Call](https://platform.stepfun.com/docs/zh/api-reference/tool-call)、[Chat Completions](https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create)
- 火山方舟：[Function Calling](https://www.volcengine.com/docs/ark/function-calling)、[工具概述](https://www.volcengine.com/docs/ark/tools-overview)
- 自托管：[vLLM Tool Calling](https://docs.vllm.ai/en/latest/features/tool_calling)、[LM Studio Docs](https://lmstudio.ai/docs/developer)、[Ollama Tool Calling](https://docs.ollama.com/capabilities/tool-calling)、[Ollama OpenAI 兼容](https://docs.ollama.com/api/openai-compatibility)
- Anthropic：[Tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)、[Define tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)、[Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- Google：[Function calling](https://ai.google.dev/gemini-api/docs/function-calling)、[GenerateContent 参考](https://ai.google.dev/api/generate-content)、[Thinking](https://ai.google.dev/gemini-api/docs/thinking)
- 弱模型/基准：[BFCL](https://gorilla.cs.berkeley.edu/blogs/8_berkeley_function_calling_leaderboard.html)、[Zylos Research](https://zylos.ai/research/2026-04-07-tool-use-function-calling-standards)、[Spheron BFCL/tau-bench](https://www.spheron.network/blog/tool-calling-benchmarks-bfcl-tau-bench-latency-optimization)、[Databricks eval](https://www.databricks.com/blog/unpacking-function-calling-eval)、[vLLM #42021](https://github.com/vllm-project/vllm/issues/42021)、[particula: empty tool_calls](https://particula.tech/blog/empty-tool-calls-reasoning-content-vllm-parser)
