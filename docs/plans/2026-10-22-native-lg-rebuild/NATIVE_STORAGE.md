# 原生 TXT 存储契约

当前进度、CI、源码版本及验证边界统一见 [HANDOFF §2—3](HANDOFF.md)。下文保留首个 TXT 切片的历史契约；新增批量条目审批审计见文末 v6 小节，PDF 页审批回执见 v7 小节。源码版本与非破坏性迁移以 NativeProjectDatabase / NativeAppDatabase 为准，不能把旧切片验证当成新增能力的运行证据。

本文说明首个 `.lnt` TXT 切片的源码边界。实施进度、实际验证和未完成项只记入 [HANDOFF](HANDOFF.md)；TXT 存储基线已有指定提交的 CI 证据；新增导入/恢复与 nativeapp 接线的用户 CI/真机确认见 HANDOFF §22，不表示完成 S1 验收。

## 1. 格式与所有权

- 原生文件为 SQLite `.lnt`，`application_id = 0x4c4e5431`（LNT1），`user_version = 1`，对象/命令指纹编码版本为 1。打开时检查标识、版本与 SQLite 头部格式版本字节（18/19 必须为 1 = 回滚日志；2 = WAL 直接拒绝）。未来版本、WAL 与外来格式直接拒绝，不自动升级或清库。
  不能改用 `PRAGMA journal_mode` 断言：Android 每次可写打开都会把连接日志模式设成平台默认（AOSP 为 `TRUNCATE`），而 `DELETE/TRUNCATE/PERSIST` 不写进文件头，只有 WAL 持久化。快照保证的是"不是 WAL、经 ATTACH 逻辑复制"，不是某个连接的 rollback 子模式。
- 以下描述历史单 TXT v1 切片；当前源码为多资产 v7，包含 v2 规则表、v3 资产/设置扩展、v4 归档移除、v5 姓名快照、v6 审批条目批次回执及 v7 PDF 页审批回执，不是全部工程模型的冻结版。PDF、规则、Chat、格式私有字段、兼容保留区等须通过后续 schema 版本/迁移扩展；不得将旧数据导入这个不完整目标而静默丢字段。
- 私有工程位于 `filesDir/native_projects/<本机生成的 UUID>/project.lnt`。路径不使用工程名称或外部文件名；逻辑资产路径经 `RelativePath` 验证，实际文件访问拒绝符号链接改写。
- 新应用索引使用独立 Room `lnt_native_app.db`（当前源码 v16：v1→v2 Agent 记录、v2→v3 执行表、v3→v4 请求元数据、v4→v5 协议/多密钥、v5→v6 用量账本、v6→v7 固定翻译任务、v7→v8 响应/提交暂存、v8→v9 Agent 输入队列/steer、v9→v10 任务终止、v10→v11 成员裁决、v11→v12 手动摘要、v12→v13 历史分支、v13→v14 批量条目提案与成员、v14→v15 规则/提示词提案、v15→v16 PDF 页提案与成员；导出/升级验证边界见 HANDOFF），与旧 Room v6 完全分开。原生工程只保存来源工程身份；本地实例和恢复代际由 Room 索引拥有。当前只生成初始代际 1，没有覆盖恢复/代际推进入口。
- 原稿使用 `raw-v1` 原始字节分块，单块最多 64 KiB，保存原始长度及 SHA-256；不是 Zstd，也不冒充 LG schema。外部工程转换已取消，仅维护原生 `.lnt`。

## 2. Schema 与端口

| 所有者 / 表 | 内容 |
|---|---|
| Room `project_instances` | 本地实例、来源工程、代际、唯一创建命令及其内容摘要 |
| `.lnt` `project` | 单工程名称、来源身份、内容修订与指纹版本 |
| `.lnt` `assets` / `asset_chunks` | TXT 路径/类型、原始长度、摘要、codec 和顺序分块；空文件也有资产记录 |
| `.lnt` `items` | 稳定正整数 ID、资产、原行位置、源文、译文、人工状态和指纹 |
| `.lnt` `item_revisions` | 各次已提交的译文/状态、前后指纹、命令、实例/代际和时间 |
| `.lnt` `write_receipts` | 按实例/代际/命令唯一的完整意图摘要、对象、终态、修订和时间；包括不写入终态 |

