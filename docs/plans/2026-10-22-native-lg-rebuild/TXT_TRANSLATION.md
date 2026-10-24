# TXT 翻译流水线首个切片

当前进度与验证边界统一见 [HANDOFF §3](HANDOFF.md)。提交 `77daf74` 的 [Actions #158 / 40085555783](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40085555783) 已通过 test/lint/release 和签名校验，覆盖至 §101；§102—107 仅存在于工作区，未提交、未构建，§108 为姓名链路核对。新增设备/视觉、真实供应商与 Room 2—8 schema 导出仍待补齐。

后续源码边界：持久执行记录/中断核对及 Room v9 源码已由 #158 覆盖，见 [HANDOFF §3](HANDOFF.md)。契约见 [RUN_RECOVERY](RUN_RECOVERY.md)；完整后台执行接续仍未完成。§34—40 的当前页/整工程模拟应用级前台执行与共享调度已有 #158 CI 证据，见 [FOREGROUND_TRANSLATION](FOREGROUND_TRANSLATION.md)。

当前验证边界：本地模拟和分批写回用例已由 #158 覆盖，设备流程/截图仍待验；唯一进度见 [HANDOFF §3](HANDOFF.md)。不能继承 §22 TXT 手工版的真机结论。

本文定义当前本地模拟切片的源码契约，原始开发记录见 HANDOFF §23，当前进度与验证仅记入 [HANDOFF §3](HANDOFF.md)。不代表统一网关 R11、完整批翻 R13、S1 或 Agent 已完成验收。

## 接线与范围

`Proofreading` 确认弹窗 → `NativeProjectViewModel.simulatePage` → `TextTranslationService` → `NativeModelGateway` → `LocalSimulationModel` → `TranslationCommitPort` → `NativeTextProjectStore` → `ProjectWriteStore`。

只有明确确认后才运行；界面说明它不是真实翻译、不联网、不收费，会把 `[SIMULATION] ` 加原文保存为已处理译文，并建议需要时先复制工程。用户显式选择当前页或整工程，只处理非空、状态 NONE 的条目；当前页最多 40 条，整工程逐页扫描，不覆盖已处理或已排除条目。工程引用、预期修订和页游标在执行前固定。

这是新模型网关的翻译端口切片。AgentModelPort 已经由 NativeKoogConversation 接入同一 NativeModelGateway，并与模拟翻译共享固定调度。远程 Agent 传输及凭据存储已由 #158 覆盖，真实供应商/KeyStore 真机未验收；远程批量翻译、自动重试、完整调度/密钥池、请求日志、费用及上下文规划继续按 R10—R14 实现。

## 请求与运行

- 进程内 TRANSLATION 运行独占共享 RuntimeCoordinator，正文规划/校验在 Default dispatcher，SQLite IO 在存储适配器的 IO dispatcher。新运行在引擎入口取得真实租约，手工写入与其他运行不能并行进入。
- 每次请求固定工程实例/代际、运行、配置修订和新的 attempt 身份；最多 20 条，文本计划预算 32,768 字符，单条可超过计划预算但仍受硬上限约束，请求硬上限 65,536 字符。首个切片单条源文大于 65,500 字符拒绝，给模拟标记预留空间；这些不是正式设备/模型预算。
- 网关在后端请求前取得 MODEL_REQUEST 操作租约，等待结构化子任务结束再释放；后端必须在返回前取消/回收自身资源。没有隐式重试。取消原样传播，未分类后端异常转为脱敏 UNKNOWN。
- 响应必须 COMPLETE，条目身份集合及数量精确匹配，无重复或未知条目；文本长度及严格 UTF-8 合法性校验通过才允许写入。停止后的迟到响应不提交。
- 进度区分已提交条目和请求尝试数（不等同于已计费请求数）；Token 按响应的 ACTUAL/ESTIMATED/UNKNOWN 聚合，混入未知即保持未知。本地模拟全部为 UNKNOWN，不伪造零 Token 或费用。请求尝试和用量仅在本次进程状态中，不冒充持久账本。

## 批次写入与取消

TranslationBatch 使用独立类型化端口；存储端验证运行归属和真实不透明租约，只允许 TRANSLATION，Agent 不能拿人工命令绕过审批。适配器外层 SQLite 事务包围批内的 ProjectWriteStore 条目事务，任一条修订/指纹冲突或提交前取消会回滚整批；每条仍产生既有 v1 指纹、修订及回执。后续批次失败不回滚之前批次。

工程条目事实沿用早期 `.lnt` v1 命令编码；当前工程 schema 为 v5，独立持久 Run/请求与提交阶段记录由应用 Room v9 提供，见 RUN_RECOVERY。人工和翻译共享唯一事务拥有者，但执行入口和运行授权不同；Agent 单条 TXT 持久审批已有 #158 CI，完整 R19 多对象审批仍未完成。

取消按钮先请求 Runtime 停止，再取消实际协程；运行占用在最终清理后释放，停止阶段拒绝下一次请求和写入。ViewModel 在完成/失败/取消后重新读取已提交事实，避免提交成功但取消回调迟到时继续显示旧数据。再次点击模拟是显式的新运行，只处理仍未处理的条目；不自动恢复执行、不自动重放模型请求。

## 验证与未实现

已写网关和真实 Room/SQLite 集成测试，包含无效/截断响应、停止和迟到响应、批内回滚、伪造租约、后续失败/取消保留前批、显式继续、保存与重新导入；新增 UI 确认回调用例。上述用例及后续前台/整工程增量已由 #158 覆盖，见 HANDOFF §3；本机只做静态审查，未实际构建。

进程死亡后已提交项目事实可重开，未提交响应不自动重放；§28 已增加执行阶段/计数及回执核对，§80 已补持久用量账本，§83—97 已补暂停、任务分页与指定回执边界的恢复；原 Run 跨进程自动接续仍缺。整工程本地模拟已有 CI；远程 TXT 翻译已有 §43/§49 接线并已由 #158 覆盖，完整 Agent/审批与全部格式验收均未完成。§23 历史切片由 ViewModel 协程拥有；§34—40 已迁移到应用级前台执行器和 Service 并扩展整工程范围，新增设备验收仍缺，不关闭完整 S1。

## 后续整工程范围与用量

范围的源码契约扩展见 [FOREGROUND_TRANSLATION](FOREGROUND_TRANSLATION.md) 和 HANDOFF §39。`TextTranslationService.translateProject` / `translateReservedProject` 在单个持久运行中逐页读取，跨页共享修订、计数与用量；`translatePage` 保留原来的单页语义。本地模拟仍是默认入口；远程 TXT 批翻已由 §43/§49 接入共享网关与用户确认表单，不代表所有模型/协议/规划分支已完成。

两类推理均经过 [共享请求调度](MODEL_SCHEDULING.md)。原 RequestUsage 是保守兼容投影；新增 RequestUsageTotals 分别保留实际、估算及未知响应数，精确累计不会被后续缺失值或 Long 上限抹掉。§80 已把用量账本与派发/响应同事务持久化；费用定价和真实供应商验收仍缺。

## 新规划核心的实际边界

§49 已新增 NEW/CONTINUE/RESET、规划/预算/前文、去重、分类/对半重试及检查点编解码核心；§51—55 源码已由 #158 覆盖，让 TextTranslationService 消费规划器的资产分组与条数/字符预算、过滤停用资产、接入远程 NEW/CONTINUE/RESET、确认有界同资产前文、显式额度失败分类/退避/拆批及同文件本次运行的显式译文复用（普通条目）；§81—97 继续补固定任务、暂停、分页和恢复。完整被动去重协调与跨进程自动续跑仍未完成。持久编码类型本身不提供暂停或同 Run 跨进程接续。
