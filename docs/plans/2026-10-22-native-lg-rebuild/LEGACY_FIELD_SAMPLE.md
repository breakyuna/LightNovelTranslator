# 旧 LNT 数据字段映射样本（R36 早期产物）

本文是 [实施计划](IMPLEMENTATION_PLAN.md) §4.1 中“R36 迁移字段样本”的落地输入：在 R07 schema 冻结前，把旧 Room v6 的每类资料绑定到“新事实 / 转换 / 档案 / 需扩展”四类归属，并把无法表达的问题提前暴露。它不是迁移器、不是验收证据，也不表示已经访问或读取任何用户数据。

来源：只读静态阅读 `app/src/main/java/com/breakyuna/noveltranslator/data/model/`、`data/db/`、`data/prefs/`、`core/book/BookFileManager.kt`、`core/security/ApiKeyCipher.kt` 与 `app/schemas/`。未运行 Room 迁移测试、未构建、未读取真实库。

## 1. 旧库事实

| 项 | 值 |
|---|---|
| 数据库文件 | `light_novel_translation_platform.db`（`AppDatabase`，`version = 6`） |
| 实体数 | 24（见 `@Database(entities = [...])`） |
| 迁移链 | `MIGRATION_1_2` → `2_3` → `3_4` → `4_5` → `5_6`，全部在 `AppDatabase` 内注册 |
| 导出 schema | `app/schemas/…/1.json`、`3.json`、`4.json`、`5.json`、`6.json`——**没有 `2.json`** |

`2.json` 缺失意味着 v2 结构不能从导出文件重建。R36 的迁移验证必须用“合成 v1 库跑 1→6 连续链”，或在 PC/CI 明确记录 v2 无法导出的影响；不能假设可以从 schema 文件拼出 v2。旧库与新应用记录、`.lg` 工程事实三者的所有权见 [架构](ARCHITECTURE.md) §3.1。

## 2. 实体 → 目标归属

状态含义：`直接映射` 字段语义可直接落到新模型；`转换` 需要重排/重算或分拆；`档案保留` 只进可导出档案，不成为新产品事实；`需扩展` 目标模型尚不能表达，必须在 R07 前决定；`可再生` 不迁移。

| 旧实体（关键字段） | 目标 | 状态 |
|---|---|---|
| `books`（title/author/description/originalLanguage/coverPath/createdAt/updatedAt） | 工程名称、元数据、封面 Asset | 直接映射 |
| `books`（primaryEditionId/preferredReadingEditionId/hiddenFromShelf/shelfOrder） | 旧阅读平台状态，不进新工程事实 | 档案保留 |
| `editions`（name/type/language/sourceEditionId/isComplete） | 每个 Edition 独立形成工程/资产与来源映射；多 Edition 不合并成单一“当前译文” | 转换 |
| `logical_chapters`（chapterIndex/canonicalTitle）+ `edition_chapters`（title/contentFileName/wordCount/isAvailable） | Item/Asset 的原始定位与来源顺序；`contentFileName` 指向 `books/book_<id>` 内文件 | 转换 |
| `edition_segments`（baseText/sourceHash） | Item 源文 + 基准指纹；`sourceHash` 是 `ObjectFingerprint` 候选，但算法/长度需版本化 | 转换 |
| `edition_segment_mappings`（1:1…N:N） | 旧平台对齐关系；新模型用 Item 身份 + 原稿定位表达 | 需扩展 |
| `segment_revisions`（revisionType/text/priority/sourceRevisionId/isActive/note/createdAt） | 人工修订事实；`priority` 是旧引擎合并顺序（MANUAL_EDIT 400 … AUTO_REPAIR 100） | 需扩展 |
| `translation_projects_v2`（语言/模型/风格/协议版本/模式/并发/前文/范围/state） | 工程设置镜像与来源映射；`metadataEnabled`、`parallelChapters`、`rangeStart/End`、`highQualityReview` 是旧章节引擎参数，新引擎不套用 | 转换 |
| `translation_prompt_profiles`（4 段提示词 + includeMetadataRules + version） | `PromptConfig` 历史版本；旧提示词作为来源材料，不自动成为新协议提示词 | 直接映射 |
| `lexicon_entries`（sourceTerm/targetTerm/kind/category/aliases/notes/caseSensitive/exactMatch/priority/enabled/source/reviewStatus） | 术语规则条目（姓名/正文范围、备注、启用、排序）；`aliases` 是逗号分隔字符串 | 转换 |
| `story_memory`（factKey/factValue/entities/sourceChapterIndex/lastUpdatedChapterIndex/conflictNote） | 带来源材料保留，不强制进入新翻译协议 | 档案保留 |
| `chapter_memory`（summary/entities/stateChanges/newFacts/unresolvedThreads/repairState） | 同上 | 档案保留 |
| `context_snapshots`（stablePrefix/fingerprint/各版本号） | 旧协议上下文快照，仅审计 | 档案保留 |
| `reader_progress`（阅读位置、显示模式、字体、背景等全部字段） | 迁移档案；新产品不重建阅读器 | 档案保留 |
| `provider_cache_records`（fingerprint/hit/miss/estimatedSavedCost/expiresAt） | 供应商缓存是再生数据 | 可再生 |
| `platform_translation_runs` / `_batches` / `_request_logs`（状态、用量、费用、错误、prompt/response 正文、attemptTrace） | 用量/费用/状态进入统计与审计；请求正文可能含小说正文，默认不进新日志 | 转换 + 档案保留 |
| `quality_rule_sets`（type/enabled/mode/entriesJson/revision） | 文本保护 / 译前替换 / 译后替换三个规则槽位；`entriesJson` 是 Moshi JSON，需按新规则模型逐项转换，`revision` 保留作冲突依据 | 转换 |
| `agent_sessions`（title/bookId?/translationProjectId?/providerId?/modelName?/state/lastMessagePreview） | AgentChat 历史；归属可为空，见 §3 | 转换 + 档案保留 |
| `agent_messages`（role/content/toolName/toolCallId/toolArgsJson/toolResultJson/isError/token 列/createdAt） | 旧文本协议历史；顺序与角色保留、工具调用/结果按身份配对；token 按消息累计 | 转换 |
| `LexiconCandidateAggregateEntity` | AI 术语候选聚合中间态 | 需扩展（是否可再生待评审） |
| `api_providers`（name/providerType/baseUrl/apiKey/selectedModel/价格/currency/temperature/maxContextTokens/isDefault/customHeadersJson） | `ModelConfig` + 凭据；`apiKey` 必须经旧解密边界迁移 | 转换 |