领域端口在 `core/domain/.../TextProject.kt`；SQLite SQL 在 `core/storage/.../NativeProjectDatabase.kt`，手工事务在 `ProjectWriteStore.kt`；应用 IO 适配器是 `NativeTextProjectStore`。新 Room 的 KSP schema 导出目录为 `core/storage/schemas`；首份权威 `1.json` 已由 CI run 39789360824（提交 `224da53`）生成、审阅并提交，后续 schema 变更仍须先由 PC/CI 生成新版本文件再改迁移。

`TextProjectPort` 提供：

1. `createTxt`：显式创建命令、名称、相对路径、原始字节与编码；先校验/解析，再发布私有副本和 Room 索引。
2. `registeredProjects` / `open`：重启后发现并重开登记实例。列表有界、按实例 ID 排序，尚不是完整的最近访问排序功能。每条返回 `RegisteredProject`：可读时带 `summary`，缺失目录、损坏文件或外来格式时带分类后的 `failure`，单个失效实例不会让整份最近列表失败；`open` 仍对指定实例抛出同一分类错误。
3. `items`：按 ID 游标读取，携带预期工程修订，过期窗口拒绝；每页最多 100 条。`readPage` 在一次事务内同时返回 summary 和有界 items，供运行中界面的最新分页预览使用，避免两次读取之间提交批次导致 STALE_PAGE；不替代模型/写入的预期修订检查。该新增端口的未验证边界见 HANDOFF §36。
4. `edit` / `receipt`：手工编辑、命令去重及持久结果查询。
5. `saveProject`：生成原生工程一致快照，写入调用方提供的流。
6. `exportTxt` / `exportOriginal` / `exportBilingual`：分别导出有效译文、原始字节与原译对照。
7. `importProject` / `copyProject`：校验外部快照后创建新本地实例；复制复用一致快照与导入链路。
8. `recoverProjects`：扫描本机已发布但未登记的工程并逐个校验、恢复索引；异常目录保留并返回问题。
9. `item`：读取指定条目及同一读取边界的工程修订，供单条编辑。
10. `cleanupTemporaryOutputs`：只清理适配器拥有的临时输出，保护正在复制的快照。

调用方提供的输出工厂只负责打开目标流，在 IO 线程调用；适配器负责写入、flush、close 全部成功后才返回。它不是 SAF UI 或权限管理器。方法从 IO dispatcher 运行；调用方应在应用级注入同一个 RuntimePort，结束全部调用后再关闭适配器。

读方法同样以可写连接打开工程：SQLite 只能在可写连接上回滚热日志，崩溃后的写入恢复优先于省掉写标志。同一适配器的调用串行；两个实例不得共享同一私有工程目录。存储故障（目录不可创建、发布重命名失败、目标产物不新鲜）返回 `STORAGE_IO`，项目数据损坏返回 `CORRUPT_PROJECT`，不再混用 `check`/`error` 抛出的 `IllegalStateException`。

## 3. 创建、写入与快照

创建在专属 staging 目录内一次事务写入原稿和条目，关闭 SQLite 后发布私有目录，最后登记 Room。相同创建命令/相同摘要重放已有实例，不同内容返回冲突。取消在分块/条目循环中检查，失败仅清理本次未发布 staging；目录已发布而 Room 登记失败时保留材料。发布前写入并同步私有 registration.json，重启 recoverProjects 可校验发布完成但 Room 未登记的目录并重新登记；损坏或无清单目录保留并报告，不能把这一窄窗口恢复称为完整备份恢复。

手工编辑取得 RuntimePort 的工程写租约；活动 Agent/翻译运行阻止人工写入。工程事务先按命令读已有回执并核对完整摘要；无回执时检查预期工程修订、对象存在性和基准指纹。成功时一起更新条目、递增修订、保存修订事实与回执；冲突或对象不存在也保存不写入终态。相同命令重试返回同一结果，不因稍后工程变化而变成另一种结果。提交前检查点观察到取消或发生事务故障时，内容、修订和回执一起回滚；若取消到达时已经跨过该检查点并提交，不回滚已提交事实，调用方按命令查询回执，不把取消异常当作“没有写入”。

