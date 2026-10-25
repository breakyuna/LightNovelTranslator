# 文档目录与读取方式

本目录是导航，不是进度记录。日常任务只读相关文件；当前实现以源码为准，进度以 HANDOFF 为准。历史资料与早期切片契约可能落后于源码，不可直接作为当前完成结论。

## 根目录

- [AGENTS.md](../AGENTS.md)：精简协作规则。
- [README.md](../README.md)：仓库入口，含旧平台说明，不必每轮通读。
- [REFACTOR_REPORT.md](../REFACTOR_REPORT.md)：历史审计快照。

## 开发与当前重构

- [ENGINEERING_GUIDE.md](ENGINEERING_GUIDE.md)：从根规则移出的技术细则，按相关小节查阅。
- 查进度只读精简 HANDOFF；历史轮次去 HANDOFF_HISTORY 搜索标题后分段读取，不通读归档。
- 目标、架构、功能、实施与 UI 文档按任务选择；专题文档说明各开发切片，不单独维护当前进度。

| 文件 | 用途/标题 |
|---|---|
| [AGENT_CONTRACT.md](plans/2026-10-22-native-lg-rebuild/AGENT_CONTRACT.md) | Agent 原生消息与单次请求边界 |
| [AGENT_CONVERSATION.md](plans/2026-10-22-native-lg-rebuild/AGENT_CONVERSATION.md) | 原生 Agent 结构化对话与远程模型切片 |
| [APPROVAL_CONTRACT.md](plans/2026-10-22-native-lg-rebuild/APPROVAL_CONTRACT.md) | 审批与工程变更契约 |
| [ARCHITECTURE.md](plans/2026-10-22-native-lg-rebuild/ARCHITECTURE.md) | 原生 LG 目标架构 |
| [BASELINE_GUIDE.md](plans/2026-10-22-native-lg-rebuild/BASELINE_GUIDE.md) | 实施源码保护与迁移索引 |
| [EDITOR_TOOLS.md](plans/2026-10-22-native-lg-rebuild/EDITOR_TOOLS.md) | TXT 编辑定位与草稿查找替换 |
| [FEATURE_MATRIX.md](plans/2026-10-22-native-lg-rebuild/FEATURE_MATRIX.md) | LG → 原生 Android 功能对齐矩阵 |
| [FOREGROUND_TRANSLATION.md](plans/2026-10-22-native-lg-rebuild/FOREGROUND_TRANSLATION.md) | 模拟翻译的前台执行切片 |
| [HANDOFF.md](plans/2026-10-22-native-lg-rebuild/HANDOFF.md) | 原生 LG 重构交接记录 |
| [HANDOFF_HISTORY.md](plans/2026-10-22-native-lg-rebuild/HANDOFF_HISTORY.md) | 历史交接完整归档，仅按需追溯 |
| [IMPLEMENTATION_PLAN.md](plans/2026-10-22-native-lg-rebuild/IMPLEMENTATION_PLAN.md) | 实施、依赖与验收计划 |
| [LEGACY_FIELD_SAMPLE.md](plans/2026-10-22-native-lg-rebuild/LEGACY_FIELD_SAMPLE.md) | 旧 LNT 数据字段映射样本（R36 早期产物，已移出当前范围） |
| [MANUAL_BATCH.md](plans/2026-10-22-native-lg-rebuild/MANUAL_BATCH.md) | TXT 人工批量选择与原子写入 |
| [MODEL_ENDPOINTS.md](plans/2026-10-22-native-lg-rebuild/MODEL_ENDPOINTS.md) | Agent 端点、模型发现与对话界面契约 |
| [MODEL_SCHEDULING.md](plans/2026-10-22-native-lg-rebuild/MODEL_SCHEDULING.md) | 共享请求调度与用量汇总契约 |
| [NATIVE_STORAGE.md](plans/2026-10-22-native-lg-rebuild/NATIVE_STORAGE.md) | 原生 TXT 存储契约 |
| [PROOFREADING_QUERY.md](plans/2026-10-22-native-lg-rebuild/PROOFREADING_QUERY.md) | TXT 校对查询与统计契约 |
| [QUALITY_SCAN.md](plans/2026-10-22-native-lg-rebuild/QUALITY_SCAN.md) | 已保存 TXT 译文的质量扫描与筛选 |
| [README.md](plans/2026-10-22-native-lg-rebuild/README.md) | LNT 原生 Android 全面重构计划 |
| [REMOTE_TRANSLATION.md](plans/2026-10-22-native-lg-rebuild/REMOTE_TRANSLATION.md) | 远程 TXT 批量翻译与持久请求回执 |
| [RGSS_CODECS.md](plans/2026-10-22-native-lg-rebuild/RGSS_CODECS.md) | RGSS 归档、Marshal 与脚本写回 |
| [RULES_AND_PROMPTS.md](plans/2026-10-22-native-lg-rebuild/RULES_AND_PROMPTS.md) | 工程规则、提示词与远程翻译流水线 |
| [RUN_RECOVERY.md](plans/2026-10-22-native-lg-rebuild/RUN_RECOVERY.md) | 持久执行记录与中断核对切片 |
| [S2_AGENT_SLICE.md](plans/2026-10-22-native-lg-rebuild/S2_AGENT_SLICE.md) | S2 原生 Agent 单条 TXT 审批切片 |
| [STATIC_REVIEW.md](plans/2026-10-22-native-lg-rebuild/STATIC_REVIEW.md) | 首轮实施静态审查与构建交接 |
| [STREAMING.md](plans/2026-10-22-native-lg-rebuild/STREAMING.md) | Agent 流式传输与实时预览契约 |
| [SUBTITLE_CODECS.md](plans/2026-10-22-native-lg-rebuild/SUBTITLE_CODECS.md) | 字幕纯文本格式核心 |
| [TEXT_QUALITY.md](plans/2026-10-22-native-lg-rebuild/TEXT_QUALITY.md) | TXT 草稿质量检查契约 |
| [TXT_TRANSLATION.md](plans/2026-10-22-native-lg-rebuild/TXT_TRANSLATION.md) | TXT 翻译流水线首个切片 |
| [UI_SPEC.md](plans/2026-10-22-native-lg-rebuild/UI_SPEC.md) | 原生 Android UI 重做规格 |

