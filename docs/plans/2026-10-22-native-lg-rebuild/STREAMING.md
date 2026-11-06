# Agent 流式传输与实时预览契约

实际进度和当前 CI 范围见 [HANDOFF §3、§49](HANDOFF.md)，原开发轮见 §30。当前切片已提交并有构建/测试证据；尚未查看新组件截图或连接真实服务商。

## 1. 唯一请求链路

`NativeKoogConversation → AgentRequestBoundary → NativeModelGateway → CompatibleAgentModel → CompatibleHttpTransport`。

聊天请求设置 `stream: true`、`stream_options.include_usage: true` 和 SSE Accept Header。HTTP / HTTPS、可选 Key 及精确模型配置修订保持 §29 契约。供应商拒绝流式参数时报告错误，不自动切换参数或重发可能计费的请求；同一次响应若返回 application/json，则按完整回复解析，不发起第二次请求。

Koog functional strategy 继续通过原生类型网关 `complete` 组织工具循环；它消费最终类型化响应，HTTP 字节流同时经预览事件显示给用户。没有另建 Koog HTTP 客户端，`PromptExecutor.executeStreaming` 不开放另一条请求路径。

## 2. SSE 与工具分片

增量读取 UTF-8，处理字节边界、BOM、LF/CRLF/CR、注释心跳、多行 data 与末尾 `[DONE]`。不使用浏览器 EventSource，不重连、不带 Last-Event-ID 重放。没有空行终结的末尾事件按 SSE 规则丢弃。

只消费一个 choice（index 0），正文 `content`、思考 `reasoning_content` 和最多八个工具调用。工具参数按精确整数 index 拼接，完成后才交给原有严格 JSON/参数边界；部分参数不会执行，也不把它们拼进用户消息。工具 provider ID 和原生工具身份继续保持映射。ID/函数名支持增量分片；分片值与当前累计值完全相同时视为重复完整值，不再次拼接，参数始终按分片追加。分数/越界 index、重复 JSON 字段、非法 JSON、未知结束原因与结束后追加内容会失败。

必须收到有效 finish_reason，`[DONE]` 本身不构成完成证明；缺少结束原因的 EOF 登记截断。兼容服务器在已经完整派发 finish 帧后正常关闭、未发 `[DONE]` 的情况可以完成；异常断线仍是失败。`length/content_filter` 回执保留已知文本与用量，不生成不完整的工具调用，并由请求边界阻止其成为有效回合。

usage 可在末尾单独的空 choices 帧或最后 choice 帧中提供，采用最后一个非空快照，不把累计值逐帧相加。缺失指标仍 UNKNOWN，已有数值严格验证为非负整数。实际用量只随最终回执持久化。

首轮预算为每次最多 8 MiB 流量、32768 个 data 事件、每行/事件 512 Ki 字符、累计语义内容 256 Ki 字符、单工具参数 128 Ki 字符。最终消息沿用 512 Ki 字符的历史单条边界。流式请求总超时 10 分钟、默认读取空闲超时 90 秒；模型列表继续使用 20 秒外层超时。这些是配置防护值，不是已测得的设备性能承诺。

协议参考：[SSE 标准](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation)、[LM Studio Chat Completions](https://lmstudio.ai/docs/developer/openai-compat/chat-completions)、[DeepSeek 流式与用量字段](https://api-docs.deepseek.com/api/create-chat-completion/)。文档材料不是本项目的供应商联调证据。

## 3. 取消、回执与审批

HTTP 在 IO dispatcher 读取，取消观察协程关闭 Call；响应流和响应体在调用返回前收尾，运行占用仍由 DurableRunExecutor 最后释放。停止后不新增请求，不通过重新发送取得缺失用量。

完全接收的结果在 IO 返回/关闭响应体时遇到取消仍会交回 AgentRequestBoundary，先保存已知回执，再检查取消。未完整结束的流没有最终响应，不将 delta 保存为助手历史；DISPATCHED 保持不确定，重启不自动请求。

正式助手消息与工具结果继续按既有持久历史顺序保存。准备差异、允许一次/拒绝及 ProjectWriteStore 不变；增量工具元数据只是“准备中”提示，不提供写授权。

## 4. 预览与原生页面

`AgentStreamPreviewController` 为每次执行提供隔离 session，检查完整 ProjectInstance/Generation/Chat/Run/attempt 归属。旧 session 的 delta、结束或 finally 不能覆盖新执行。文本与思考各保留最新 8192 字符，约 50ms 合并一次，结束/回执时强制发布剩余内容；发布不在控制器 monitor 内运行订阅者。

逐 token 预览 StateFlow 仅由可见的时间线预览组件用于渲染。页面另收集经 `map`/`distinctUntilChanged` 投影的可见性布尔值：仅在存在匹配工程实例/代际/Chat、且尚未作为正式消息展示的预览时创建列表项；空、归属不符或已保存的预览不留额外间距。正文更新不会逐 token 重组整个页面。正文实时显示，思考可展开，工具仅显示准备数量。预览面板使用有界滚动区域；跟随末尾时自动滚动，用户向上阅读时保留位置。

最终回执完成后保留预览直到正式消息刷新，以消息 ID 去重后清除。断线或停止时明确标记未提交的部分预览；它只在当前 ViewModel 内存中保留，不进入 Room、SavedStateHandle、工程文件或模型历史。切工程/会话、启动新操作或进程退出会清除；重启只显示已有持久事实。

## 5. 验证与剩余范围

新增 SSE/真实传输夹具、取消资源收尾、已知结果返回取消窗口、预览归属/节流/收尾、请求边界与原生组件用例。网络夹具由拦截器/内存字节流提供，不调用真实服务商；本机禁止运行这些测试。源码已由 #158 覆盖，统计与未覆盖边界见 HANDOFF §3；仍须在设备上验收首字、思考、工具、停止、断网、长回复、切会话与键盘布局。

本轮不增加附件、分支、输入队列、流式断点续传、自动重试或 Responses/Anthropic 流式协议；该切片未改变当时的 Room/.lnt 版本（当前 Room v18、`.lnt` v8），迁移与模型发现已有后续 CI 证据，但 schema 导出、设备升级与真实端点联调仍须分别补齐。

共享调度后的预览启动点位于取得派发资格及持久 DISPATCHED 登记之后。等待者不产生预览生命周期；只有实际拥有预览的调用才清理它。完整响应的 NonCancellable 持久化边界保持不变，详见 [MODEL_SCHEDULING](MODEL_SCHEDULING.md) 及 HANDOFF §39。