这不是 Agent 授权。完整 Agent `ProjectWritePort` 仍未接全部对象的持久审批，单条 TXT 专用切片见 S2_AGENT_SLICE，不能将模型请求转换为 `ManualTextEdit` 绕过审批；通用 PROJECT_WRITE 工具仍拒绝执行；新增 APPROVAL_PREPARATION 仅准备不可变待审批，不直接写入工程。后续审批/批翻实现必须扩展同一 ProjectWriteStore 事务拥有者，不另造第二个写入者。

快照通过 SQLite 同连接 `ATTACH` 新文件，在事务中复制当前 v1 的全部表，再脱离并核对标识/版本与 `quick_check`；不复制活动数据库主文件，不依赖 API 24 尚不支持的 `VACUUM INTO`。`sqlite_sequence` 单独原样复制：显式 ID 的 `INSERT ... SELECT` 只会把自增高水位压到现存最大值，恢复快照后一旦删除过条目就会复用 item ID。外部流写入前已经结束数据库事务和运行写租约。导出失败不改变私有工程，保留临时产物；临时产物目前在 cacheDir，可能被系统回收，不能当作持久备份。当前同一适配器的调用仍串行，长时间目标写入可能让其他调用等待。

## 4. TXT 行为与边界

- 支持严格 UTF-8、UTF-16LE/BE、显式 GB18030/Shift_JIS；BOM 优先，拒绝 UTF-32、无法解码或不受平台支持的编码，不静默替换非法字节。尚无自动编码检测。
- 行边界对齐已审阅 LG `file-format-helpers.ts` 的 splitlines：合并 CRLF，保留中间空行，处理 Unicode 分行符；空输入产生零条目，尾部分行符不多造一条。原始 BOM/换行仍保存在原稿分块。
- 人工状态为 NONE / PROCESSED / EXCLUDED；PROCESSED 的空译文也是有效结果，其他状态导出回源。当前译文导出为 UTF-8、LF，双语导出按有效状态选译文，相同原译默认去重，空译文回源；完整 Markdown 格式能力仍未实现。
- 默认原稿上限 8 MiB、最多 10 万条、单条源/译文最多 65,536 UTF-16 单元、每页最多 100 条。这些是初始防护值，不是 G5 实测设备预算。TXT 原稿解析入口仍是有界 ByteArray；系统选择器先在 IO 线程有界读取，再交给解析器，并非流式解析。
- 外部 `.lnt` 导入、复制、发布后登记恢复和最小 nativeapp 界面已扩展到源码，具体边界见 §6；§70 已有单工程 ZIP 备份/恢复；完整应用级备份（设置/凭据/会话）、全对象 schema、完整持久运行恢复与 Service 仍未实现；后续 Room Chat/单条审批、网关和模拟翻译源码见 §7—8 与 HANDOFF §23—25。

## 5. 外部验证交接

新增的测试针对合成材料：真实 SQLite/Room 关闭重开、命令幂等/内容冲突、终态不回退、事务中断回滚、过期窗口/代际、分块/原稿摘要、空资产、空译文、快照与修订/回执、错误格式/版本/WAL、活动运行互斥、失效实例不拖垮最近列表、快照自增高水位，以及编码/行边界/预算和指纹。

以下只能由 PC/获授权 CI 执行，不在本机 Termux 执行：

```sh
./gradlew :core:domain:test :core:storage:testDebugUnitTest lintDebug assembleDebug
```

现有 CI 根任务选择器包含新增 storage 单测；报告上传增加 Room schema 目录，存储基线已由 #158 覆盖构建/测试证据；最低 API 及设备行为仍须验证。设备上的 SAF 关闭失败、锁屏/进程死亡、磁盘满、文件系统掉电和并发恢复另行验收；不以单测或本机静态审查替代。


## 6. 原生导入、复制、恢复与交付边界

