# 原生 TXT 存储契约

本文说明首个 `.lnt` TXT 切片的源码边界。实施进度、实际验证和未完成项只记入 [HANDOFF](HANDOFF.md)；TXT 存储基线已有指定提交的 CI 证据；新增导入/恢复与 nativeapp 接线的用户 CI/真机确认见 HANDOFF §22，不表示完成 S1 验收。

## 1. 格式与所有权

- 原生文件为 SQLite `.lnt`，`application_id = 0x4c4e5431`（LNT1），`user_version = 1`，对象/命令指纹编码版本为 1。打开时检查标识、版本与 SQLite 头部格式版本字节（18/19 必须为 1 = 回滚日志；2 = WAL 直接拒绝）。未来版本、WAL 与外来格式直接拒绝，不自动升级或清库。
  不能改用 `PRAGMA journal_mode` 断言：Android 每次可写打开都会把连接日志模式设成平台默认（AOSP 为 `TRUNCATE`），而 `DELETE/TRUNCATE/PERSIST` 不写进文件头，只有 WAL 持久化。快照保证的是"不是 WAL、经 ATTACH 逻辑复制"，不是某个连接的 rollback 子模式。
- 这是仅支持单 TXT 资产的原生 v1，不是全部工程模型的冻结版。PDF、规则、Chat、格式私有字段、兼容保留区等须通过后续 schema 版本/迁移扩展；不得将旧数据导入这个不完整目标而静默丢字段。
- 私有工程位于 `filesDir/native_projects/<本机生成的 UUID>/project.lnt`。路径不使用工程名称或外部文件名；逻辑资产路径经 `RelativePath` 验证，实际文件访问拒绝符号链接改写。
- 新应用索引使用独立 Room `lnt_native_app.db`（当前源码 v2，v1→v2 增加 Agent 记录表，尚待构建/迁移验证，见 S2_AGENT_SLICE），与旧 Room v6 完全分开。原生工程只保存来源工程身份；本地实例和恢复代际由 Room 索引拥有。当前只生成初始代际 1，没有覆盖恢复/代际推进入口。
- 原稿使用 `raw-v1` 原始字节分块，单块最多 64 KiB，保存原始长度及 SHA-256；不是 Zstd，也不冒充 LG schema。`.lg` 转换仍由后续 CompatibilityStore 实现。

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
3. `items`：按 ID 游标读取，携带预期工程修订，过期窗口拒绝；每页最多 100 条。
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
- 外部 `.lnt` 导入、复制、发布后登记恢复和最小 nativeapp 界面已扩展到源码，具体边界见 §6；完整备份、`.lg` 双向转换、全对象 schema、完整持久运行恢复与 Service 仍未实现；后续 Room Chat/单条审批、网关和模拟翻译源码见 §7—8 与 HANDOFF §23—25。

## 5. 外部验证交接

新增的测试针对合成材料：真实 SQLite/Room 关闭重开、命令幂等/内容冲突、终态不回退、事务中断回滚、过期窗口/代际、分块/原稿摘要、空资产、空译文、快照与修订/回执、错误格式/版本/WAL、活动运行互斥、失效实例不拖垮最近列表、快照自增高水位，以及编码/行边界/预算和指纹。

以下只能由 PC/获授权 CI 执行，不在本机 Termux 执行：

```sh
./gradlew :core:domain:test :core:storage:testDebugUnitTest lintDebug assembleDebug
```

现有 CI 根任务选择器包含新增 storage 单测；报告上传增加 Room schema 目录，存储基线已获 CI 构建/测试证据；本轮扩展仍需重新运行 CI，最低 API 及设备行为仍须验证。设备上的 SAF 关闭失败、锁屏/进程死亡、磁盘满、文件系统掉电和并发恢复另行验收；不以单测或本机静态审查替代。


## 6. 原生导入、复制、恢复与交付边界

外部导入先有界复制并关闭来源流，再只读校验自有 staging 副本。默认导入文件上限 256 MiB、历史行上限 200,000、指纹校验累计字符预算 64 Mi；TXT 条目/文本仍受 TextProjectLimits 约束。检查 SQLite 标识/头部、精确 v1 表及索引定义、quick_check/外键、整数类型、原稿块顺序/大小/摘要、条目指纹、完整修订链、回执与修订对应关系和自增高水位。未知 schema 对象拒绝，不经复制静默丢弃。它只支持本实现的单 TXT v1，不支持 LG 文件改后缀导入。

导入保留来源工程身份与历史事实，另建本地 UUID 和初始代际 1；导入历史不提供当前实例的可执行授权。创建命令和来源摘要实现重放去重，复制显式生成独立实例。registration.json 位于私有工程目录，由本机创建，最多 16 KiB；扫描限制 4096 个目录，逐个报告失败，忽略 staging，不删除异常目录。它不包含外部来源权限或自动重放指令。

nativeapp 的系统 OpenDocument 导入采用私有副本；CreateDocument 支持 `.lnt`、译文、双语和原稿，流的写入/flush/close 全部成功才提示完成。目标 URI 与发起时的工程身份绑定。失败保留本地事实及适配器临时产物；cacheDir 可能被系统回收，不能代替备份。清理仅针对 native_project_snapshots 中自有 snapshot-* 的 lnt/txt/source 普通文件，进行中复制快照受保护，不删除项目、原稿或其他缓存。

尚未实现来源 URI 关联/内容变化冲突策略、覆盖恢复与代际推进、完整备份、旧库迁移、`.lg` 双向兼容、复杂格式、完整持久审批及后台 Service；后续单条审批、Agent 对话和模型翻译源码边界见 §7—8 与 HANDOFF §23—25。本节导入切片未改变 .lnt v1；后续 Agent 切片已将独立应用 Room 升至 v2，见 §8；新导入/界面的 CI 与用户实机检查记录见 HANDOFF §22；本机仍只做静态审查。


## 7. 本地模拟翻译写回扩展

新增 TranslationCommitPort，限定真实 TRANSLATION 运行租约，批次外层事务复用 ProjectWriteStore 的条目指纹、修订和回执；批内冲突/取消整体回滚，前批事实保留，本模拟切片未改变 .lnt v1 与当时的 Room 版本。后续单条 Agent 审批见 §8；模拟入口不能作为 Agent 写权限。请求和运行边界见 [TXT_TRANSLATION](TXT_TRANSLATION.md)，实际进度见 HANDOFF §23；本轮新增代码尚待 CI/设备验证。


## 8. Agent 单条审批与应用 schema v2

当前源码新增 NativeAgentStore 与应用 Room v2：本机历史/请求回执/不可变审批正文不写入 .lnt。显式允许一次后，存储内部 approvedEdit 验证真实 AGENT 租约，通过 ProjectWriteStore 保存条目与命令回执；approvedReceipt 另外校验完整命令摘要，供重启核对。新增应用 MIGRATION_1_2 不影响 .lnt v1 和旧阅读库；生成的 Room 2.json 尚缺，后续 PC/CI 生成审阅，不能继承 v1 的验证结论。详见 [S2_AGENT_SLICE](S2_AGENT_SLICE.md)，实际进度见 HANDOFF §24。
