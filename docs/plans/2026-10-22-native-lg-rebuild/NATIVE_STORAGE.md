# 原生 TXT 存储契约

本文说明首个 `.lnt` TXT 切片的源码边界。实施进度、实际验证和未完成项只记入 [HANDOFF](HANDOFF.md)；本文不表示已经构建、接入 UI 或完成 S1 验收。

## 1. 格式与所有权

- 原生文件为 SQLite `.lnt`，`application_id = 0x4c4e5431`（LNT1），`user_version = 1`，对象/命令指纹编码版本为 1。打开时检查标识、版本与 SQLite 头部格式版本字节（18/19 必须为 1 = 回滚日志；2 = WAL 直接拒绝）。未来版本、WAL 与外来格式直接拒绝，不自动升级或清库。
  不能改用 `PRAGMA journal_mode` 断言：Android 每次可写打开都会把连接日志模式设成平台默认（AOSP 为 `TRUNCATE`），而 `DELETE/TRUNCATE/PERSIST` 不写进文件头，只有 WAL 持久化。快照保证的是"不是 WAL、经 ATTACH 逻辑复制"，不是某个连接的 rollback 子模式。
- 这是仅支持单 TXT 资产的原生 v1，不是全部工程模型的冻结版。PDF、规则、Chat、格式私有字段、兼容保留区等须通过后续 schema 版本/迁移扩展；不得将旧数据导入这个不完整目标而静默丢字段。
- 私有工程位于 `filesDir/native_projects/<本机生成的 UUID>/project.lnt`。路径不使用工程名称或外部文件名；逻辑资产路径经 `RelativePath` 验证，实际文件访问拒绝符号链接改写。
- 新应用索引使用独立 Room `lnt_native_app.db` v1，与旧 Room v6 完全分开。原生工程只保存来源工程身份；本地实例和恢复代际由 Room 索引拥有。当前只生成初始代际 1，没有覆盖恢复/代际推进入口。
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

领域端口在 `core/domain/.../TextProject.kt`；SQLite SQL 在 `core/storage/.../NativeProjectDatabase.kt`，手工事务在 `ProjectWriteStore.kt`；应用 IO 适配器是 `NativeTextProjectStore`。新 Room 的 KSP schema 导出目录为 `core/storage/schemas`，首份权威 JSON 必须在 PC/CI 生成、审阅并提交；本机不伪造导出结果。

`TextProjectPort` 提供：

1. `createTxt`：显式创建命令、名称、相对路径、原始字节与编码；先校验/解析，再发布私有副本和 Room 索引。
2. `registeredProjects` / `open`：重启后发现并重开登记实例。列表有界、按实例 ID 排序，尚不是完整的最近访问排序功能。每条返回 `RegisteredProject`：可读时带 `summary`，缺失目录、损坏文件或外来格式时带分类后的 `failure`，单个失效实例不会让整份最近列表失败；`open` 仍对指定实例抛出同一分类错误。
3. `items`：按 ID 游标读取，携带预期工程修订，过期窗口拒绝；每页最多 100 条。
4. `edit` / `receipt`：手工编辑、命令去重及持久结果查询。
5. `saveProject`：生成原生工程一致快照，写入调用方提供的流。
6. `exportTxt` / `exportOriginal`：分别导出当前有效译文和完全保留的原始字节。

调用方提供的输出工厂只负责打开目标流，在 IO 线程调用；适配器负责写入、flush、close 全部成功后才返回。它不是 SAF UI 或权限管理器。方法从 IO dispatcher 运行；调用方应在应用级注入同一个 RuntimePort，结束全部调用后再关闭适配器。

读方法同样以可写连接打开工程：SQLite 只能在可写连接上回滚热日志，崩溃后的写入恢复优先于省掉写标志。同一适配器的调用串行；两个实例不得共享同一私有工程目录。存储故障（目录不可创建、发布重命名失败、目标产物不新鲜）返回 `STORAGE_IO`，项目数据损坏返回 `CORRUPT_PROJECT`，不再混用 `check`/`error` 抛出的 `IllegalStateException`。

## 3. 创建、写入与快照