外部导入先有界复制并关闭来源流，再只读校验自有 staging 副本。默认导入文件上限 256 MiB、历史行上限 200,000、指纹校验累计字符预算 64 Mi；TXT 条目/文本仍受 TextProjectLimits 约束。检查 SQLite 标识/头部、精确 v1—v6 表及索引定义、quick_check/外键、整数类型、原稿块顺序/大小/摘要、条目指纹、完整修订链、回执与修订对应关系和自增高水位。未知 schema 对象拒绝，不经复制静默丢弃。当前校验器支持本实现 v1—v6 及对应修订链，不支持 LG 文件改后缀导入；本段其余预算与校验以实际 NativeImportValidator 为准。

导入保留来源工程身份与历史事实，另建本地 UUID 和初始代际 1；导入历史不提供当前实例的可执行授权。创建命令和来源摘要实现重放去重，复制显式生成独立实例。registration.json 位于私有工程目录，由本机创建，最多 16 KiB；扫描限制 4096 个目录，逐个报告失败，忽略 staging，不删除异常目录。它不包含外部来源权限或自动重放指令。

nativeapp 的系统 OpenDocument 导入采用私有副本；CreateDocument 支持 `.lnt`、译文、双语和原稿，流的写入/flush/close 全部成功才提示完成。目标 URI 与发起时的工程身份绑定。失败保留本地事实及适配器临时产物；cacheDir 可能被系统回收，不能代替备份。清理仅针对 native_project_snapshots 中自有 snapshot-* 的 lnt/txt/source 普通文件，进行中复制快照受保护，不删除项目、原稿或其他缓存。

尚未实现来源 URI 关联/内容变化冲突策略、覆盖恢复与代际推进、完整应用级备份（设置/凭据/会话）、复杂格式、完整持久审批及完整后台执行接续；旧 LNT 数据迁移已按用户决定移出当前范围；后续单条审批、Agent 对话和模型翻译源码边界见 §7—8 与 HANDOFF §23—25。该导入切片当时未改变 `.lnt` v1，后续 Agent 切片引入 Room v2；当前 `.lnt` v5、Room v13，源码由工作区增量覆盖（#158 仅到 §101）。新导入/界面的 CI 与用户实机检查记录见 HANDOFF §22；本机仍只做静态审查。


## 7. 本地模拟翻译写回扩展

新增 TranslationCommitPort，限定真实 TRANSLATION 运行租约，批次外层事务复用 ProjectWriteStore 的条目指纹、修订和回执；批内冲突/取消整体回滚，前批事实保留，本模拟切片未改变 .lnt v1 与当时的 Room 版本。后续单条 Agent 审批见 §8；模拟入口不能作为 Agent 写权限。请求和运行边界见 [TXT_TRANSLATION](TXT_TRANSLATION.md)，实际进度见 HANDOFF §23；该切片已有指定提交 CI，新增设备验收仍待补。


## 8. Agent 单条审批与应用 schema v2

§24 切片引入 NativeAgentStore 与应用 Room v2（当前源码应用库为 v13，v1→v8 迁移源码已由 #158 覆盖，v9—v13 为工作区增量，2—13.json 导出与新版本设备升级仍待补）：本机历史/请求回执/不可变审批正文不写入 .lnt。显式允许一次后，存储内部 approvedEdit 验证真实 AGENT 租约，通过 ProjectWriteStore 保存条目与命令回执；approvedReceipt 另外校验完整命令摘要，供重启核对。新增应用 MIGRATION_1_2 不改变工程 schema（当时 v1）和旧阅读库；Room 2—13.json 尚缺，后续 PC/CI 生成审阅；v1→v8 运行迁移源码已由 #158 覆盖，仍不代替导出与设备升级验收。详见 [S2_AGENT_SLICE](S2_AGENT_SLICE.md)，实际进度见 HANDOFF §24。

## v3 多资产扩展基础

`NativeProjectFormat.SCHEMA_VERSION` 当前为 5。v3 引入 assets 顺序/启用/格式元数据，items 增加 asset/slot/tag/name/extra_field/skip_internal_filter 及扩展状态；project_settings、asset_receipts、asset_revisions 参与修订与校验；v4 增加 archived_items 与资产移除归档；v5 给 item_revisions 增加 name_source/name_translation 快照。ProjectAssetPort 提供追加/删除/重排/重置/设置/读回/导出，写入仍经写租约和命令回执；工作台已接追加及按资产导出，其余端口不等于完整管理 UI。旧版本升级在需要 v3 资产操作时经 ensureV3、v4 移除时经 ensureV4、姓名写事务前经 ensureV5 升级；旧读取/编辑仍有版本感知路径，不声称只要打开就强制升级。以下旧 v1 表结构是历史基础，当前 schema 以 NativeProjectDatabase/NativeImportValidator 为准。