## 3. 文件、凭据与运维配置

- `BookFileManager` 目录：`filesDir/books/book_<id>`（正文、封面、`shared/images`）、`cacheDir/books/book_<id>`（可再生）、`exports/book_<id>`（产物）。迁移前必须做一致副本；新原稿/工作区/产物按 [架构](ARCHITECTURE.md) §3.5 分配唯一拥有者，禁止绕过 `BookFileManager` 清理源数据。
- `edition_chapters.contentFileName` 与文件清单必须一起核对；缺文件按 PR09 的“缺材料失败、保留可重试材料”语义报告，不能静默生成缺章工程。
- 凭据：`ApiKeyCipher` 只作迁移来源，解密失败保留旧密文并要求重新配置，不写日志、不回显 Key。
- `RequestPacingPreferences`（SharedPreferences，键 `pacing_<providerId>_rpm` / `_concurrency`，0 表示未配置）→ 新调度配置；必须先建立 providerId 映射，再转换覆盖值。

## 4. 会话归属（AG07 / R36）

`agent_sessions` 的 `bookId` 与 `translationProjectId` 都可为空，存在三种情况：

1. 同时绑定工程 → 可无歧义继续（仍需转换活动分支与工具配对）；
2. 只绑定书、书下多个工程 → 作为应用历史档案打开，要求用户显式选择目标工程；
3. 无任何绑定 → 只作档案，不默认取第一个工程。

不允许“取第一个工程”这类歧义回落；旧执行记录只用于回看，不重放副作用。

## 5. R07 schema 冻结前必须回答

1. 新模型是否保留 N:N 段落映射与修订优先级？若否，只能档案保留，需确认不丢人工修订事实。
2. 多 Edition → 多工程时，“主版本/首选阅读版本”语义是否进入新工程元数据？
3. 请求正文与旧提示词是否进入新审计？涉及正文外泄边界与 Debug 默认关闭。
4. `sourceHash` 的算法、长度、版本能否直接充当 `ObjectFingerprint`？不能则需在迁移时重算并记录。
5. 术语候选聚合是否可再生，决定丢弃或档案保留。
6. 无归属会话的档案打开入口与目标工程选择交互（U02/U09）归属哪个页面。

## 6. 验证方式（合成夹具）

- 合成库覆盖 v1→v6 各版本、多 Edition、人工修订、缺正文/图片、密钥无法解密、无归属会话、空间不足、取消与重复启动。
- 断言源库/源文件 hash 不变；逐字段核对目标事实与来源映射；失败只影响当前 staging。
- 每个写入阶段注入崩溃，验证可取消、可重入、可恢复；不读取用户真实小说作为回归数据。
- 迁移器与测试尚未实现；本样本只固定字段语义与开放问题。

## 7. 未验证边界

本文为静态阅读旧实体、DAO、schema 清单与文件管理器所得，未构建、未运行 Room 迁移测试、未访问或迁移任何真实用户数据；不登记任何迁移或功能 ID 为完成。