创建在专属 staging 目录内一次事务写入原稿和条目，关闭 SQLite 后发布私有目录，最后登记 Room。相同创建命令/相同摘要重放已有实例，不同内容返回冲突。取消在分块/条目循环中检查，失败仅清理本次未发布 staging；目录已发布而 Room 登记失败时保留材料。此窗口的孤立目录扫描/重关联尚未实现，不声称完整跨库崩溃恢复。

手工编辑取得 RuntimePort 的工程写租约；活动 Agent/翻译运行阻止人工写入。工程事务先按命令读已有回执并核对完整摘要；无回执时检查预期工程修订、对象存在性和基准指纹。成功时一起更新条目、递增修订、保存修订事实与回执；冲突或对象不存在也保存不写入终态。相同命令重试返回同一结果，不因稍后工程变化而变成另一种结果。提交前检查点观察到取消或发生事务故障时，内容、修订和回执一起回滚；若取消到达时已经跨过该检查点并提交，不回滚已提交事实，调用方按命令查询回执，不把取消异常当作“没有写入”。

这不是 Agent 授权。Agent `ProjectWritePort` 仍未接持久审批，不能将模型请求转换为 `ManualTextEdit` 绕过审批；生产工具注册表仍拒绝写工具。后续审批/批翻实现必须扩展同一 ProjectWriteStore 事务拥有者，不另造第二个写入者。

快照通过 SQLite 同连接 `ATTACH` 新文件，在事务中复制当前 v1 的全部表，再脱离并核对标识/版本与 `quick_check`；不复制活动数据库主文件，不依赖 API 24 尚不支持的 `VACUUM INTO`。`sqlite_sequence` 单独原样复制：显式 ID 的 `INSERT ... SELECT` 只会把自增高水位压到现存最大值，恢复快照后一旦删除过条目就会复用 item ID。外部流写入前已经结束数据库事务和运行写租约。导出失败不改变私有工程，保留临时产物；临时产物目前在 cacheDir，可能被系统回收，不能当作持久备份。当前同一适配器的调用仍串行，长时间目标写入可能让其他调用等待。

## 4. TXT 行为与边界

- 支持严格 UTF-8、UTF-16LE/BE、显式 GB18030/Shift_JIS；BOM 优先，拒绝 UTF-32、无法解码或不受平台支持的编码，不静默替换非法字节。尚无自动编码检测。
- 行边界对齐已审阅 LG `file-format-helpers.ts` 的 splitlines：合并 CRLF，保留中间空行，处理 Unicode 分行符；空输入产生零条目，尾部分行符不多造一条。原始 BOM/换行仍保存在原稿分块。
- 人工状态为 NONE / PROCESSED / EXCLUDED；PROCESSED 的空译文也是有效结果，其他状态导出回源。当前译文导出为 UTF-8、LF，不宣称完整 TXT/Markdown 或双语输出已实现。
- 默认原稿上限 8 MiB、最多 10 万条、单条源/译文最多 65,536 UTF-16 单元、每页最多 100 条。这些是初始防护值，不是 G5 实测设备预算。原稿入口仍是有界 ByteArray，未实现 SAF 流式导入。
- 当前只重开本机索引登记的私有实例。外部 `.lnt` 导入、工程复制/恢复、完整备份、`.lg` 双向转换、全对象 schema、Room Chat/审批/运行记录、UI、网关、批翻与 Service 均不在这个切片内。

## 5. 外部验证交接

新增的测试针对合成材料：真实 SQLite/Room 关闭重开、命令幂等/内容冲突、终态不回退、事务中断回滚、过期窗口/代际、分块/原稿摘要、空资产、空译文、快照与修订/回执、错误格式/版本/WAL、活动运行互斥、失效实例不拖垮最近列表、快照自增高水位，以及编码/行边界/预算和指纹。

以下只能由 PC/获授权 CI 执行，不在本机 Termux 执行：

```sh
./gradlew :core:domain:test :core:storage:testDebugUnitTest lintDebug assembleDebug
```

现有 CI 根任务选择器包含新增 storage 单测；报告上传增加 Room schema 目录，仍需实际运行确认 KSP、Robolectric native SQLite、最低 API 和签名构建。设备上的 SAF 关闭失败、锁屏/进程死亡、磁盘满、文件系统掉电和并发恢复另行验收；不以单测或本机静态审查替代。