## v4 资产移除归档

HANDOFF §58 将原生 `.lnt` 新建版本升至 4；§110 升至 5 并增加逐修订姓名快照，二者均在未提交工作区。archived_items 保留被移除条目完整文本/格式身份与指纹，item_revisions/回执保持不变；资产移除在同事务归档后删除活动资产和原始字节。快照包含归档；导入按活动/归档联合身份验证修订链、移除关系与高水位。活动加归档累计仍受工程条目上限约束。旧 v1/v2/v3 只在移除需要时事务升级，不要求只读打开就迁移。归档不参加翻译/统计/普通导出，外部源文件不触碰，暂未提供归档恢复 UI。源码未构建、未设备验证。

## 工程备份与恢复（§70，#158 覆盖）

`NativeProjectBackupService` 导出 ZIP：`manifest.json`（严格原生 envelope，含格式/版本/名称/来源身份/修订/字节数/SHA-256/时间）和 `project.lnt`。导出先经 `saveProject` 生成并校验 `.lnt`，再写压缩包；恢复只接受这两个精确条目，限制 manifest 1 MiB、工程 1 GiB，校验长度/摘要后经 `importProject` 创建新实例，不覆盖现有工程，也不恢复任何执行授权。凭据、Agent 会话、模型设置、预设/模板库不进入包。工作台已有 SAF 导出/恢复入口；运行中、编辑草稿、人工批次、未保存规则或加载不完整时禁用。该切片只覆盖单工程，不是全应用数据备份；源码已由 #158 覆盖，未设备验证。

## 项目集备份（§110，未构建）

`NativeProjectSetBackupService` 将全部可读工程逐个经 `saveProject` 写成 `.lnt` 快照，ZIP 内含严格 `manifest.json` 和 `projects/NNNN.lnt`；恢复先校验条目/长度/SHA-256，再按顺序 `importProject` 创建新的本地实例。它不覆盖现有工程、不携带执行授权、凭据、Agent 会话、模型设置、模板/预设库或应用设置；多工程恢复不是跨工程事务，部分导入失败会保留已导入工程并上报。工作台已接 SAF 导出/恢复入口。

## 任务人工终止裁决（Room v10，未构建）

NativeTranslationTaskStore 实现 TranslationTaskReviewPort，按固定工程实例/代际/Run 读取有界元数据快照；绑定任务状态、配置、恢复字段、已提交/剩余数及未知请求数的指纹。独占运行协调器写预留下重新核对，Room v10 同事务写入 native_translation_task_decisions 与 CANCELLED 终态。命令重放核对原指纹；既有目标、响应、暂存与回执不删除，不发送付费请求。重试、跳过和全任务队列执行仍待接线。

显式错误成员重试见 HANDOFF §114：有界读取原任务目标，按当前修订/ERROR 状态/指纹重新固定所选成员；新 Run、新预算和 RETRY_ERRORS 裁决关联在 Room v10 同事务建立。未知派发拒绝，原任务事实与成功条目不变，准备不自动付费执行。

Room 当前源码已到 v16：v10 任务终止、v11 逐条成员决策、v12 手动上下文摘要、v13 历史分支、v14 批量条目提案、v15 规则/提示词提案、v16 PDF 页提案；v9—v16 尚未构建验证，#158 不覆盖这些增量。翻译队列使用独立私有 AtomicFile（当前快照 v2，审计执行修订），不新增 Room 表。

## v6 审批条目批次与原生审计

`approved_item_receipts` 按实例/代际/命令保存绑定摘要、不可变提案正文、用户所选成员、实际接受/拒绝集合、修订及提交时间。v5→v6 只增加该表，不重写既有正文、姓名快照或回执。创建和逻辑快照包含全部 v6 表。

