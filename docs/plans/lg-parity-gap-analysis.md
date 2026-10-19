# LG → LNT 功能对标差距分析（LinguaGacha-Termux vs LightNovelTranslator）

> 类型：只读审计 + 路线图输入文档
> 审计对象 A：`/data/data/com.termux/files/home/LinguaGacha-Termux`（TypeScript / Electron 桌面端，"次世代 AI 翻译器"）
> 审计对象 B：`/data/data/com.termux/files/home/LightNovelTranslator`（Android / Kotlin / Compose，"轻小说阅读 + AI 翻译"）
> 审计日期：2026-10-19
> 范围：LG 中与**小说 / EPUB 翻译**相关的功能点（游戏引擎格式族、字幕、PDF 等只在"不建议移植"清单中登记）
> 结论性质：所有判断均附 LG 或 LNT 的文件路径证据；无法从代码直接确认的一律标注「待验证」。

---

## 0. 阅读前必读：已知并行重构状态（本文的判定基准）

`docs/plans/2026-10-19-master-refactor-plan.md` 登记的并行任务在 `git status` 中可验证（未提交的工作区改动）：

| 任务 | 磁盘证据 | 本文标注 |
|---|---|---|
| A. LG 文本机制移植 | `app/src/main/java/com/breakyuna/noveltranslator/core/text/**` 为未跟踪新目录，含 11 个实现文件 + 10 个测试文件 | **重构中** |
| C. 引擎重构 | `core/translation/TranslationContinuity.kt`、`ProviderPromptCache.kt` 未跟踪；`BookTranslationEngine.kt`、`TranslationProtocol.kt`、`AppDatabase.kt` 已修改；`app/schemas/.../5.json` 未跟踪 | **重构中** |
| D. Koog Agent 层 | `core/agent/koog/**`、`core/agent/AgentToolHost.kt`、`ui/screens/agent/**`、`data/model/AgentEntities.kt`、`data/db/AgentDao.kt` 未跟踪 | **重构中** |
| F. ML Kit 端侧辅助 | `core/ondevice/**` 未跟踪；`app/src/main/assets/ondevice/prompts/` 未跟踪 | **已具备（未接线）** |

因此下文凡是落在上述四个范围内的能力，**不得标注"缺失"**；标注为"缺失"的是 LNT 完全没有对应文件、也没有在任何计划中登记的能力。

一个关键事实需要在读表前明确：**A/C/D 三个任务的产物目前大部分只到"模块就绪"，尚未接入引擎或 UI**。静态核对结果：

