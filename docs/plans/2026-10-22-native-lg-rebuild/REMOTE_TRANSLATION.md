# 远程 TXT 批量翻译与持久请求回执

§44 后续未验证增量：新建 `.lnt` v2、兼容 v1 的工程规则/提示词与事务回执、原生管理页及远程文本流水线见 [RULES_AND_PROMPTS](RULES_AND_PROMPTS.md)；尚未构建，原文的 v1/未接规则限制按原切片理解。

开发记录和验证边界见 [HANDOFF §43](HANDOFF.md)。本轮为未提交源码，尚未构建或运行测试；§41 的 CI 不覆盖本轮。未调用真实供应商。完整 R10—R14/R18 和 S1/S2 仍未完成。

## 用户入口与固定请求

校对页新增独立“远程翻译”入口；模型先在 Agent 页配置，当前页或整工程的范围必须单独确认。表单展示已选模型、端点、可能费用和原文外发说明；目标语言必填，源语言可为 auto，可填写本次翻译要求、每批条数和字符目标。编辑或取消表单不会请求模型。

表单固定展示时的工程实例/代际、工程修订与模型配置修订，确认时核对当前工程和修订；不在发送时偷偷改用最新模型。运行请求通过 RemoteTranslationOptions 冻结语言、要求及批次策略；同一 commandId 更改选项或范围会拒绝复用。表单是本次运行的草稿，不冒充已实现工程级规则/提示词/预设持久化。

表单沿用全屏 Dialog 窗口、居中限高卡片、有界表单滚动和固定按钮。筛选、编辑、批选、未决人工批次、运行记录读取失败或无待处理条目时不能启动；工程范围从零游标开始。

## 生产调用链

校对确认 → NativeProjectViewModel → ForegroundTranslationRunner → NativeTranslationService 前台确认 → DurableRunExecutor → TextTranslationService → NativeModelGateway 共享调度 → CompatibleTranslationModel / CompatibleHttpTransport → TranslationCommitPort / ProjectWriteStore。

没有 UI、Koog 或第二个 HTTP 客户端的付费旁路。远程翻译使用已实现的 Chat Completions 兼容协议，非流式请求；Agent 仍使用现有 SSE 对话。配置/凭据按确切修订从 NativeModelSettingsPort 解析，复用同一 HTTP 所有者、禁隐式重试和重定向、超时/限流/认证分类及取消关闭资源。

共享 FIFO 资格取得后才记录 REQUESTING、计入请求尝试，再核对 Stop；排队时取消不登记为已发送。远程适配器进一步在 HTTP 前写入不可重复的 attempt 审计记录。持久检查点到 HTTP 之间仍有保守不确定窗口，不能承诺精确的供应商计费次数。

每批 1—20 条，字符目标 256—32768；单条长文本可超过软目标，仍受既有单条/请求硬预算约束。只翻译非空 NONE 条目，保留已处理和排除条目；一页一页读取，逐批原子保存，后续失败或停止保留之前已提交事实。不自动重试、暂停续跑或跨进程重发。

## 响应协议与写回

请求中的源文是数据，不得当作指令执行。每个请求使用独立 request_id，每条输入包含字符串 id、item_fingerprint 和 source。id 即使超过 JavaScript 安全整数也不得转浮点或按位置关联。

模型正文必须是一个严格 JSON 对象，只有 request_id 和 items；每个输出只有字符串 id、item_fingerprint、text。request_id、输入身份集合、个数与指纹须精确匹配；重复/缺失/多出/跨请求/数字身份、有重复键的 JSON、代码围栏、空白译文、NUL、损坏 Unicode、超长输出、截断、过滤或工具调用一律拒绝整批。模型输出不能改写来源身份或授权写入。

全部校验成功后，沿用真实运行租约、预期修订/对象指纹、逐条命令回执和外层批事务。停止后的迟到译文不写回。当前不提供宽松自动修复或额外付费重试。

## Room v4 请求元数据与用量

NativeAppDatabase 源码升至 v4，增加 MIGRATION_3_4 和 native_translation_requests，保留 v1→v2→v3→v4 升级链。旧阅读库和 `.lnt` v1 不变。v4、2/3/4.json 的真实 KSP 导出及设备升级均待用户统一验证，不手写 schema。

每个远程 attempt 持久绑定工程/实例/代际、Run/Command、模型配置修订、实际 HTTP 请求正文的 SHA256、DISPATCHED/RECEIVED、分类与五种 Token 度量。库中不存原稿、译文、提示词、Key、Header 或完整 HTTP 响应；仅有摘要和轻量事实。

发送前 INSERT 拒绝所有重复 attempt，包括同一 payload 的重复；它不提供重放入口。完整读取 HTTP 响应后，即使正文解析失败，也先在 NonCancellable 收尾中记录可解析用量及分类，再传播停止/解析失败。完整响应跨 IO 返回时遇协程取消，通过临时内存槽取回；部分响应不持久化为完成。写入审计失败会阻止译文继续提交，不声称已保存响应事实。

未提供的计量保持 UNKNOWN；实测、估算、未知分别累计，使用 BigInteger 避免 Long 总和溢出。缓存未命中输入不能当作缓存写入 Token。当前没有货币定价、费用计算或跨 Agent 的完整 UsageLedger。

运行历史可在 Agent 页和工作台查看按运行持久汇总的已收响应 Token，包括解析失败或取消后已记录的响应；按 100 条元数据分页、在一致事务内汇总，不把正文读入 UI。HTTP 失败/未完整响应可能仍产生费用，已收响应数不是计费次数。

恢复只读取事实：带远程 attempt 的 REQUESTING 若无审计预留，则尚未跨过 HTTP 边界；有 DISPATCHED 而无 RECEIVED 时保留需核对；有完整响应元数据则改记 RESPONSE_RECORDED。该阶段不表示译文正文已持久化，更不自动补写译文。旧无 attempt 的模拟检查点继续保持保守不确定语义。

## 页面与任务寿命

前台翻译期间可切换同一工程的 Agent/工作台/校对页，busy 仍阻止写入、导出、换工程和新模型请求。全局停止入口和通知停止保持同一 token 归属；页面切换不取消任务。Agent 对话自身仍由 ViewModel 持有，尚未前台化；此变更不构成完整后台任务系统。

## 待用户统一验证

新增协议/拦截器/用量保存竞态、真实存储迁移/核对、批次失败/选项绑定和表单确认用例源码；均未运行。需 CI/PC 全量 test/lint/release、v4 KSP schema、组件截图录制与查看，以及设备通知/切页/重建/停止。真实供应商联调与付费调用仍需明确授权。

工程级规则、文本保护/替换、术语、前文/去重/Token 规划、所有翻译模式、完整五协议、动态调度/密钥池/费用账本、暂停/同 Run 接续和复杂格式仍待实施，不因本切片开放远程入口而登记为完整 LG 翻译器。