`NativeItemProposalStore` 在应用 Room 的 `native_item_proposals` / `native_item_proposal_members` 保存待决正文和轻量投影，一次消费与所选集合/绑定/时间同事务。`NativeTextProjectStore.approvedItems` 核对真实 Agent 租约和设备本地 CONSUMED 绑定后进入 ProjectWriteStore；后者在单个工程事务内校验修订/指纹、写入译文/姓名/状态、保存逐条修订与完整结果。允许部分接受的策略在准备时固定；用户未选或发生冲突的成员属于最终拒绝集合，不会利用旧命令稍后补写。

外部导入按正文重新计算绑定，检查所选/接受/拒绝集合、原值/新值指纹、实际修订/时间及完整修订链；缺失或篡改审计会拒绝发布。导入审计只保留事实，不生成 Room 授权。复制/恢复后的不同实例/代际不能复用旧批准。消费后缺少工程回执且旧执行者已经退出时记未提交终止；读取不确定则保留 CONSUMED，不自动重放。

条目提案不等于通用文件/规则/提示词/资产/PDF 写入口。当前实现与验证证据仍只记 HANDOFF。

## v7 PDF 页审批与原生审计

`approved_pdf_page_receipts` 按实例/代际/命令保存绑定摘要、资产 ID、不可变页决策正文、用户所选页码、实际接受/拒绝集合、修订及提交时间。v6→v7 只增加该表；创建和逻辑快照包含全部 v7 表。工程写回仍在单个事务内校验工程修订、资产 ID/SHA/页数、逐页 before 决策，写入 `project_settings`、SETTINGS 修订与通用 asset receipt，并同时写审批回执；`REJECT_BATCH` 在任一选中页冲突时整批拒绝，`ACCEPT_INDEPENDENT_OBJECTS` 只提交仍未变化的选中页。

Room `native_pdf_page_proposals` / `native_pdf_page_proposal_pages` 保存页级提案、绑定、所选集合、一次消费结果和轻量投影；正文只有页决策码，不含 PDF 原文。审批消费与工程提交不跨库持有事务：先查工程回执，再消费 Room；旧执行者退出后只核对不重放，复制/恢复后的实例/代际不能复用旧批准。权威 Room `16.json` 尚未由 KSP 导出。

## 规则/提示词审批的存储边界

Room `native_policy_proposals` 固定完整前后策略、实例/代际/Chat/Run/command、摘要、变更计数和一次消费时间；轻量列表不查询正文。工程写回仍使用既有 text_policy / policy_revisions / policy_receipts，不引入第二个策略写入者或新的 `.lnt` 回执编码。审批消费与工程提交不跨库持有事务；恢复仅核对已提交回执，不重放旧授权。


### PDF 页处置设置

PDF 页覆盖保存在现有 `project_settings` 中，不修改 `items.extra_field` 或历史指纹，不新增 SQL schema。设置 JSON codec v2 增加 `pdf_pages`：每项严格包含 `asset`（正整数资产 ID）、`sha256`（固定原稿摘要）、`decisions`（每原始页一字符，`-` 沿用导入、`k` 保留、`o` 移除、`t` 翻译）。没有覆盖时仍生成原 v1 编码，保留旧指纹与历史回执。预算为单 PDF 5000 页、最多 1000 个策略/总 100000 页、JSON 1 Mi 字符；这些是资源边界，尚非设备性能结论。

设置保存沿用写租约、全局预期修订、SETTINGS 修订及命令回执，验证当前资产的类型/摘要/条目数。已删除资产的设置保留为无效历史，不应用于其他资产；资产 ID 采用 AUTOINCREMENT，重新导入不继承原决策。备份/副本/导入保留设置，导入同时校验活动绑定与历史 SETTINGS payload。普通语言/姓名设置保存保留 PDF 策略。

翻译任务冻结、规划执行及显式失败重试共用 ProjectPageTranslationPolicy，KEEP/OMIT 不进入目标；沿用导入时读取原页决策。已暂停任务的续跑仍核对固定工程修订，设置变化不重放旧授权。导出以固定修订读取设置与条目，只临时投影 PDF 页 extraField（保留提取来源 digest），输出前再次核对工程修订。空白/扫描页设为翻译不自动 OCR；仍需保存非空译文，缺译文拒绝导出。重排文本不保留被替换页的图形/原版式，真实 PDF 输出及设备效果待验收。
