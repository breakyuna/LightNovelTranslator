# 远程 TXT 批量翻译与持久请求回执

当前进度与验证边界统一见 [HANDOFF §3、§49](HANDOFF.md)。本专题相关源码已纳入 `2643b2c` / Actions #146 的 test/lint/release 成功基线；新增设备/视觉、真实供应商与 Room schema 导出仍待补齐。

开发记录和验证边界见 [HANDOFF §43](HANDOFF.md)。本切片已由 §49 的 Actions #146 覆盖，§41 保留为旧版本证据。未调用真实供应商。完整 R10—R14/R18 和 S1/S2 仍未完成。

## 用户入口与固定请求

校对页新增独立“远程翻译”入口；模型先在 Agent 页配置，当前页或整工程的范围必须单独确认。表单展示已选模型、端点、可能费用和原文外发说明；目标语言必填，源语言可为 auto，可填写本次翻译要求、每批条数和字符目标。编辑或取消表单不会请求模型。

表单固定展示时的工程实例/代际、工程修订与模型配置修订，确认时核对当前工程和修订；不在发送时偷偷改用最新模型。运行请求通过 RemoteTranslationOptions 冻结语言、要求及批次策略；同一 commandId 更改选项或范围会拒绝复用。表单是本次运行的草稿，不冒充已实现工程级规则/提示词/预设持久化。

表单沿用全屏 Dialog 窗口、居中限高卡片、有界表单滚动和固定按钮。筛选、编辑、批选、未决人工批次、运行记录读取失败或无待处理条目时不能启动；工程范围从零游标开始。

## 生产调用链

校对确认 → NativeProjectViewModel → ForegroundTranslationRunner → NativeTranslationService 前台确认 → DurableRunExecutor → TextTranslationService → NativeModelGateway 共享调度 → CompatibleTranslationModel / CompatibleHttpTransport → TranslationCommitPort / ProjectWriteStore。

没有 UI、Koog 或第二个 HTTP 客户端的付费旁路。§69 未构建源码中，远程翻译按配置协议选择 Chat Completions 兼容传输或 `ModelProtocolTransport`（Responses/Gemini/Anthropic/Sakura，均非流式）；Agent 的 Chat 协议仍走 SSE，非 Chat 协议走非流式协议传输并在完成后一次性发出预览。配置/凭据按确切修订从 NativeModelSettingsPort 解析，复用同一 HTTP 所有者、禁隐式重试和重定向、超时/限流/认证分类及取消关闭资源；每次显式请求只选择一个可用 Key，Key 失败只记录冷却/禁用，不在单次请求内换 Key 重发。

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

## 启动模式增量（未构建）

HANDOFF §51—52 未提交源码已接共享资产分组规划与 NEW/CONTINUE/RESET 选择；模式随 RemoteTranslationOptions 冻结，改变模式不能重绑既有前台命令。重新翻译不先清空旧译文，只有新结果提交才替换；请求失败/停止仍保留旧事实。五语确认明确再次外发及可能费用。本增量尚无 CI/设备证据，不继承 #146 全绿。

## 前文增量（未构建）

§53 新增 precedingLines（0–16，默认 2），确认后固定并进入请求。TranslationContextPort 在当前修订读取同资产锚点前条目，规划器按预算筛选；协议将其放在 precedings，累计最多 2048 字符。前文只读，不进入输出身份或提交集合；跨页可读，空行/句尾/跳过规则由共享规划器消费。本增量无 CI/设备证据。

## 有界失败策略增量（未构建）

§54 新增每原计划批次 0–5 次额外请求额度，默认 0；用户显式确认费用后才执行重试/拆批。所有子请求共用额度，认证/过滤等终止错误不重试；存储提交失败不触发付费重发。替代请求有独立 attempt 并重经共享调度/停止/审计边界，成功子批保留。当前队列仅进程内，不提供进程恢复自动重放。本增量无 CI/设备证据。

## 同文件复用增量（未构建）

§55 新增默认关闭的 deduplicateSameFile：同页代表条目请求一次，跨页仅复用本次运行开始后提交的同文件/同原文普通条目结果；不复用运行前旧译文或命名/格式元数据条目。目标按原子子批保存为 PROCESSED，不增加模型请求/Token。当前不提供完整 LG 被动 DUPLICATED 状态与代表维护。本增量尚无 CI/设备证据。

## 条目失败与日志结算增量（未构建）

§56 已接协议/截断/内容/过滤/容量/空响应终止后的 ERROR 状态持久化，之后可显式 CONTINUE；已译条目重新翻译失败保留旧译文与状态，认证/网络等整体故障不落条目内容错误。REQUEST_FAILED 保留未知派发核对并允许确认额度内重试，MARKING_FAILURE 恢复时不增加成功翻译数。当前终止错误结束运行，完整持久任务接续仍待实施。