- `core/text/**` 11 个文件中，只有 `PrecedingContextBuilder` 被 `BookTranslationEngine.kt:1064` 实际调用；`TranslationSourcePreparer`、`PostTranslationRestorer`、`ResourceReferenceSlots`、`TextProtectionPresets`、`TranslationQualityRules`、`GlossaryCompiler`、`TextReplacements` 在主源码中**没有任何调用点**（只有 `app/src/test/.../core/text/**` 的测试引用）。
- `TranslationProjectV2Entity.parallelChapters / continuityMode / metadataEnabled / promptCacheEnabled`（`data/model/ReaderPlatformEntities.kt:186-195`）已在 `AppViewModel.updateProjectConfig`（`AppViewModel.kt:403-432`）与建工程入口（`AppViewModel.kt:756-781`）中可写，但 `ui/**` 中**没有任何 Composable 暴露这四个开关`（`ui/screens/workspace/BookWorkbenchDetailScreen.kt` 只操作 `maxBatchChapters`，见 2097/2113/2408/2569 行）。
- `ProviderPromptCacheCapability` 已在 `AppViewModel.kt:140-150` 注入引擎。
- `core/ondevice/OnDeviceAssistGateway` 在 `ui/viewmodel/AppViewModel.kt` 中**无任何引用**（`rg onDevice|OnDevice` 仅命中自身目录），确认未接线。
- `ui/screens/agent/AgentChatScreen.kt` 存在，但 `ui/navigation/**` 无路由引用（`rg AgentChatScreen` 仅命中该文件自身），确认 Agent UI 未接入导航。

> 这条事实是全文最重要的输入：**LNT 与 LG 的差距 currently 不是"设计差距"，而是"接线差距 + 配置层差距 + 校对产品层差距"**。

---

## 1. 结论摘要：LNT 相对 LG 的真实差距 Top 10

按"对长篇小说/EPUB 翻译质量与可用性的实际影响 × 当前确实不存在"排序。

### T1. 没有"译前/译后文本替换"这一层产品能力（LG 的 `pre_replacement` / `post_replacement`）

LG 把译前替换和译后替换做成与术语表平级的四个质量规则槽位之一（`src/domain/quality.ts:85-90`、`125-170`），各有启用开关、条目集、修订号、预设目录与默认预设设置键，并在 pipeline 的固定位置执行：译前在"资源投影 → ruby 清理 → 保护分析"之后（`src/shared/text/translation-source.ts:114-121`），译后在逐行恢复之后（`src/backend/batch-translation/work-unit/pipeline/translation-post-pipeline.ts:86-96`）。

LNT 侧：`core/text/TranslationSourcePreparer.kt:225-304` 已移植 `TextReplacementEntry` / `TextReplacements` 数据类与执行语义，`PostTranslationRestorer.kt:90-93` 已留好调用点。但**数据库没有规则表、设置页没有编辑器、没有预设、没有导入导出**（`rg "替换"` 在 `data/` 与 `ui/screens/settings/` 下无业务命中，只命中 `core/text` 自身注释与 `LexiconReplacementService` 的术语替换）。

差距本质：LNT 有"引擎原语"，没有"用户可配置的产品层"。这是 LG 文本保真体系里最容易被低估的一块——LG 内置预设 `builtin/post_translation_replacement/preset/02_首尾半角引号转直角引号.json`（把行首 `"` 变 `「`、行尾 `"` 变 `」`）就是靠它落地的。

### T2. 没有"文本保护的用户可配置层"（模式 / 自定义规则 / 按格式预设）

LG 的 `text_preserve` 槽位有三态模式 `off / smart / custom`（`src/domain/quality.ts:82`），`smart` 按 `text_type` 选择内置预设（`src/shared/text/text-preserve-rules.ts:29-36`、`143-172`），`custom` 使用用户规则；基础层 `builtin/text_preserve/preset/base.json`（`<br>`、`\s`、`lg-uri/\d+`）在所有模式下始终叠加。另有 KAG / RENPY / RPGMAKER / WOLF 四个引擎预设。

LNT 侧：`core/text/TextProtectionRules.kt`（重叠裁决、`transformUnpreserved`）与 `core/text/TextProtectionPresets.kt:15-26`（base 三条规则）已移植，且注释明确说明"未移植 KAG/RENPY/RPGMAKER/WOLF 四个引擎预设，LNT 面向小说正文"——这个取舍本身是对的。但 **mode 三态、用户自定义规则、持久化、UI 全无**；现有 `core/translation/TranslationTextProtection.kt:12-15` 是另一套硬编码正则（`[IMG:...]`、`{{...}}`、`${...}`、HTML 标签、HTML 实体），与 `core/text` 的新体系**并存但未打通**。

### T3. 没有"全书写校对台"（警告码 / 筛选 / 统计 / 批量替换 / 清空）

LG 校对页是独立产品面：6 个稳定 warning code（`src/shared/proofreading/proofreading-types.ts:9-16`）、按 code 的计数摘要（同文件 `267-287`）、按 outcomes / files / glossary_entry_ids / include_without_glossary_miss 的筛选（`src/backend/proofreading/proofreading-query-service.ts:189-203`）、排序、搜索范围（`253-255`）、窗口化懒加载查询 `sync / list / window / row_ids_range / row_index / items_by_row_ids / context / filter_panel / warning_summary`（同文件 `46-136`）、后端执行的批量替换（`src/backend/proofreading/proofreading-service.ts:95-151`）、清空译文并可选择重置状态（同文件 `153-193`）、人工三态 `NONE / PROCESSED / EXCLUDED`（`src/domain/item.ts:17`）。

LNT 侧对等物分散在三处且都不是"校对台"：
- 引擎内 QA：`core/translation/TranslationProtocol.kt:601-746`（`DeterministicTranslationQa`，约 15 个 code）+ `repairScope`（`748-805`）；
- 阅读器快捷编辑：`ui/screens/reader/PlatformReaderScreen.kt:1309-1327`（`QUICK_EDIT` 模式 + `QuickEditor`）；
- 术语替换：`core/translation/LexiconReplacementService.kt`（预览 → 确认 → 撤销，写 `USER_CONFIRMED_REPLACEMENT` Revision）。

**缺的是"跨全书的问题清单视图"**：没有 warning code 词表暴露给 UI、没有按 code 的筛选与计数、没有"跳到下一处问题"、没有全书批量替换/清空。`core/text/TranslationQualityRules.kt:7-22` 已定义 `QualityIssueCode`（FOREIGN_RESIDUE / PUNCTUATION_STRUCTURE / TRANSLATION_SIMILAR / LINE_COUNT_MISMATCH / PARAGRAPH_COUNT_MISMATCH），但主源码无调用点。

### T4. 译文"生成/写回"环节缺失（LG 的 translation generation）

LG 的翻译终点是"生成译文文件"：`/api/translation/files/generate`（`src/frontend/features/translation-generation/use-translation-generation-flow.ts:163-166`），生成前先取 warning 摘要做预检并把"去 Agent 审校"作为一等出口（同文件 `184-196`），生成后给出下载清单（`50-69`）。写回侧按格式各有 writer：`src/backend/file/epub/epub-writer.ts`（AST 级保留）、`md-v2-format.ts`、`subtitle/srt-format.ts`、`subtitle/ass-ssa-format.ts`、`renpy/writer.ts`、`kvjson/`、`messagejson/`、`txt/txt-format.ts`；双语导出与去重开关在 `epub-writer.ts:338-342`、`src/domain/setting.ts:50-52`。

LNT 侧只有 `core/exporter/EditionExporter.kt:24-84`（`exportTxt` / `exportEpub`，原子写、图片保留、按 logicalSegment 回退原文）。**没有双语导出、没有"原格式写回"概念、没有生成前质量预检、没有导出中心**。需要注意：LNT 的 Edition 模型在哲学上优于 LG 的 item 模型，"写回原格式"不应照搬（见 §5），但"双语导出"和"生成前预检"是真实缺口。

### T5. 术语表的产品层不完整（导入导出 / 预设 / 重复冲突预览）

LG：`src/shared/quality/quality-rule-import.ts` 提供完整的导入预览（`preview_quality_rule_import`，`93-122`），区分 `same-target / existing-target-empty / incoming-target-empty / different-target` 四种重复（`258-275`），产出 skip 与 overwrite 两份快照供页面确认（`37-46`）；预设见 `builtin/glossary/preset/`（`01_示例.json`、`02_常用菜单文本.json`）；Agent 技能 `builtin/skills/glossary/`（含 `references/extract.md`、`scripts/match-literals.mjs` 等）实现"一键生成术语表"。

LNT：术语模型其实更丰富（`LexiconEntryEntity` 有 kind / category / aliases / notes / caseSensitive / exactMatch / priority / enabled / source / reviewStatus，`data/model/ReaderPlatformEntities.kt:268-285`），候选聚合与投票 `data/model/LexiconCandidateAggregate.kt`（`LexiconCandidateVoting`、`LexiconCandidateNoiseFilter`、`LexiconCandidateImportPlanner`）也相当完整，`AppViewModel.kt:1165+` 的 `scanGlossaryForBook` 覆盖全范围分批串行。**缺的是文件级导入导出与预设包**：`rg "import.*[Gg]lossary|export.*[Ll]exicon"` 在主源码无命中（仅 `core/logger/SystemLogger.kt` 的 Moshi 命中）。

### T6. 请求级限流 / 多密钥轮换 / 自动并发升降档缺失

LG `src/backend/batch-translation/core/translation-request-scheduler.ts` 是完整的请求调度器：多 API key 轮换与冷却（`KEY_COOLDOWN_STEPS_MS = [15s, 30s, 60s]`，`18` 行）、`ready / draining / cooling / probing` 四态（`26` 行）、429 时自动把并发减半并升版本隔离（`295-307`）、成功后 +1 升档（`298`）、`AUTO_CONCURRENCY_INITIAL = 4 / MIN = 1 / MAX = 16`（`src/backend/batch-translation/core/request-rate.ts:3-5`）；`TranslationRequestRate` 提供跨任务复用的 RPM / 隐藏 RPS 令牌桶（同文件 `15-76`）。

LNT：`core/llm/LlmGateway.kt:157-180` 的 `RetryPolicy` 只做"单请求指数退避 + 分类 maxAttempts"；`BookTranslationScheduler`（`core/translation/BookTranslationEngine.kt:46-73`）只做"跨书 3 并发 + 同书 Mutex"；`parallelChapters`（1..5）是同书内有界并发。**没有全局 RPM 闸门、没有密钥池、没有根据 429 自动降档的机制**。Android 上这不该照搬（见 §5），但"限流"与"多 key"是真实缺口。

### T7. 提示词"风格预设库"与"思考块开关"缺失

LG 的翻译提示词是四段模板 `prefix.txt / base.txt / thinking.txt / suffix.txt`，按 `app_language` 选 zh/en（`src/backend/batch-translation/work-unit/work-unit-prompt-builder.ts:128-152`、`262-275`），占位符 `{source_language}` / `{target_language}` / `{translation_output_format}`（`148-151`）；`thinking.txt` 是 `<why></why>` 结构化思考块（`builtin/translation_prompt/template/zh/thinking.txt`），由 `prompt_enhancement_enable` 开关控制是否拼入（`work-unit-prompt-builder.ts:136-138`，设置键见 `src/domain/setting.ts:49`）。风格预设：`builtin/translation_prompt/preset/01_文言文风格.txt`、`02_文学措辞风格.txt`。

LNT：`core/translation/TranslationProtocol.kt:60-65` 有 `defaultPromptProfile()`，`127-151` 的 `renderSystemPrompt` 支持 `{{SOURCE_LANGUAGE}}` / `{{TARGET_LANGUAGE}}` / `{{STYLE_GUIDE}}` 占位符并强制追加 `nonNegotiableProtocolRules()`（这个设计比 LG 更稳），`PromptProfileEntity` 做版本化（`data/model/ReaderPlatformEntities.kt:242-261`），`ui/screens/workspace/BookWorkbenchDetailScreen.kt:2155` 有 `PromptProfileEditorDialog`。

**缺：预设库（文言/文学/直译等多风格一键切换）、用户可见的"思考/推理强度"开关**。LNT 的 `reasoningEffort` 在引擎里被硬编码为 `"low"`（`BookTranslationEngine.kt:1409`、`1826`、`2087`、`2403`），`LlmRequest.reasoningEffort` 字段存在（`LlmGateway.kt:49`）但无 UI 入口、无项目级配置。

### T8. 质量规则命中统计缺失

LG `src/shared/quality/quality-statistics.ts` 为每条规则统计"命中的不同 item 数"并稳定抽取最多 2 个例句 item（`43-80`、`169-205`，用 FNV-1a 哈希做抽样种子避免总是取首尾）；字面量规则共用一次 `compile_literal_patterns` 扫描，正则规则单独编译（`83-148`）；保护段内的临时 token 命中不计入（`150-166`）。这让用户能在翻译前判断"这条规则到底命中了多少文本"。

LNT 无对应物（`rg quality-statistics|hitsByEntry` 无命中）。对小说的价值：判断术语表某条是否值得加、某条保护规则是否误伤。

### T9. 预过滤与清洗开关缺失（ruby / 重复源 / 语言过滤 / MTool 优化 / 伪名注入）

LG 有一组"翻译前正文治理"开关，集中在 `src/domain/setting.ts:46-53` 与实验室页（`src/frontend/pages/laboratory-page/use-laboratory-page-state.ts:15-19`）：
- `clean_ruby`：注音清理（`src/shared/text/text-ruby-cleaner.ts`）
- `skip_duplicate_source_text_enable`：重复来源条目过滤
- `mtool_optimizer_enable`：MTool 文本预过滤优化
- `deduplication_in_bilingual` / `write_translated_name_fields_to_file`：导出侧
- 语言预过滤与规则预过滤：`src/shared/prefilter/language-prefilter.ts`、`item-prefilter.ts`
- 控制码伪名注入：`src/shared/text/text-fakename-injector.ts`（把 `\nN[1]` 这类控制码伪装成中文伪名再进 prompt，降低模型误识别为术语的概率）

LNT：`core/parser/EpubParser.kt:252-273` 已把 `<img>` / `<svg>` 替换为 `[IMG:文件名]` 并进 `TranslationTextProtection` 保护；`ui/screens/reader/ChineseVariantConverter.kt` 提供繁简转换（阅读侧）。**缺：ruby 清理开关（EPUB 日语小说常见）、重复 Segment 跳过、章节级预过滤报告**。

### T10. Agent 层能力面窄于 LG（审批 / 技能 / 附件 / 队列 / 压缩）

LG Agent（`src/backend/agent/`）：
- 工具 6 个：`ask_user` / `batch_item_translation` / `read_skill` / `web_search` / `workspace_apply` / `workspace_run`（`src/backend/agent/tools/`）
- 工作区契约：datasets + changes 的 Schema 化文件协议（`src/backend/agent/workspace/schema.ts`、`tools/contract.ts`）
- 审批：`manual / auto` 两态，手动模式展示实际变更类别与数量（`src/domain/setting.ts:29`、`PRODUCT.md` 的 Capabilities 段）
- 10 个内置技能（`builtin/skills/`：agent-charter / epub / glossary / pdf / roleplay / rpgmaker / skill-creator / text-preserve / translation / writing-guide）+ 技能编辑器页面（`src/frontend/pages/skills-page/`）
- 会话增强：上下文压缩（`agent-compaction.ts`）、输入队列（`agent-input-queue.ts`）、图片附件（`agent-image-service.ts`）、响应批注（`agent-response-annotation.tsx`）、工具详情弹窗、可修订消息

LNT（重构中）：`core/agent/AgentToolHost.kt` 定义 9 个工具（`list_books / get_book_status / start_translation / pause_translation / scan_glossary / resplit_chapters / get_recent_log / ask_user / update_translation_config`），已在 `AppViewModel.kt:214` 由 `AgentToolHostImpl` 实现；`core/agent/koog/**` 提供 `LntPromptExecutor`（`LntPromptExecutor.kt:42-128`，把 Koog `PromptExecutor` 桥到 `LlmGateway`，工具走文本协议）、`AgentToolProtocol`、`AgentSession`、`AgentTurnStrategy`、`RoomAgentSessionStore`；`AgentEntities` + `AgentDao` 做持久化。

**缺：写入审批模式（LNT 目前没有"Agent 改配置/启翻译前必须批准"的概念）、技能系统（SKILL.md 式可扩展指令包）、图片附件、消息队列、上下文压缩 UI、Agent UI 未接入导航**。

---

## 2. 功能对标总表

图例：**已具备** = LNT 有对等实现且已接线；**部分具备** = 有原语/模块但缺产品层或未接线；**缺失** = LNT 无对应物且无计划登记；**重构中** = 属于 A/C/D/F 并行任务范围。

工作量单位：人日（1 人日 ≈ 1 个工作日，含自测；不含全仓静态审查与真机验证）。

### 2.1 翻译执行

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| E1 | 多 API key 轮换 + 故障冷却（15/30/60s） | `src/backend/batch-translation/core/translation-request-scheduler.ts:18,66-77,310-320` | 缺失 | LNT 无密钥池概念；`ApiProviderEntity` 单 key | P1 | 3 | 部分（建议改为"密钥组 + 轮换"，见 §5） |
| E2 | 429 自动并发降半 + 成功升档 + 版本隔离 | 同文件 `295-307` | 缺失 | LNT `RetryPolicy` 只退避不降并发；`parallelChapters` 是静态配置 | P1 | 2 | 部分（建议改为"按错误类别调整在飞章数"） |
| E3 | RPM / RPS 全局限流（跨任务复用速率时钟） | `src/backend/batch-translation/core/request-rate.ts:15-99` | 缺失 | LNT 无任何速率闸门；手机端网络与计费敏感，反而更重要 | P0 | 3 | 是（必须，但阈值要保守） |
| E4 | 精确 token 计数切块（planning worker + 缓存 + 同文共用） | `src/backend/batch-translation/planning/translation-planner.ts:42-70,136-195`；`token-counter.ts`；`token-count-cache.ts` | 部分具备 | LNT 用 `TokenBudgetPlanner`（`TranslationProtocol.kt:502-539`）做字符级估算 + `MAX_PROTOCOL_SEGMENTS_PER_REQUEST = 48`（`BookTranslationEngine.kt:132`）+ chunk 兜底（`788`、`2342-2344`）。估算而非精确计数 | P2 | 3 | 是（可换真实 tokenizer） |
| E5 | 行数上限切块（`line_limit = max(8, token/16)`）+ 同 `file_path` 不分块 | 同文件 `82,95-100` | 部分具备 | LNT 以章为天然边界，`file_path` 边界不适用；但"同章不拆"原则一致 | P2 | 0 | 不适用（章即边界） |
| E6 | 前文截取（句末标点边界 + 行数阈值 + 同文件） | 同文件 `200-228`；`src/domain/setting.ts:47` | 重构中 | `core/text/PrecedingContextBuilder.kt`（76 行）已移植并在 `BookTranslationEngine.kt:1064` 接线，`PRECEDING_SOURCE_LINES = 24` / `PRECEDING_SOURCE_TAIL_CHARS = 900`（`BookTranslationEngine.kt:136-138`） | — | 0 | 是 |
| E7 | 对半切重试（2 轮）+ 单条重试（1 次） | `src/backend/batch-translation/planning/translation-retry.ts:7-42` | 部分具备 | LNT `runQaRepairLadder`（`BookTranslationEngine.kt:1948-2028`）做"局部段对半切 + 全章兜底"，`MAX_PAID_REPAIRS_PER_CHAPTER = 3`（`142` 行）。语义不同但更细（段级而非条目级） | P2 | 0 | 是 |
| E8 | 瞬时错误退避重排队（15/30/60s，尊重 Retry-After） | 同文件 + `src/domain/batch-translation.ts:149-157` | 重构中 | `RECOVERABLE_BACKOFF_MS`、`MAX_RECOVERABLE_BACKOFF_MS = 120s`、`MAX_RECOVERABLE_CHAPTER_FAILURES = 5`（`BookTranslationEngine.kt:139-146`、`144-146`）；`delayForRequeue` 可取消可暂停（`686-700`） | — | 0 | 是 |
| E9 | run 状态机 `idle/requested/running/stopping/stopped/done/error/interrupted` | `src/domain/batch-translation.ts:31-40` | 部分具备 | LNT 项目状态 `IDLE/RUNNING/PAUSED/CANCELLED/COMPLETED/COMPLETED_WITH_WARNINGS/COMPLETED_WITH_ERRORS/FAILED/INTERRUPTED`（`BookTranslationEngine.kt:134`、`396-403`）；缺 `stopping` 中间态与"崩溃后 interrupted 恢复" | P2 | 2 | 是 |
| E10 | 启动模式 `new / continue / reset` | 同文件 `42`；`resolve_batch_translation_start_mode` `198-211` | 部分具备 | LNT 按 scope（`TranslationMode.FULL_BOOK / CHAPTER_RANGE / SEAMLESS`）+ 已完成章节跳过实现续跑；缺"重置全部/重置失败"显式命令 | P1 | 2 | 是 |
| E11 | 取消来源 `user / parent / shutdown` | 同文件 `59` | 部分具备 | LNT `TranslationControlSignal`（`LlmGateway.kt:96-145`）区分 paused/cancelled 且 pause 会 drain 在飞请求；缺"父任务/关机"来源 | P2 | 1 | 是 |
| E12 | command_id 去重（回包丢失后重复提交） | 同文件 `179-191` | 缺失 | LNT 无命令幂等层；`AppViewModel` 直接驱动引擎 | P2 | 2 | 是 |
| E13 | 进度 token 三分（input / reasoning / output） | 同文件 `68-79` | 部分具备 | LNT `LlmResult.promptTokens / completionTokens`（`LlmGateway.kt`）二分；`PlatformRequestLogEntity` 记录 usageSource `ACTUAL/ESTIMATED/UNKNOWN`。reasoning token 未分离 | P2 | 2 | 是 |
| E14 | 请求恢复事实展示（retry_count / retry_at） | `src/backend/batch-translation/core/translation-request-scheduler.ts:183-197` | 部分具备 | LNT `logChapterRequeue` / `logChapterRetriesExhausted`（`BookTranslationEngine.kt:655-680`）落系统日志；UI 无"正在等待重试"专用态 | P2 | 1 | 是 |
| E15 | worker 线程池（planning / translation 分离） | `src/backend/batch-translation/planning/planning-worker-pool.ts`、`work-unit/translation-worker-pool.ts`、`work-unit-worker-entry.ts` | 不适用 | Node `worker_threads` 专属；LNT 用 Kotlin 协程 + `Dispatchers.IO`（`BookTranslationEngine.kt:594-640`）已对等 | — | 0 | 不适用（机制 Desktop 专属，意图已实现） |

### 2.2 提示词与风格

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| P1 | 四段模板 prefix / base / thinking / suffix + 按界面语言选 zh/en | `src/backend/batch-translation/work-unit/work-unit-prompt-builder.ts:128-152,262-275`；`builtin/translation_prompt/template/{zh,en}/*.txt` | 部分具备 | LNT 单段 system prompt（`TranslationProtocol.kt:67-120`）+ `{{...}}` 占位符（`55-58`）；无 prefix/suffix 分段，无模板语言切换 | P2 | 2 | 是 |
| P2 | 用户自定义主提示词覆盖 + 启用开关 | `src/domain/prompt.ts:5-28`；`src/shared/text/text-processing.ts:71-72` | 已具备 | LNT `PromptProfileEntity` 版本化可编辑（`data/model/ReaderPlatformEntities.kt:242-261`）+ `PromptProfileEditorDialog`（`BookWorkbenchDetailScreen.kt:2155`）。LNT 更强（版本化） | — | 0 | 是 |
| P3 | 思考块 `<why></why>` + `prompt_enhancement_enable` 开关 | `builtin/translation_prompt/template/zh/thinking.txt`；`work-unit-prompt-builder.ts:136-138`；`src/domain/setting.ts:49` | 缺失 | LNT `reasoningEffort` 硬编码 `"low"`（`BookTranslationEngine.kt:1409` 等 4 处）；无用户可见开关 | P1 | 2 | 是 |
| P4 | 风格预设库（文言文 / 文学措辞） | `builtin/translation_prompt/preset/01_文言文风格.txt`、`02_文学措辞风格.txt`；`src/domain/prompt.ts:24` | 缺失 | LNT 只有 `styleGuide` 自由文本（`TranslationProjectV2Entity.styleGuide`，`ReaderPlatformEntities.kt:178`）注入 `{{STYLE_GUIDE}}` | P1 | 3 | 是 |
| P5 | JSONLINE 输出协议 + `actor_text` 姓名模式 | `src/shared/text/translation-output-format.ts:11-22`；`work-unit-prompt-builder.ts:209-220` | 已具备（等价） | LNT 用 XML `<C id><S id>` 协议（`TranslationProtocol.kt:94-99`）+ `META` 块（`102-118`）；姓名走 Lexicon 而非独立 actor 字段。设计不同但覆盖 | — | 0 | 是 |
| P6 | 前文注入（只进 user prompt，避免系统指令变化） | `work-unit-prompt-builder.ts:157-167` | 已具备 | LNT `[PREVIOUS_CHAPTER_ORIGINAL_TAIL]` / `[PREVIOUS_CHAPTER_TRANSLATION_TAIL]` / `[PREVIOUS_CHUNK_TRANSLATION_TAIL]` / `[RECENT_CONTEXT]`（`TranslationProtocol.kt:345-357`），且稳定前缀单独快照（`ContextEngine.kt:100-121`） | — | 0 | 是 |
| P7 | SakuraLLM 专用提示词（本地轻小说模型） | `work-unit-prompt-builder.ts:106-123` | 不适用 | LG 面向桌面本地模型生态；LNT Provider 体系（`PresetModels.kt`）可另接本地端点 | P2 | 2 | 部分（作为"本地模型预设"更有意义） |
| P8 | 术语注入 prompt（激活式 + 备注） | `work-unit-prompt-builder.ts:172-182`；`src/shared/quality/glossary.ts:126-128` | 已具备 | LNT `[LEXICON]` 块带 category/note（`TranslationProtocol.kt:329-337`），且 `ContextEngine.prepare` 按优先级/长度/归一化源项排序（`ContextEngine.kt:36-41`） | — | 0 | 是 |
| P9 | 控制字符样例注入（仅当主提示词要求控制符时） | `work-unit-prompt-builder.ts:187-204` | 缺失 | LNT 用 `__LNT_PROTECTED_N__` 不透明标记（`TranslationTextProtection.kt:22-27`），不需要样例；但 `core/text` 的 `samples` 产物（`TranslationSourcePreparer.kt:86-93`）已就绪 | P2 | 1 | 是 |
| P10 | 提示词增强开关（实验室页三个实验字段） | `src/frontend/pages/laboratory-page/use-laboratory-page-state.ts:15-19` | 缺失 | LNT 无"实验室/实验开关"层；`metadataEnabled` 语义接近但无 UI | P2 | 1 | 是 |
| P11 | META 元数据侧信道（chapterMemory / storyMemoryDelta / lexiconCandidates） | — （LG 无对应物，LG 的元数据由 Agent 工具负责） | 重构中 | LNT `TranslationProtocol.kt:102-118` 的 `<META>` 块 + `metadataEnabled` 开关（`ReaderPlatformEntities.kt:193`）+ `MemoryOperation` / `ChapterMemoryEntity`。**这是 LNT 超过 LG 的能力** | — | 0 | 是 |
| P12 | 二次审校（AI_POLISH）独立 Prompt | — （LG 由 Agent `translation` 技能的 page-review 负责，`builtin/skills/translation/references/page-review.md`） | 已具备 | LNT `polishSystemPrompt` / `polishUserPrompt`（`TranslationProtocol.kt:236-314`）+ `runPostDraftReview`（`BookTranslationEngine.kt:1640+`）+ `highQualityReview` 开关。LNT 更强（可无人值守） | — | 0 | 是 |
| P13 | 修复请求带具体 QA 证据 | — （LG 无对应物） | 已具备 | LNT `repairUserPrompt`（`TranslationProtocol.kt:383-406`）带 `problems` 列表 + `DUPLICATE_OUTPUT_REPAIR` / `NUMERIC_FACT_REPAIR` 专项指令 | — | 0 | 是 |

### 2.3 术语表

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| G1 | 术语槽位（启用 + 条目 + revision） | `src/domain/quality.ts:85-90,126-136` | 已具备 | LNT `LexiconEntryEntity` + `LexiconKind` + `ReviewStatus`（`ReaderPlatformEntities.kt:268-285`）；模型更丰富 | — | 0 | 是 |
| G2 | 激活式注入（只注入本批命中的术语） | `src/backend/batch-translation/work-unit/runners/translation-runner.ts:420-442` | 已具备 | LNT `ContextEngine.prepare` 按 `matches(entry, sourceText)` 过滤（`ContextEngine.kt:35`）+ `userPrompt` 内二次过滤 `LexiconTermMatcher.matchesSource`（`TranslationProtocol.kt:325-327`） | — | 0 | 是 |
| G3 | 译后验证四态 none/applied/partial/missing | `src/shared/quality/glossary.ts:113-122` | 部分具备 | `core/text/GlossaryCompiler.kt:128-137` 已移植 `resolveGlossaryApplicationState`；但主源码无调用点。引擎侧 QA 用 `GlossaryQaStatus`（`TranslationProtocol.kt:807-828`） | P0 | 1（接线） | 是 |
| G4 | 校对告警 `GLOSSARY` | `src/shared/proofreading/proofreading-evaluator.ts:257-270` | 部分具备 | LNT `DeterministicTranslationQa` 报 `GLOSSARY_MISSING`（`TranslationProtocol.kt:680-685`）+ `GLOSSARY_CROSS_SEGMENT` 诊断（`688-702`）。**但重构计划已把 `GLOSSARY_MISSING` 降为非阻断 warning**（master plan §C.5） | P1 | 2 | 是 |
| G5 | 术语筛选面板（按 entry_id 筛条目 + 计数） | `src/backend/proofreading/proofreading-query-service.ts:195-199`；`proofreading-types.ts:234-245` | 缺失 | LNT `GlossaryManagementTab`（`BookWorkbenchDetailScreen.kt:2872`）是管理视图，不是"按术语筛译文" | P1 | 3 | 是 |
| G6 | 导入预览 + skip/overwrite + 重复四分类 | `src/shared/quality/quality-rule-import.ts:93-122,258-275` | 缺失 | LNT 无术语文件导入导出（`rg` 无命中） | P1 | 4 | 是 |
| G7 | 预设（示例 / 常用菜单文本） | `builtin/glossary/preset/01_示例.json`、`02_常用菜单文本.json` | 缺失 | LNT 无预设包机制；游戏菜单文本预设对小说价值低，但"通用轻小说术语预设"有价值 | P2 | 2 | 是 |
| G8 | Agent 一键生成术语表（技能 + 脚本） | `builtin/skills/glossary/`（`SKILL.md`、`references/extract.md`、`scripts/match-literals.mjs`） | 已具备（等价） | LNT `core/agent/TermExtractionAgent.kt` + `TranslationPrompts.buildTermExtractionPrompt`（`core/llm/TranslationPrompts.kt:9-81`，带 `<UNTRUSTED_TEXT_EXCERPT>` 包裹与严格边界规则）+ `LexiconCandidateAggregator` + 投票/去噪/导入规划（`data/model/LexiconCandidateAggregate.kt`）。**LNT 的候选审核流比 LG 更工程化** | — | 0 | 是 |
| G9 | 术语替换为 Revision（可撤销） | — （LG 无对应物，LG 由校对页 replace_all 直接改 dst） | 已具备 | LNT `LexiconReplacementService.kt:16-78`（预览 → 确认写 `USER_CONFIRMED_REPLACEMENT` → undo 停用 Revision）。**LNT 明显更安全** | — | 0 | 是 |
| G10 | 术语条目去重 / 冲突合并键 | `src/shared/quality/quality-rule-import.ts:315-325` | 已具备 | LNT `LexiconCandidateVoting.normalizeSourceTerm`（`data/model/LexiconCandidateAggregate.kt`）+ DAO 唯一索引 `(translationProjectId, sourceTerm)`（`ReaderPlatformEntities.kt:266`） | — | 0 | 是 |

### 2.4 文本保真

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| F1 | 保护规则三态模式 off / smart / custom | `src/domain/quality.ts:82`；`src/shared/text/text-preserve-rules.ts:143-172` | 部分具备 | `core/text/TextProtectionRules.kt` 已移植裁决算法；`TextProtectionPresets.base` 只有基础层；无 mode、无用户规则持久化 | P0 | 3 | 是 |
| F2 | 基础保护规则（`<br>` / `\s` / 资源 token） | `builtin/text_preserve/preset/base.json` | 重构中 | `core/text/TextProtectionPresets.kt:15-26` 已等价移植，且 token 规则与 `ResourceReferenceSlots.TOKEN_PREFIX` 同源（注释明确说明必须同源） | — | 0 | 是 |
| F3 | 按 text_type 的引擎预设（KAG/RENPY/RPGMAKER/WOLF） | `src/shared/text/text-preserve-rules.ts:29-36`；`builtin/text_preserve/preset/{kag,renpy,rpgmaker,wolf}.json` | 不适用 | `core/text/TextProtectionPresets.kt:9-11` 明确不移植并说明理由（游戏脚本专用，LNT 面向小说正文）。**这个判断正确** | — | 0 | 不适用 |
| F4 | 资源引用投影为扁平 token + 译后精确还原 | `src/shared/text/text-resource-reference.ts:195-226` | 重构中 | `core/text/ResourceReferenceSlots.kt:167-213` 已移植（含长 token 优先 + `(?!\d)` 防前缀误命中） | — | 0 | 是 |
| F5 | 资源引用识别（data URI / scheme URI / 无 scheme 资源路径，90+ 扩展名 + 句末标点裁剪 + 未配对闭合符裁剪） | 同文件 `22-101,104-127,278-298` | 重构中 | `ResourceReferenceSlots.kt` 已移植；扩展名清单对 EPUB 内链与脚注有意义 | P1 | 1（核对清单） | 是 |
| F6 | ruby 注音清理（保守 + 激进两级 + 按格式排除） | `src/shared/text/text-ruby-cleaner.ts`；`TranslationSourcePreparer.kt:174-213`（LNT 侧移植） | 重构中 | `core/text/TranslationSourcePreparer.kt` 内 `RubyCleaner` 已移植；无 `clean_ruby` 设置项与 UI | P1 | 2 | 是（日语 EPUB 常见） |
| F7 | 译前替换（字面量 + 正则，`$1`/`${name}` 语义） | `src/shared/text/text-replacement-rules.ts` | 重构中 | `core/text/TranslationSourcePreparer.kt:225-304` 已移植；无配置层 | P0 | 3 | 是 |
| F8 | 译后逐行恢复（保护段删除 / 反斜杠段长度 / 圆圈数字 / 标点形态 / 边界引号 / CJK 引号转换） | `src/backend/batch-translation/work-unit/pipeline/translation-output-restoration.ts:71-374` | 重构中 | `core/text/PostTranslationRestorer.kt:106-440` 已完整移植（含 `STABLE_SOURCE_FORM_RULES` / `ADAPTABLE_ASCII_FORM_RULES` 全表） | — | 0 | 是 |
| F9 | 译后替换 | `src/backend/batch-translation/work-unit/pipeline/translation-post-pipeline.ts:86-96` | 重构中 | `core/text/PostTranslationRestorer.kt:90-93` 已留调用点；无配置层 | P0 | 2 | 是 |
| F10 | 译前替换预设 | `builtin/pre_translation_replacement/preset/01_示例.json` | 缺失 | 无预设机制；示例规则（Markdown 链接剥壳）对 EPUB 意义有限 | P2 | 1 | 部分 |
| F11 | 译后替换预设（半角引号转直角引号） | `builtin/post_translation_replacement/preset/02_首尾半角引号转直角引号.json` | 缺失 | 中文小说标点规范化的高频需求；**注意 `PostTranslationRestorer.stabilizePunctuationForms` 已在 CJK 目标时把 `“”` 转 `「」`**（`PostTranslationRestorer.kt:285-291`），部分覆盖该预设意图 | P1 | 1 | 是 |
| F12 | 控制码伪名注入 | `src/shared/text/text-fakename-injector.ts` | 缺失 | 对 EPUB/TXT 小说价值低（游戏控制码场景）；LG 内置 60+ 中文/日文伪名池 | P2 | 2 | 部分 |
| F13 | 预过滤：语言正文字符判定 | `src/shared/prefilter/language-prefilter.ts`；`src/domain/language.ts` | 部分具备 | LNT `core/text/LanguageCodes.kt:306` 行含 `classifyGrapheme` 与 `isCjkLanguageCode`；无"该 Segment 无可翻译正文则跳过"的预过滤接线 | P2 | 2 | 是 |
| F14 | 预过滤：重复源文本跳过 | `src/shared/prefilter/item-prefilter.ts`；`src/domain/setting.ts:53` | 缺失 | LNT 无重复 Segment 去重跳过；小说中重复的章节题记/过渡句会重复付费 | P1 | 2 | 是 |
| F15 | 预过滤：规则前缀/模式跳过（RPGMaker 元数据等） | 同文件 `29-45` | 不适用 | 游戏格式专属 | — | 0 | 不适用 |
| F16 | MTool 优化器 | `src/domain/setting.ts:52`；`src/backend/file/shared/` | 不适用 | MTool 导出格式专属 | — | 0 | 不适用 |
| F17 | 图片标记保护（`[IMG:name]` 往返不变 + QA 校验） | — （LG 由资源引用 + 保护规则覆盖） | 已具备 | LNT `EpubParser.kt:252-273` 生成 `[IMG:...]`，`TranslationTextProtection.kt:13` 保护，`DeterministicTranslationQa` 报 `IMAGE_MARKER_CHANGED`（`TranslationProtocol.kt:647`），`EditionExporter.kt:182-201` 导出时还原为 `<img>` | — | 0 | 是 |

### 2.5 质量与校对

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| Q1 | 6 类 warning code 词表 | `src/shared/proofreading/proofreading-types.ts:9-16` | 部分具备 | LNT `QualityIssueCode` 5 项（`core/text/TranslationQualityRules.kt:7-22`）+ `DeterministicTranslationQa` 约 15 个 code（`TranslationProtocol.kt:601-746`）。**code 更多但未产品化** | P0 | 2 | 是 |
| Q2 | 外文残留检查（字素聚合 + 孤立 Latin/短大写豁免） | `src/shared/text/translation-quality-rules.ts:16-54` | 重构中 | `core/text/TranslationQualityRules.kt:69-112` 已移植（用 `BreakIterator` 替代 `Intl.Segmenter`） | — | 0 | 是 |
| Q3 | 译文相似度检查（包含快判 + Jaccard + JA/KO→ZH 特例） | 同文件 `59-97` | 重构中 | `core/text/TranslationQualityRules.kt:120-148` 已移植 | — | 0 | 是 |
| Q4 | 标点结构不一致检查 | `src/shared/text/punctuation-structure.ts` | 重构中 | `core/text/TranslationQualityRules.kt:151-174` 已移植（分组表一致） | — | 0 | 是 |
| Q5 | 保护段逐行对照（`TEXT_PRESERVE` 警告，带 source/translation fragments） | `src/shared/proofreading/proofreading-evaluator.ts:113-133` | 缺失 | LNT QA 只报 `PROTECTED_TOKEN_CHANGED`（token 序列不同），不做"源文保护段 vs 译文保护段"的逐行证据对照 | P1 | 3 | 是 |
| Q6 | 行数不一致（`LINE_COUNT_MISMATCH`） | 同文件 `231-233` | 重构中 | `core/text/TranslationQualityRules.kt:241-248` 已含 `LINE_COUNT_MISMATCH` + 额外的 `PARAGRAPH_COUNT_MISMATCH` | — | 0 | 是 |
| Q7 | 空输出 / 异常长度 / 拒答检测 | — （LG 由日志与状态覆盖） | 已具备 | `EMPTY_SEGMENT` / `POSSIBLE_OMISSION` / `ABNORMAL_LENGTH` / `REFUSAL_OR_EXPLANATION`（`TranslationProtocol.kt:635-674`） | — | 0 | 是 |
| Q8 | 数字事实变化检测（同长度不同序列才阻断，数量不匹配只诊断） | — | 已具备 | `NUMERIC_CONTENT_CHANGED` / `NUMERIC_CONTENT_UNCERTAIN`（`TranslationProtocol.kt:648-669`）+ `ChineseNumberParser`。**设计比 LG 更谨慎** | — | 0 | 是 |
| Q9 | 重复译文检测（同译文不同源文才阻断；同源文只诊断） | — | 已具备 | `REPEATED_TRANSLATED_SEGMENTS` / `REPEATED_TRANSLATED_SOURCE`（`TranslationProtocol.kt:704-739`） | — | 0 | 是 |
| Q10 | 警告摘要（按 code 计数） | `src/shared/proofreading/proofreading-types.ts:267-287` | 缺失 | LNT 无跨章 warning 汇总视图 | P0 | 2 | 是 |
| Q11 | 筛选面板（outcomes / files / glossary_entry_ids / 是否含无术语缺失） | `src/backend/proofreading/proofreading-query-service.ts:189-203`；`proofreading-types.ts:61-77,93-98` | 缺失 | LNT `ChapterStatusMatrix`（`BookWorkbenchDetailScreen.kt:2589`）只按章状态展示，无 warning code 维度 | P0 | 4 | 是 |
| Q12 | 窗口化懒加载查询（window / row_ids_range / context） | 同文件 `84-132` | 部分具备 | LNT 有 `LazyColumn` + 按 ID 延迟加载（AGENTS.md §4）；无"上下文行"专用查询（阅读器天然提供上下文） | P2 | 0 | 部分（阅读器即上下文） |
| Q13 | 批量替换（后端按当前事实执行，字面量/正则） | `src/backend/proofreading/proofreading-service.ts:95-151` | 部分具备 | LNT `LexiconReplacementService` 只做"术语 → 译文"替换；无自由文本/正则的全书批量替换 | P1 | 3 | 是 |
| Q14 | 清空译文 + 可选重置状态 | 同文件 `153-193` | 缺失 | LNT 无"清空某范围译文"命令（只能删 Edition 或重跑） | P1 | 2 | 是 |
| Q15 | 人工三态 `NONE / PROCESSED / EXCLUDED` | `src/domain/item.ts:17` | 部分具备 | LNT `RevisionType.USER_CONFIRMED_REPLACEMENT` / `MANUAL_EDIT`（`ReaderPlatformEntities.kt:10`）+ 阅读器 QuickEditor；无"标记为已确认/排除"的显式状态 | P1 | 2 | 是 |
| Q16 | 修订号乐观锁（items + proofreading 双 section） | `src/shared/project/project-write-request.ts`；`src/shared/project-event.ts` | 缺失 | LNT 用 Room 事务 + Revision 优先级；无跨 UI/Agent 的乐观锁概念 | P2 | 3 | 是 |
| Q17 | 质量规则命中统计（hits + 2 个稳定例句） | `src/shared/quality/quality-statistics.ts:43-205` | 缺失 | 无对应物 | P2 | 3 | 是 |
| Q18 | 生成译文前 warning 预检 | `src/frontend/features/translation-generation/use-translation-generation-flow.ts:105-136` | 缺失 | LNT 无"导出前质量门" | P1 | 2 | 是 |

### 2.6 导入导出

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| X1 | EPUB 导入（目录/spine/资源） | `src/backend/file/epub/epub-ast.ts`、`epub-format.ts` | 已具备 | `core/parser/EpubParser.kt:377` 行，产出章节 + Segment + `[IMG:]` 标记 + 共享图片目录 | — | 0 | 是 |
| X2 | TXT 导入 + AI 章节识别（预览后确认） | `src/backend/file/txt/txt-format.ts` | 已具备 | `core/parser/TxtParser.kt` + `ChapterStructureDetector.kt` + `ChapterSplitAgent.kt` + `ui/screens/reader/ReaderChapterResplitDialog.kt` + `data/model/ChapterSplitPreview.kt` | — | 0 | 是 |
| X3 | 粘贴文本导入 | — | 已具备 | `AppViewModel.kt:371` `importPastedBook` + `AcquisitionType.PASTED_TEXT` | — | 0 | 是 |
| X4 | EPUB 导出 | — | 已具备 | `core/exporter/EditionExporter.kt:42-84`（mimetype STORED + container.xml + OPF3 + nav + CSS + 图片 + 按 logicalSegment 回退原文） | — | 0 | 是 |
| X5 | TXT 导出 | `src/backend/file/txt/txt-format.ts` | 已具备 | `EditionExporter.kt:24-40`（原子写 + 备份回滚） | — | 0 | 是 |
| X6 | 双语导出（原文+译文对照，可去重） | `src/backend/file/epub/epub-writer.ts:338-342,418-431`；`src/domain/setting.ts:50` | 缺失 | LNT `EditionExporter` 无双语模式；阅读器有 `BILINGUAL` 显示（`PlatformReaderScreen.kt:1292-1308`）但不可导出 | P1 | 3 | 是 |
| X7 | EPUB 写回保留原样式/ruby/脚注/链接（AST 级） | `src/backend/file/epub/epub-writer.ts` + `epub-ast.ts` | 部分具备 | LNT 导出是"重建"而非"改写的"：固定 CSS（`EditionExporter.kt:56`）、按空行分段（`159`）、图片按名字匹配（`182-201`）。**会丢失原书样式与行内结构** | P1 | 8 | 部分（见 §5，建议做"原包改写"模式） |
| X8 | 译名写入文件开关 | `src/domain/setting.ts:51` | 不适用 | LNT 的 `name_src/name_dst` 语义由 Lexicon 承担，无独立姓名槽 | — | 0 | 不适用 |
| X9 | 导出后打开输出目录 | `src/domain/setting.ts:48` | 部分具备 | Android 应改为"分享/保存到 Downloads"；`AppViewModel.kt:1567` 已产出 File，缺系统分享入口 | P1 | 2 | 是（改造） |
| X10 | 下载清单 / 输出目录列举 | `src/frontend/features/translation-generation/use-translation-generation-flow.ts:50-69` | 缺失 | LNT 无导出中心 | P2 | 2 | 是 |
| X11 | 字幕 / Markdown / Ren'Py / MTool / SExtractor / VNTextPatch / Translator++ / WOLF / RPGMaker / KVJSON / MESSAGEJSON / XLSX / PDF | `src/backend/file/{subtitle,markdown,renpy,rpgmaker-mv-mz,rpgmaker-rgss,kvjson,messagejson,xlsx,pdf,trans}/` | 不适用 | 见 §4 | — | 0 | 不适用 |
| X12 | `.lg` 单文件工程（可拷贝/备份/迁移） | `src/backend/project/project-copy-service.ts:9`；`src/backend/migration/**` | 不适用 | LNT 用 Room + `BookFileManager` 分层（`core/book/BookFileManager.kt:196` 行；`files/books/book_<id>/{cover,source,shared/images,editions/...,workspace}` + `cache/` + `files/exports/`）。**LNT 模型更适合 Android** | — | 0 | 不适用 |

### 2.7 数据与工程模型

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| D1 | 条目身份（`item_id` + `row_number`）与格式私有 `extra_field` / `tag` | `src/domain/item.ts:41-73` | 已具备（更强） | LNT `LogicalSegmentEntity` 跨 Edition 稳定 + `EditionSegmentMappingEntity`（`ReaderPlatformEntities.kt:77-137`）。**LG 的 row 绑定单一格式，LNT 的逻辑位置不绑定 Edition** | — | 0 | 是 |
| D2 | 条目 7 态状态机（含 RULE_SKIPPED / LANGUAGE_SKIPPED / DUPLICATED） | `src/domain/item.ts:6-14` | 部分具备 | LNT 章级状态 + `SegmentRevision`；无"被规则跳过/语言跳过/重复"的段级状态 | P2 | 3 | 是 |
| D3 | 资产存储（`sources/<file_path>/` 原文件保留） | `src/backend/agent/workspace/sources.ts` | 已具备 | `BookFileManager` `source/` 目录（README「数据与文件」段） | — | 0 | 是 |
| D4 | section revision（files / items / quality / proofreading） | `src/shared/project-event.ts`；`proofreading-types.ts:218-232` | 缺失 | LNT 无跨模块修订号；靠 Flow + 事务 | P2 | 2 | 是 |
| D5 | 数据库版本迁移框架 | `src/backend/migration/database/**`、`project/**`、`startup/**` | 已具备 | Room `MIGRATION_1_2/2_3/3_4`（AGENTS.md §4）+ v5（`app/schemas/.../5.json` 未跟踪，重构中） | — | 0 | 是 |
| D6 | 最近工程列表 | `src/domain/setting.ts:33-36` | 已具备（等价） | 书架 + 阅读历史（`ui/screens/bookshelf`、`ui/screens/history`） | — | 0 | 是 |
| D7 | 工程保存位置策略（MANUAL/FIXED/SOURCE） | `src/domain/setting.ts:25-26` | 不适用 | 桌面文件系统语义；Android 用 SAF/MediaStore | — | 0 | 不适用 |
| D8 | Agent 会话与消息持久化 | `src/backend/agent/agent-chat-data.ts` 等 | 重构中 | `data/model/AgentEntities.kt` + `data/db/AgentDao.kt` + `RoomAgentSessionStore.kt` | — | 0 | 是 |

### 2.8 Agent 与技能

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| A1 | 工具：ask_user（2-3 个固定选项 + 自定义答案 + 取消） | `src/backend/agent/tools/ask-user.ts:105` | 重构中 | LNT `AgentToolNames.ASK_USER`（`AgentToolHost.kt:60`）；选项约束与 UI 待验证 | P1 | 2 | 是 |
| A2 | 工具：批量条目翻译 | `src/backend/agent/tools/batch-item-translation.ts:69` | 重构中（等价） | LNT `START_TRANSLATION` + `UPDATE_TRANSLATION_CONFIG`（`AgentToolHost.kt:54,62`） | — | 0 | 是 |
| A3 | 工具：read_skill | `src/backend/agent/tools/read-skill.ts:25` | 缺失 | 无技能系统 | P1 | 3 | 是 |
| A4 | 工具：web_search | `src/backend/agent/tools/web-search.ts:26`；`web-search-service.ts` | 缺失 | LNT 无联网检索；对小说翻译（查专有名词）有价值 | P2 | 3 | 部分（需用户主动触发 + 隐私提示） |
| A5 | 工具：workspace_apply / workspace_run（文件式工作区契约） | `src/backend/agent/tools/workspace-apply.ts:26`、`workspace-run.ts:23`；`workspace/schema.ts` | 缺失 | LNT 用类型化工具（`AgentToolHost.executeTool`）；文件式契约对手机端过重 | P2 | 0 | 不适用（用类型化工具更合适） |
| A6 | 工程写入审批 manual / auto（手动模式展示变更类别与数量） | `src/domain/setting.ts:29`；`PRODUCT.md` Capabilities | 缺失 | LNT `AgentToolHost` 无审批概念；`START_TRANSLATION` 会直接发起付费请求 | P0 | 3 | 是（Android 上更重要） |
| A7 | 10 个内置技能（epub/glossary/text-preserve/translation/writing-guide/roleplay/...） | `builtin/skills/` | 缺失 | LNT 只有 `assets/agent/system-prompt.md` 单文件 | P1 | 5 | 是 |
| A8 | 技能编辑器（文件树 + 文档编辑 + 增删改） | `src/frontend/pages/skills-page/` | 缺失 | — | P2 | 5 | 部分（手机端编辑体验需重新设计） |
| A9 | 人格/个性配置 | `builtin/personality.md`；`src/backend/agent/agent-personality-service.ts` | 缺失 | — | P2 | 1 | 部分 |
| A10 | 上下文压缩（手动 + 自动） | `src/backend/agent/agent-compaction.ts` | 部分具备 | `LntPromptMapper.capTranscript`（`LntPromptExecutor.kt:195-201`）做头尾截断；非语义压缩 | P1 | 3 | 是 |
| A11 | 输入消息队列（运行中可继续排队） | `src/backend/agent/agent-input-queue.ts` | 缺失 | — | P2 | 2 | 是 |
| A12 | 图片附件 | `src/backend/agent/agent-image-service.ts`；`src/frontend/pages/agent-page/agent-message-attachments.tsx` | 缺失 | Android 侧可接相册/相机；对"这张插图对应哪段"有用 | P2 | 3 | 是 |
| A13 | 响应批注（选中助手回复加注） | `src/frontend/pages/agent-page/agent-response-annotation.tsx` | 缺失 | — | P2 | 2 | 部分 |
| A14 | 工具详情弹窗 / 思考过程可见 | `src/frontend/pages/agent-page/agent-tool-detail-dialog.tsx`、`agent-timeline.tsx` | 部分具备 | LNT `AgentChatScreen.kt:276` 有"正在思考或调用工具"状态；无工具调用详情视图 | P1 | 3 | 是 |
| A15 | 可修订消息 / 行内编辑器 | `src/frontend/pages/agent-page/agent-message-editor.tsx`、`agent-inline-editor.tsx` | 缺失 | — | P2 | 2 | 部分 |
| A16 | Agent 发起批量翻译并共用运行资源 | `PRODUCT.md` Operating Context；`src/backend/agent/tools/batch-item-translation.ts` | 重构中 | LNT `START_TRANSLATION` 工具经 `AgentToolHostImpl`（`AppViewModel.kt:214`）到引擎，天然共用 | — | 0 | 是 |

### 2.9 端侧与成本

| # | 功能点 | LG 实现位置（文件） | LNT 现状 | 差距说明 | 优先级 | 预估工作量 | 适合 Android 形态 |
|---|---|---|---|---|---|---|---|
| C1 | token 用量统计（input/reasoning/output 三分） | `src/domain/batch-translation.ts:74-78` | 部分具备 | LNT `LlmResult.promptTokens / completionTokens` + `UsageSource.ACTUAL/ESTIMATED/UNKNOWN`（`LlmGateway.kt:32-36`）；reasoning 未分离 | P2 | 2 | 是 |
| C2 | 费用计算与币种 | — （LG 无对应物） | 已具备 | `TokenCalculator.calculateCost / formatCost`（`core/llm/TokenCalculator.kt:40-63`）+ `ApiProviderEntity.currency` + `PlatformTranslationRunEntity.currency` + `formatRunCostSummary`（`PlatformTaskCenterScreen.kt:1318`）。**LNT 超过 LG** | — | 0 | 是 |
| C3 | Prompt 缓存 | — （LG 无对应物） | 重构中 | `ProviderPromptCache.kt`（Anthropic `cache_control` 断点 + OpenAI/DeepSeek/Gemini 自动缓存）+ `PromptCachePolicy.shouldCreate`（≥1024 token 且复用 ≥2 次）。**LNT 超过 LG** | — | 0 | 是 |
| C4 | token 计数缓存（同文只算一次，跨任务复用） | `src/backend/batch-translation/planning/token-count-cache.ts` | 部分具备 | LNT `TokenBudgetPlanner` 为字符级估算，无缓存；`ProviderCacheRecordEntity`（`ReaderPlatformEntities.kt:374`）是 provider 缓存记录 | P2 | 2 | 是 |
| C5 | 请求日志（Debug 开关 + 脱敏） | `src/backend/log/log-manager.ts`；`src/frontend/pages/log-window-page/` | 已具备 | `PlatformRequestLogEntity` + `DebugDiagnosticsTab`（`BookWorkbenchDetailScreen.kt:3646`）+ AGENTS.md §5 的 Key 脱敏不变量 | — | 0 | 是 |
| C6 | 端侧推理（ML Kit GenAI） | — （LG 无对应物） | 已具备（未接线） | `core/ondevice/**`（状态探测、摘要、校对、Prompt 轻任务、语言限制、配额退避）；`AppViewModel` 无引用 | P1 | 3（接线） | 是 |
| C7 | 模型管理（增删改 + 预设模板 + 阈值配置 + OAuth 登录） | `src/frontend/pages/model-page/`；`src/domain/model.ts:100-125`；`src/backend/auth/chatgpt-auth-service.ts` | 部分具备 | LNT `ApiSettingsScreen.kt:1130` 行 + `PresetModels.kt`（Moonshot/Kimi/OpenRouter/Ollama 等模板）+ 端点/Key/超时/重试/价格可编辑。**缺阈值配置（并发/RPM/输入 token 上限）与 OAuth** | P1 | 3 | 是（阈值必加，OAuth 不必） |

---

## 3. 分类深挖

### 3.1 翻译执行（并发 / 限流 / 快照 / 重试 / 断点）

1. **LG 的请求调度是一个独立的、可单测的状态机**。`translation-request-scheduler.ts` 把"逻辑请求"与"物理请求"分离：一个 `PendingRequest` 对应一个 Promise，换密钥重试仍留在同一队列（`86-109`）；密钥有 `ready / draining / cooling / probing` 四态，`fail_key` 统一把并发故障收敛为首次失败再冷却（`310-313`），`cool_key` 用 `KEY_COOLDOWN_STEPS_MS[min(failures,3)-1]` 取退避档位（`315-320`）。LNT 目前没有这一层——`RetryPolicy.decide`（`LlmGateway.kt:158-179`）只回答"这次失败要不要重试、等多久"。

2. **LG 的自动并发探测值得单独看**：`auto_concurrency` 在 `concurrency_limit === 0 && rpm_limit === 0` 时启用（`request-rate.ts:116`），初始 4，每次成功 +1（上限 16），每次有效 429 减半并把 `concurrency_version` +1（`translation-request-scheduler.ts:295-307`），版本号让"降档前已发出的成功"不参与升档判断。这是"无需用户配置就能跑满供应商额度"的关键设计，对应 PRODUCT.md 的"让用户尽量少配置"。

3. **LG 的切块同时受 token 与行数两个上界约束**，且"同 `file_path` 不拆章"（`translation-planner.ts:95-100`）。LNT 以章为边界天然满足后者，但前者只有字符级估算（`TokenBudgetPlanner.estimate`）。对 EPUB 来说，一章可能 3 万字符也可能 300 字符，字符估算在 CJK 场景偏差可达 ±30%。

4. **LG 的断点/续跑有三个层次**：工程级持久化进度（`update_translation_progress`，`batch-translation-runner.ts:397-405`）、运行级 `run_progress`（只属于内存，`domain/batch-translation.ts:164`）、以及提交后的"迟到 run 不允许写入"（`commit_translation_entries` 的 `is_current` 检查，`batch-translation-runner.ts:315`）。LNT 有 `ChunkCheckpoint` + `.translation_checkpoint_` 前缀文件（`BookTranslationEngine.kt:99-106`、`161`）与 `activeRuns` / `claimedProjects`（`154-158`），等价物齐全。

5. **LNT 在"暂停不得再发起付费请求"上比 LG 更严格**：`TranslationControlSignal.withRequestPermit`（`LlmGateway.kt:129-139`）在注册在飞计数的同一把 Mutex 内检查 paused/cancelled，`requestPause` 会 `awaitDrain()`（`114-117`）。LG 的对应物是 `handle.signal`（AbortSignal）+ stop source，没有"pause 等待在飞请求排空"的硬边界。**这是 LNT 应保持的优势，不要为了对标而回退。**

6. **LG 的 `interrupted` 终态**（`domain/batch-translation.ts:40`）是"崩溃后按持久化线索恢复的展示终态"。LNT 的 `INTERRUPTED` 在 `resume` 允许状态集合里（`BookTranslationEngine.kt:195`），但工作区 UI 无对应展示（`effectiveTranslationState`，`BookWorkbenchDetailScreen.kt:3799`）——待验证是否覆盖。

### 3.2 提示词与风格（模板 / 风格预设 / 自定义 / 思考块）

1. **LG 的模板是"资产文件 + 运行时拼装"**：`PromptBuilder.build_main` 按 `prefix → base → [thinking] → suffix` 拼接，再替换三个占位符（`work-unit-prompt-builder.ts:128-152`）。模板文件按 `builtin/translation_prompt/template/{zh,en}/` 组织，带静态缓存（`262-275`）。好处是升级应用时模板可随版本替换，用户自定义只覆盖 `base` 段。

2. **LNT 的提示词是"代码内字符串 + 版本化 Profile"**：`TranslationProtocol.systemPrompt` 是 Kotlin raw string（`TranslationProtocol.kt:67-120`），`PromptProfileEntity` 存用户可编辑副本并按 version 递增（`ReaderPlatformEntities.kt:242-261`）。好处是协议不变量可以用 `nonNegotiableProtocolRules()` 强制追加（`153-159`），不会被用户编辑删掉；代价是没有"官方模板升级"的通道，也没有风格预设库。

3. **思考块是 LG 的质量开关之一**：`thinking.txt` 要求模型在 `<why></why>` 里输出"语境/决策"两行极简结构化思考（`builtin/translation_prompt/template/zh/thinking.txt`），由 `prompt_enhancement_enable` 控制（`setting.ts:49`）。LNT 的 `reasoningEffort` 已经在协议层支持（OpenAI `reasoning.effort`、Claude `thinking`、Gemini `thinkingConfig`，见 `LlmClient.kt:372-381`、`458-470`、`576-590`，且 400 回退会去掉 thinking 字段，`506-507`、`594-596`），但**被硬编码为 `"low"` 且无 UI**。这是一个低成本高收益的补齐点。

4. **风格预设应该做成 Profile 的一部分**：LNT 已有 `styleGuide` 字段与 `{{STYLE_GUIDE}}` 占位符（`TranslationProtocol.kt:58`、`138`），缺的只是"几个一键可选的内置风格文本"。LG 的 `01_文言文风格.txt` / `02_文学措辞风格.txt` 可以直接作为初始预设内容参考（注意版权与语气需重写为 LNT 风格）。

5. **LG 的"控制字符样例"机制说明了一个通用原则**：只有当主提示词显式要求保护控制符时，才把收集到的保护段样例注入 user prompt（`work-unit-prompt-builder.ts:187-204`）。LNT 的不透明标记方案不需要它，但 `core/text/TranslationSourcePreparer.kt:86-93` 已经产出 `samples`，未来若引入 `core/text` 体系可直接复用。

### 3.3 术语表（生成 / 注入 / 译后验证 / 导入导出 / 预设）

1. **LG 的术语是"四条质量规则之一"**，与替换规则共享同一套槽位机制（`QualityRule` 类统一计算 database_type / preset_directory / enabled_meta_key / revision_meta_key，`domain/quality.ts:178-273`）。这让术语表天然获得：启用开关、修订号（用于缓存失效与事件广播）、预设目录、默认预设设置键。

2. **LNT 的术语模型更丰富但更"重"**：`LexiconEntryEntity` 有 14 个字段（`ReaderPlatformEntities.kt:268-285`），候选流有聚合实体、投票、噪声过滤、导入规划（`data/model/LexiconCandidateAggregate.kt` 340 行）。这套设计对"AI 抽术语 → 人工确认"非常合适，LNT 实际上已经超过 LG。

3. **LG 的译后验证是"逐字段"的**：`match_glossary_source` 只在源文字段命中并记录 `source_field → target_field` 映射（`glossary.ts:60-80`），`evaluate_glossary_applications` 逐字段确认译文字段是否包含术语译文（`83-111`）。`core/text/GlossaryCompiler.kt` 已移植但简化为单文本（注释明说：下一阶段接入引擎时如需分字段，可按字段各建一个 `CompiledGlossary`，`GlossaryCompiler.kt:52-57`）。

4. **导入预览是 LG 做得最细的产品层之一**：`preview_quality_rule_import` 同时算出 skip 与 overwrite 两份结果快照（`quality-rule-import.ts:102-111`），重复分四类（`258-275`），覆盖字段按规则类型白名单（`295-301`）。LNT 的对应物应该是"JSON 文件导入术语表 + 冲突清单 + 跳过/覆盖选择"，工作量主体在 UI 与文件读写（Android SAF），算法可复用 `LexiconCandidateVoting.normalizeSourceTerm` 与 DAO 唯一索引。

5. **术语的"命中统计"对长篇小说特别有用**：LG 的 `quality-statistics.ts` 能回答"这条术语在多少段落出现过、举个例"。LNT 目前只能看到术语列表，无法判断某条是否冗余或某条是否从未命中。建议在接线 `core/text` 时一并实现（`GlossaryCompiler.matchSource` 已提供逐文本命中能力）。

### 3.4 文本保真（保护规则 / 资源引用 / ruby / 译前译后替换 / 格式恢复）

1. **LG 的固定顺序是这套体系的灵魂**：资源投影 → ruby 清理 → 保护段分析 → 译前替换（`translation-pre-pipeline.ts:62-63` 注释明确写出这个顺序）。资源必须先隔离，否则 ruby 规则和替换规则会把 URL / data URI 改坏。`core/text/TranslationSourcePreparer.kt:50-55` 的注释完整保留了这个顺序说明，`:74-101` 的实现也一致。

2. **LG 的译后恢复是"单调收敛"的**：每一步只在证据能完整对应时才改写（`translation-output-restoration.ts:68-93`）。`core/text/PostTranslationRestorer.kt:100-105` 的注释甚至明确把它表述为幂等。这套逻辑对小说的价值：模型经常把 `“”` 改成 `「」``、把全角冒号改成半角、把圆圈数字 ① 改成 1——LG 用"总量唯一可解释"才授权全局恢复（`canRestoreByTotalCount`，`translation-output-restoration.ts:254-277`），避免误改。

3. **保护规则的 `transformUnpreserved` 是被低估的原语**：它让"只改非保护文本"成为一行调用（`text-preserve-rules.ts:77-86`）。LNT `core/text/TextProtectionRules.kt:81-92` 已移植。接线后，译后替换、标点恢复、质量检查都能复用它。

4. **两套保护体系并存是当前最大的技术债**：`core/translation/TranslationTextProtection.kt`（硬编码 5 类模式 + `__LNT_PROTECTED_N__`）正在被引擎使用（`BookTranslationEngine.kt:1323`、`3046`、`TranslationProtocol.kt:309`、`641-642`），而 `core/text/TextProtectionRules.kt`（规则驱动 + `TextPreserveAnalysis`）没有调用点。**建议的接线方向见 §5.1**：不是二选一，而是让 `TranslationTextProtection` 的识别结果成为 `TextProtectionRule` 的一条内置规则。

5. **译前/译后替换是 LG 里最"平凡但高频"的能力**。小说场景的典型用法：把作者的特殊分隔符（`◆`、`※`、`—— 作者注 ——`）统一；把半角引号规范化；把连续的省略号规范化。LG 用正则 + `$1` 捕获组（`builtin/pre_translation_replacement/preset/01_示例.json` 的 Markdown 链接剥壳就是 `\[(.+?)\]\(.+\)` → `\1`）。`core/text/TranslationSourcePreparer.kt:312-343` 的 `expandReplacement` 已完整复刻 JS 的 `$1` / `${name}` 语义（含反斜杠转义与缺失分组归空）。

### 3.5 质量与校对（检查规则 / 告警码 / 批量替换 / 统计）

1. **LG 的 warning code 是稳定词表，UI/筛选/统计/Agent 共用**（`proofreading-types.ts:9-16`，`read_proofreading_warning_codes` 按固定顺序去重返回，`37-42`）。这种"一个词表驱动一切"的设计让新增检查项不需要改 UI。LNT 的 QA code 散落在字符串拼接里（`TranslationProtocol.kt:609-616` 的 `issue(code, detail)` helper），没有导出词表。

2. **LG 的 `TEXT_PRESERVE` 警告有双向证据**：同时给出 `source_fragments` 与 `translation_fragments`（`proofreading-evaluator.ts:113-133`），按行号对齐。这让用户能直接看到"原文这里有 `{{heroine}}`，译文里变成了 `{{ヒロイン}}`"。LNT 的 `PROTECTED_TOKEN_CHANGED` 只报"序列不同"，定位成本高。

3. **LG 的 `SIMILARITY` 有一个重要的语言政策**：JA/KO → ZH 时，单纯的"译文≈原文"不足以证明漏译（日文汉字与中文大量同形），必须同时有外文残留证据（`translation-quality-rules.ts:76-97`）。`core/text/TranslationQualityRules.kt:135-148` 已完整保留这个政策。**这条对中日互译的小说至关重要，接线时不要丢。**

4. **LG 的批量替换在后端执行**（`proofreading-service.ts:95-151`），前端只提交搜索/替换意图与 item_id 集合（`proofreading-command-planner.ts:153-193`）。理由写在注释里：避免渲染进程提交替换后的最终事实。LNT 的 `LexiconReplacementService` 也是"预览 → 确认"模式，但只覆盖术语；自由文本批量替换应遵循同样的"后端按当前事实执行"原则。

5. **LG 的统计用"稳定二槽例句"避免为每个命中分配完整候选集**（`quality-statistics.ts:169-205`），并用 FNV-1a 把 entry_id 折叠成抽样种子（`191-198`），使同一快照结果确定、又不偏向列表首尾。这是一个小而精致的设计，移植成本低。

### 3.6 导入导出（EPUB / TXT / 双语导出 / 写回原格式）

1. **LG 的 EPUB 写回是 AST 级的**：`epub-writer.ts` 先解析原包成 AST（`epub-ast.ts`），再把 item 的译文应用到文本 run 上（`apply_text_run`，`418-431`），双语插入受 `deduplication_in_bilingual` 控制（`338-342`、`465-470`）。这意味着原书的 CSS、字体、注音、脚注、链接关系全部保留。

2. **LNT 的 EPUB 导出是重建级的**：`EditionExporter.exportEpub` 生成全新的 `mimetype` / `container.xml` / `content.opf` / `nav.xhtml` / `style.css`（`EditionExporter.kt:48-83`），章节按 logicalSegment 顺序重组，段落按空行切分（`159`），图片按 `[IMG:name]` 与 `sharedImagesDir` 里的文件名匹配还原（`182-201`）。**这会丢失原书样式、行内结构（如 `<em>`、`<blockquote>`）、以及非空行分段的语义。**

3. **对 LNT 而言"写回原格式"应该重新定义为"原包改写"**：因为 LNT 导入时已经保留了原文件（`BookFileManager.source/`），完全可以在导出时打开原 EPUB 包，按 logicalSegment → EditionSegment 的映射把译文写回原 XHTML 节点。这是一个 P1 且工作量不小（8 人日量级）但收益明确的任务——对"翻译完要拿去读/分享"的用户是刚需。

4. **双语导出是 LNT 明确缺失的小功能**：阅读器已有 `BILINGUAL` 模式（`PlatformReaderScreen.kt:1292-1308`），导出侧补一个"每段输出原文+译文"的选项即可，TXT 侧 1 人日、EPUB 侧 2 人日。

### 3.7 数据与工程模型（.lg 工程 vs Book/Edition、资产存储、迁移）

1. **两种模型的根本差异**：LG 是"一个工程 = 一个文件集合 + 一个 item 表 + 一组规则"，工程可整体拷贝（`.lg`，`project-copy-service.ts:9`）。LNT 是"Book → Edition → LogicalChapter/LogicalSegment → SegmentRevision"，翻译是"从源 Edition 生产目标 Edition"的过程。

2. **LNT 的模型在四个场景明显更优**：
   - 翻译未完成时即可阅读（原文 fallback，`EditionExporter.kt:133-135`、`PlatformReaderScreen.kt:1285-1289`）；
   - 人工修改不覆盖 AI 译文（`SegmentRevisionEntity` + priority，`ReaderPlatformEntities.kt:139-167`）；
   - 阅读进度不依赖 Edition（`ReaderProgressEntity` 基于 logical 位置）；
   - 多个 Edition 可并存（原文/AI 译文/人工/未来 WEB_CAPTURE，`EditionType`）。

3. **LG 有而 LNT 没有的工程层能力**：section revision（跨模块乐观锁）、条目 7 态状态机（段级）、资产与工程的身份分离。其中 section revision 的价值在于"UI 与 Agent 同时写"时的冲突检测——LNT 的 Agent 层正在建设（`AgentToolHost`），**如果不引入某种修订号/乐观锁，Agent 改配置与用户在工作台改配置会静默互相覆盖**。这是 P2 但建议在 Agent 接线前决定。

4. **迁移**：LG 有独立的 migration 子系统（`src/backend/migration/{database,project,startup}/`）处理跨版本 `.lg`。LNT 用 Room 手工注册迁移（AGENTS.md §4），当前 v4→v5 由重构任务负责（`app/schemas/.../5.json` 未跟踪）。LG 的"启动时迁移提示"产品行为（PRODUCT.md：工作台保留经典分析入口，启动时提供迁移提示）对 LNT 不适用。

### 3.8 Agent 与技能（工具 / 技能 / 工作区 / 审批）

1. **LG 的工具面是"6 个粗粒度工具 + 文件式工作区"**：Agent 通过 `workspace_run` 在受控工作区里跑脚本，通过 `workspace_apply` 提交 datasets/changes 变更（`tools/contract.ts` 定义了完整的 Schema 化契约，含 quality 四类规则的 creates/updates/deletes 语义，`contract.ts:76-120`）。

2. **LNT 的工具面是"9 个类型化工具 + 文本协议"**：`AgentToolHost`（`AgentToolHost.kt:74`）返回 `toolSpecs()` 与 `executeTool(name, args)`；`AgentToolProtocol` 把工具目录渲染进 system prompt，模型用文本协议发起调用（`AgentToolHost.kt:9-13` 注释说明为什么这么做：`LlmGateway` 是单轮请求-响应，没有原生 function calling）。这个取舍在 Android + 云 provider 场景下是合理的。

3. **审批是 LNT 最紧迫的 Agent 缺口**：LG 有 `manual / auto` 两态，手动模式在提交前展示"实际变更类别与数量"，用户可拒绝/允许本次/允许本次及后续（PRODUCT.md Capabilities；`domain/setting.ts:29`）。LNT 的 `START_TRANSLATION` 工具一旦被模型调用就会发起付费请求，且 `UPDATE_TRANSLATION_CONFIG` 会改工程配置。**在 Android 上（误触、流量、电量）这比桌面更危险。**

4. **技能系统值得移植但需要重新设计载体**：LG 的 `builtin/skills/<name>/SKILL.md` + `references/` + `scripts/` + `ui.json`（元数据）是一个"可插拔指令包"格式，`read_skill` 工具让 Agent 按需加载（`tools/read-skill.ts:25`）。对 LNT 最有价值的三个技能是 `glossary`（术语提取规则）、`text-preserve`（保护规则整理）、`translation`（翻译/审校流程）。移植时应去掉 `scripts/`（Node 专属），保留 `SKILL.md` + `references/`，并存为 assets。

5. **LG 的会话增强（队列/批注/附件/压缩）在手机端的优先级应重排**：消息队列与上下文压缩价值高（长任务期间用户想补充要求）；图片附件中等（查插图语境）；响应批注与行内编辑较低（屏幕尺寸限制）。

### 3.9 端侧与成本（token 统计 / 计费 / 缓存）

1. **LNT 在计费上已经超过 LG**：LG 只统计 token 数量（`domain/batch-translation.ts:74-78`），没有价格模型；LNT 有 `TokenCalculator.calculateCost` + provider 级价格配置 + Run/Batch/RequestLog 三级审计 + `UsageSource` 三分（ACTUAL/ESTIMATED/UNKNOWN）。

2. **LNT 在 Prompt 缓存上也已经超过 LG**：LG 无对应物；LNT 的 `ProviderPromptCache.kt` 区分 Anthropic 显式断点与 OpenAI/DeepSeek/Gemini 自动缓存，并用 `PromptCachePolicy.shouldCreate`（稳定前缀 ≥1024 token 且预期复用 ≥2 次）决定是否值得建缓存。`ContextEngine.buildSnapshot` 还把 stablePrefix 与 fingerprint 持久化（`ContextEngine.kt:70-82`、`100-121`），并明确注释"术语是请求级动态上下文，绝不能进稳定前缀"（`113-115`）——这是正确的缓存设计。

3. **端侧 ML Kit 是 LNT 的独占能力**，且文档化的限制很清醒：仅前台、AICore 配额退避、设备白名单、语言限制（摘要仅 EN/JA/KO，校对不支持中文，中文一律回退云端，`OnDeviceAssistGateway.kt:29-34`）。**注意：中文回退云端意味着对"日译中"这个 LNT 主战场，端侧摘要不可用、端侧校对也不可用。** 端侧真正可用的是 `extractTerminologyAssist`（术语抽取辅助，走 Prompt 客户端，无语言限制）与 `translateTitleAssist`（标题翻译）。

4. **LG 的"请求恢复事实"是一个好的 UI 模式**：`request_recovery` 携带 `retry_count` 与 `retry_at`（`domain/batch-translation.ts:149-152`），让页面能显示"正在等待第 N 次恢复，预计 X 秒后"。LNT 的等价物应该从 `logChapterRequeue` 的系统日志提升为工作台的可见状态。

---

## 4. 明确「不建议移植」清单

| LG 能力 | 位置 | 不移植理由 |
|---|---|---|
| **游戏引擎格式族**：Ren'Py（`.rpy`）、MTool（`.json`）、SExtractor、VNTextPatch、Translator++（`.trans`/`.xlsx`）、WOLF（`.xlsx`）、RPGMaker MV/MZ 与 RGSS、KVJSON、MESSAGEJSON | `src/backend/file/{renpy,rpgmaker-mv-mz,rpgmaker-rgss,kvjson,messagejson,trans}/`；`src/domain/item.ts:30-36` | LNT 定位是小说/EPUB 阅读器，不面向游戏汉化。相关保护预设（KAG/RENPY/RPGMAKER/WOLF）已在 `core/text/TextProtectionPresets.kt:9-11` 明确不移植，判断正确 |
| **字幕格式**：SRT / ASS / SSA / VTT / LRC | `src/backend/file/subtitle/`；`src/domain/item.ts:23-26` | 与"长篇小说阅读"产品形态冲突；字幕翻译是时间轴对齐问题，LNT 无时间轴模型 |
| **PDF** 导入/预览/分页处置 | `src/backend/file/pdf/`；`src/shared/pdf.ts`；`builtin/skills/pdf/`；`src/frontend/features/media-preview/` | PDF 在 Android 上排版重建成本极高，且与 EPUB/TXT 的章节模型不兼容；`ProofreadingPageSummary` 的 page 维度（`proofreading-types.ts:154-157`）也无对应物 |
| **Markdown V2**（`MD_V2` item 类型 + `md-v2-format.ts`） | `src/backend/file/markdown/` | 小说内容几乎不是 Markdown；LNT 的 TXT/EPUB 解析已覆盖。若未来支持"MD 小说"再议 |
| **CLI 入口** | `src/cli/`（`cli-entry.ts`、`cli-parser.ts`、`cli-runner.ts`、`cli-status-reporter.ts`、`job/`） | 桌面自动化场景。Android 的对应物应是 WorkManager 后台任务 + 通知，而非命令行 |
| **`.lg` 单文件工程模型** | `src/backend/project/project-copy-service.ts:9`；`src/backend/migration/**` | LNT 的 Book/Edition + Room + BookFileManager 分层更适合 Android（见 §3.7）。引入单文件工程会破坏现有架构 |
| **Node `worker_threads` 执行模型** | `src/backend/batch-translation/planning/planning-worker-pool.ts`、`work-unit/translation-worker-pool.ts`、`src/backend/worker/` | Kotlin 协程 + `Dispatchers.IO` 已对等实现（`BookTranslationEngine.kt:594-640`）。移植机制无意义 |
| **Web 文件选择器 / 受控下载** | `src/frontend/features/web-file-picker/`（`web-file-picker-api.ts`、`web-file-download.ts`、`agent-save-path-bridge.ts`） | Web 端沙箱产物；Android 用 SAF + MediaStore + `FileProvider` 分享 |
| **Electron 宿主桥 / preload / shell** | `src/gui/{bridge,preload,runtime,shell}/` | 平台专属 |
| **ChatGPT OAuth 登录态** | `src/backend/auth/chatgpt-auth-service.ts`；`src/domain/setting.ts` 的 `auth_type: "oauth"` 分支（`translation-request-scheduler.ts:69`） | LNT Provider 体系已覆盖主流 API key 场景；OAuth 在 Android 上需要 Custom Tab + 回调，成本高、收益低 |
| **工程保存位置策略 MANUAL/FIXED/SOURCE** | `src/domain/setting.ts:25-26`、`64-67` | 桌面文件系统语义；Android 无"固定工程目录"概念 |
| **媒体预览视口（图片几何/缩放）** | `src/frontend/features/media-preview/media-geometry.ts`、`media-viewport.tsx` | LNT 阅读器已有自己的图片渲染路径（`ReaderRichContent` + `imageDirectory`） |
| **roleplay / rpgmaker / pdf / xlsx / skill-creator 内置技能** | `builtin/skills/{roleplay,rpgmaker,pdf,xlsx,skill-creator}/` | 前三个对应不移植的格式族；`skill-creator` 依赖 Node 脚本运行时 |
| **人格/个性编辑器（personality.md）** | `builtin/personality.md`；`src/backend/agent/agent-personality-service.ts` | 与 LNT 当前"工具型助手"定位不符；且 `assets/agent/system-prompt.md` 已承担该职责。低价值 |
| **控制码伪名注入** | `src/shared/text/text-fakename-injector.ts` | 主要服务游戏控制码（`\nN[1]`）伪装；EPUB/TXT 小说几乎不出现。若未来支持 Ren'Py 再议 |
| **MTool 优化器** | `src/domain/setting.ts:52` | MTool 格式专属 |

---

## 5. 对 LNT 产品形态的建议：哪些 LG 功能应改造成 Android 原生体验而非照搬

### 5.1 文本保真：把「两套保护体系」合并为「一套规则 + 两类来源」

当前 `core/translation/TranslationTextProtection.kt`（硬编码模式）与 `core/text/TextProtectionRules.kt`（规则驱动）并存。建议：

- **保留 `TranslationTextProtection` 的识别正则，把它降级为 `TextProtectionPresets` 的一条内置规则**（`[IMG:...]`、`{{...}}`、`${...}`、HTML 标签、HTML 实体）。这样引擎现有的 QA（`PROTECTED_TOKEN_CHANGED` / `IMAGE_MARKER_CHANGED`，`TranslationProtocol.kt:641-647`）逻辑不变，但保护范围变成用户可扩展。
- **模式三态改为 Android 友好的两级**：`smart`（默认，内置规则 + EPUB/TXT 自适应）与 `custom`（用户追加规则）。`off` 不建议暴露——关闭保护会让图片标记直接坏掉。
- **译前/译后替换不要做成"四槽位规则表"**，而是做成**工程级的"文本规则"单一列表 + `stage: PRE/POST` 字段**，配 3-5 个内置预设（半角引号转直角、省略号规范化、作者注标记统一、HTML 实体还原）。LG 的四槽位是为桌面多格式工程设计的；LNT 单一小说域用一张表更简单。
- **资源引用槽位保持"任务内扁平编号"语义**（`ResourceReferenceSlots.project`），但 token 前缀从 `lg-uri/` 改为 LNT 自己的（`ResourceReferenceSlots.TOKEN_PREFIX`），并确保 `TextProtectionPresets.resourceTokenPattern` 与投影端同源（该不变量已在 `TextProtectionPresets.kt:20-22` 注释中声明）。

### 5.2 校对：把「校对页」改造成「问题流 + 阅读器内修复」

LG 的校对页是桌面表格（虚拟滚动 + 筛选面板 + 批量替换）。在手机上照搬表格是不可用的。建议：

- **以"问题流"为主视图**：按 `QualityIssueCode` 分组的问题列表（"外文残留 23 处 / 标点结构 5 处 / 术语未应用 8 处"），点击跳到阅读器的对应 Segment 并高亮。
- **修复动作在阅读器内完成**：复用现有 `QUICK_EDIT` + `QuickEditor`（`PlatformReaderScreen.kt:1309-1327`），在译文下方展示"本段的 QA 证据"（哪条规则、期望什么、实际什么）。这比 LG 的"表格里编辑"更适合手机。
- **批量操作收敛为两个**：① 全书/范围批量替换（后端执行，复用 `LexiconReplacementService` 的模式）；② 按 code 批量"标记为已人工确认"（写入 `MANUAL_EDIT` Revision 并记录 note）。LG 的 `clear_translations` 在 LNT 应改为"删除该范围的 AI Revision 并回退原文"——这与 Edition 模型天然契合。
- **警告摘要放在工作台首页**：`WorkbenchHeroSummary`（`BookWorkbenchDetailScreen.kt:1091`）旁边加一个"质量摘要卡"，对应 LG 的 `warning_summary`。

### 5.3 限流与并发：把「自动探测」改造成「省电与省钱的默认策略」

LG 的自动并发探测（4 起步、成功升、429 降）目标是"跑满桌面额度"。Android 的目标应该相反：

- **默认 `parallelChapters = 1`（已是默认值，`ReaderPlatformEntities.kt:186`）**，并把"并发章数"做成工作台里的显式开关（1/2/3，对应"省电/均衡/快速"三档），不要暴露 4/5——手机上 4 章并发意味着 4 份上下文 prompt 同时在飞，内存与流量都紧张。
- **新增"请求间隔"而非"RPM"**：LG 的 `TranslationRequestRate` 是精确令牌桶；LNT 更适合一个简单的"两次请求最小间隔（秒）"设置（默认 0，用户可设 1-3 秒避开 429）。
- **429 退避已有（`RetryPolicy` + `RECOVERABLE_BACKOFF_MS`），不需要额外的自动降档**。但应在工作台可见："当前因限流等待，下次请求约 X 秒后"（对应 LG 的 `request_recovery`）。
- **多密钥支持建议以"密钥组"形式提供**（一个 provider 配多个 key，轮换使用），这对免费额度拼接的用户有实际价值；但不要做 LG 那么复杂的状态机——简单的 round-robin + 失败标记即可。

### 5.4 提示词与风格：把「模板四段」改造成「风格卡片 + 强度滑杆」

- **风格预设做成"风格卡片"**：3-5 个内置（如"直译准确"/"文学润色"/"口语自然"/"文言雅训"），每张卡片是一段 `styleGuide` 文本 + 可选的 system prompt 增量。用户点选即写入 `TranslationProjectV2Entity.styleGuide`（`ReaderPlatformEntities.kt:178`），无需理解模板占位符。
- **推理强度做成三档开关**（关闭/低/高），映射到 `LlmRequest.reasoningEffort`（`LlmGateway.kt:49`）。当前硬编码 `"low"`（`BookTranslationEngine.kt:1409` 等 4 处）应改为读工程配置。LG 的 `prompt_enhancement_enable` 就是这个开关的桌面版。
- **保留 `PromptProfileEditorDialog` 作为高级入口**（`BookWorkbenchDetailScreen.kt:2155`），但把"不可协商协议规则"的说明写进 UI（`nonNegotiableProtocolRules`，`TranslationProtocol.kt:153-159`），让用户理解为什么删不掉那一段。

### 5.5 术语表：把「导入预览」改造成「文件导入 + 冲突卡片」

- 术语导入用 Android SAF 选 JSON 文件，导入后以"冲突卡片"形式展示重复项（同源术语不同译文），每张卡给"保留原有/覆盖/跳过"三个动作——这是 LG `quality-rule-import.ts` 的 skip/overwrite 预览在手机上的自然形态。
- 术语导出走 `EditionExporter` 同目录（`files/exports/book_<id>/`）+ 系统分享，不要做 LG 的"输出目录列举"。
- **"术语命中统计"建议集成到术语管理页**：每条术语显示"命中 N 章 / M 段"，点击跳到第一处。LG 用 worker 做全量扫描；LNT 可以在 `GlossaryCompiler.matchSource` 基础上按章懒算。

### 5.6 Agent：把「审批 + 技能」作为 Android 的一等公民

- **审批不是可选项**：`START_TRANSLATION`、`UPDATE_TRANSLATION_CONFIG`、`RESPLIT_CHAPTERS` 三个有副作用/付费的工具必须走"计划 → 用户批准 → 执行"。建议在 `AgentToolHost.executeTool` 之上加一层 `AgentApprovalPolicy`（对应 LG 的 `agent_approval_mode`），默认 `manual`。工具回执里带 `requiresApproval` 与 `planSummary`，UI 用底部动作条展示"允许 / 拒绝"。
- **技能系统用 assets + 文本协议**：`assets/agent/skills/<name>/SKILL.md`，`read_skill` 工具按需读取。优先移植三个：`glossary`（术语提取边界规则，可直接从 `TranslationPrompts.buildTermExtractionPrompt` 提炼）、`text-preserve`（保护规则整理）、`translation`（翻译/审校流程）。不要移植 `scripts/` 目录。
- **消息队列与上下文压缩优先做**：长任务（一本书数小时）中用户几乎必然要补充要求。`LntPromptMapper.capTranscript`（`LntPromptExecutor.kt:195-201`）的头尾截断应升级为"按轮次折叠早期轮次为摘要"。
- **图片附件价值中等但实现轻**：Android 侧接相册/相机，把图片路径传给模型（多模态 provider）或转为"这张图的上下文描述由端侧 Prompt 客户端生成"（`OnDeviceAssistGateway.translateTitleAssist` 同款路径）。
- **Agent UI 必须先接入导航**：`ui/screens/agent/AgentChatScreen.kt` 目前无路由引用。建议作为工作台内的一个 Tab（而非顶级页），避免破坏 AGENTS.md §4 固定的顶级导航四页。

### 5.7 端侧：重新定位 ML Kit 的价值

`core/ondevice/**` 已完整实现但未接线。基于其自身的语言限制（`OnDeviceAssistGateway.kt:33-34`：摘要仅 EN/JA/KO、校对不支持中文），对"日译中"主战场的实际价值：

- **高价值**：`extractTerminologyAssist`（术语抽取辅助，Prompt 客户端无语言限制）→ 接入 `TermExtractionAgent` 作为本地预筛，减少云端请求量；`translateTitleAssist`（章节标题翻译）→ 接入导入后的标题本地化。
- **低价值（对中日）**：`summarizeChapterForMemory`（中文回退云端，等于没省）、`proofreadTranslation`（中文不可用）。
- **建议**：接线时只接前两个，并在设置页明确说明"端侧辅助仅覆盖术语与标题；摘要与校对在中文场景回退云端"，避免用户误解。探测状态（`probeStatus`，`OnDeviceAssistGateway.kt:61`）可用于设置页的"设备支持情况"展示。

---

## 附录 A：待验证清单

以下判断无法从静态阅读完全确认，实现前需实机或构建验证：

1. **Koog 1.3.0 API 兼容性**：`core/agent/koog/LntPromptExecutor.kt` 继承 `ai.koog.prompt.executor.model.PromptExecutor` 并 override `execute / executeStreaming / moderate / models / close`。Koog 1.3.0 的这些签名是否完全一致，需编译验证（master plan 已预警此点）。
2. **ML Kit GenAI beta 包名与 `overrideLibrary`**：`OnDeviceProofreader.kt:4-7`、`OnDeviceGenAiStatus.kt:7-8` 引用 `com.google.mlkit.genai.{proofreading,prompt,summarization}`。manifest 的 minSdk override 是否被主 agent 正确预置，需构建验证（master plan 已预警）。
3. **`parallelChapters` / `continuityMode` / `metadataEnabled` / `promptCacheEnabled` 的 UI 缺失是否为暂态**：已确认 `ui/**` 无引用，但 C 任务可能仍在进行。实现前重新 `rg`。
4. **`core/text` 的接线计划**：已确认仅 `PrecedingContextBuilder` 接线。A 任务的报告应说明剩余 10 个文件的接线时序；本文按"未接线"标注。
5. **`AgentChatScreen` 的导航接入方式**：已确认无路由引用，D 任务可能计划作为顶级页或工作台 Tab。这会影响 §5.6 的建议。
6. **`ChunkCheckpoint` 的跨进程恢复语义**：`BookTranslationEngine.kt:99-106` 的 checkpoint 文件（`.translation_checkpoint_` 前缀）在进程被杀后是否被正确读取，需实机验证。
7. **`EditionExporter` 对原 EPUB 样式的保留程度**：本文判断为"重建级、丢失原样式"，基于 `EditionExporter.kt:56`（固定 CSS）与 `159`（按空行分段）。若导入时已缓存原 XHTML 结构则判断需修正——`EpubParser.kt` 未读取原样式，初步支持本文判断，但需确认 `BookFileManager.source/` 是否保留原包。

## 附录 B：LG 侧关键文件索引（按本文引用频次）

| 主题 | 文件 |
|---|---|
| 领域模型 | `src/domain/item.ts`、`quality.ts`、`prompt.ts`、`batch-translation.ts`、`model.ts`、`setting.ts`、`language.ts` |
| 文本机制 | `src/shared/text/{text-preserve-rules,text-resource-reference,text-replacement-rules,translation-source,translation-output-restoration(在 backend),text-ruby-cleaner,text-pattern,literal-matcher,punctuation-structure,translation-quality-rules,text-fakename-injector,text-rule-type,text-lines,text-processing}.ts` |
| 质量规则 | `src/shared/quality/{glossary,quality-rule-import,quality-rule-state,quality-rule-entry,quality-statistics}.ts` |
| 翻译执行 | `src/backend/batch-translation/core/*`、`planning/*`、`work-unit/**`、`batch-translation-{runner,runtime,service,project-store}.ts` |
| 校对 | `src/backend/proofreading/*`、`src/shared/proofreading/*` |
| 导入导出 | `src/backend/file/**` |
| Agent | `src/backend/agent/**`、`builtin/skills/**` |
| 产品定义 | `PRODUCT.md`、`README.md` |

## 附录 C：LNT 侧关键文件索引

| 主题 | 文件 |
|---|---|
| 引擎 | `core/translation/BookTranslationEngine.kt`、`TranslationProtocol.kt`、`ContextEngine.kt`、`ProviderPromptCache.kt`、`TranslationTextProtection.kt`、`LexiconReplacementService.kt`、`TranslationScopePlanner.kt`、`TranslationFailurePolicy.kt`、`TranslationContinuity.kt`、`GlossaryQaStatus.kt` |
| LG 文本机制移植（重构中） | `core/text/{TextProtectionRules,TextProtectionPresets,TranslationSourcePreparer,PostTranslationRestorer,ResourceReferenceSlots,LiteralMatcher,GlossaryCompiler,TranslationQualityRules,LanguageCodes,PrecedingContextBuilder}.kt` |
| LLM | `core/llm/{LlmGateway,LlmClient,TokenCalculator,TranslationPrompts}.kt` |
| Agent | `core/agent/{AgentToolHost,TermExtractionAgent,ChapterSplitAgent,LexiconCandidateAggregator,TermCandidateValidation}.kt`、`core/agent/koog/**`、`ui/screens/agent/**`、`assets/agent/system-prompt.md` |
| 端侧 | `core/ondevice/**`、`app/src/main/assets/ondevice/prompts/` |
| 解析与导入 | `core/parser/**`、`core/book/{BookImporter,BookFileManager}.kt` |
| 导出 | `core/exporter/EditionExporter.kt` |
| 数据 | `data/model/{ReaderPlatformEntities,LexiconCandidateAggregate,AgentEntities,ApiProviderEntity,PresetModels}.kt`、`data/db/{AppDatabase,ReaderPlatformDao,AgentDao,ApiProviderDao,LexiconCandidateAggregateDao}.kt`、`data/repository/BookPlatformRepository.kt` |
| UI | `ui/screens/{workspace/BookWorkbenchDetailScreen,tasks/PlatformTaskCenterScreen,reader/PlatformReaderScreen,settings/ApiSettingsScreen,agent/AgentChatScreen}.kt`、`ui/viewmodel/AppViewModel.kt` |
| 重构计划 | `docs/plans/2026-10-19-master-refactor-plan.md` |