其中 LEGACY_FIELD_SAMPLE.md 是已取消 R36 的历史材料，不是迁移交付要求。

## 历史设计与参考

2026-08/10-19 的产品设计与状态不约束当前新产品；构建实战手册仍可按需参考，版本和 API 以当前代码核实。

- [2026-10-19-agent-layer-review.md](plans/2026-10-19-agent-layer-review.md)
- [2026-10-19-architecture-overview.md](plans/2026-10-19-architecture-overview.md)
- [2026-10-19-build-and-ci-playbook.md](plans/2026-10-19-build-and-ci-playbook.md)
- [2026-10-19-build-readiness-checklist.md](plans/2026-10-19-build-readiness-checklist.md)
- [2026-10-19-master-refactor-plan.md](plans/2026-10-19-master-refactor-plan.md)
- [2026-10-19-model-protocol-matrix.md](plans/2026-10-19-model-protocol-matrix.md)
- [lg-parity-gap-analysis.md](plans/lg-parity-gap-analysis.md)
- [2026-08-25-reader-translation-platform-design.md](superpowers/specs/2026-08-25-reader-translation-platform-design.md)

## Schema 说明与运行时资源

- [core/storage/schemas/README.md](../core/storage/schemas/README.md)：schema 导出说明。
- [app/src/main/assets/agent/system-prompt.md](../app/src/main/assets/agent/system-prompt.md)：应用提示词资源，不是 Agent 协作规则。
- [app/src/main/assets/ondevice/prompts/term_extraction_en.txt](../app/src/main/assets/ondevice/prompts/term_extraction_en.txt)：应用提示词资源，不是 Agent 协作规则。
- [app/src/main/assets/ondevice/prompts/term_extraction_zh.txt](../app/src/main/assets/ondevice/prompts/term_extraction_zh.txt)：应用提示词资源，不是 Agent 协作规则。
- [app/src/main/assets/ondevice/prompts/title_translation_en.txt](../app/src/main/assets/ondevice/prompts/title_translation_en.txt)：应用提示词资源，不是 Agent 协作规则。
- [app/src/main/assets/ondevice/prompts/title_translation_zh.txt](../app/src/main/assets/ondevice/prompts/title_translation_zh.txt)：应用提示词资源，不是 Agent 协作规则。

## 盘点范围

本次整理后共 49 个 Markdown/TXT/RST 文件：根目录 3 个、当前重构目录 30 个、历史设计/参考 8 个、开发手册和本目录 2 个、schema 说明 1 个、运行时提示词 5 个。不包含 JSON 索引、schema、构建产物或被忽略文件。

大文件优先按标题/符号搜索后分段读取。精简 HANDOFF 为当前接手入口，原约 13.4 万字符已移入 HANDOFF_HISTORY 历史归档；旧 lg-parity-gap-analysis 约 5.9 万、build-readiness-checklist 约 4.2 万、model-protocol-matrix 约 3.6 万字符。此处大小仅为本次盘点近似值，不要求随业务修改维护。
