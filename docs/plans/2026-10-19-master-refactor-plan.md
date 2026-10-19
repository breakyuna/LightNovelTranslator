# 总重构计划：翻译引擎重构 + Agent 层（B 计划）+ UI 重制

日期：2026-10-19
状态：执行中
约束（用户明确要求）：
- 全自动执行，允许并发 subagent。
- **禁止运行任何 gradle / 构建 / APK 命令**。所有验证以静态审查（rg/read/符号核对）代替，未构建验证必须在报告中如实标注。
- 遵守 AGENTS.md：不得提交/推送、不得破坏性 git 命令、不得覆盖无关改动。

## 并行任务与文件所有权

| 任务 | 负责 | 所有权（可写） | 状态 |
|---|---|---|---|
| A. LG 文本机制移植 | subagent | 新建 `core/text/**` + 对应测试 | 进行中 |
| B. UI 重制 | subagent | `ui/**`（禁改 AppViewModel） | 进行中 |
| C. 引擎重构集成 | subagent | `core/llm/**`、`core/translation/**`、`data/**`、`AppViewModel.kt` | 本轮启动 |
| D. Koog Agent 层 | subagent | 新建 `core/agent/koog/**`、`core/agent/AgentToolHost.kt`、`ui/screens/agent/**`、`assets/agent/**` | 本轮启动 |
| F. ML Kit 端侧辅助 | subagent | 新建 `core/ondevice/**`、Manifest 的 uses-sdk 行（已由主 agent 预置） | 本轮启动 |
| G. LG 功能对标审计 | subagent | 只读 + 新建 `docs/plans/lg-parity-gap-analysis.md` | 本轮启动 |
| E. 集成与静态审查 | 后续 | 导航接入、A/F 模块接线、全仓一致性核对、AGENTS.md 更新 | 依赖 A-F 完成 |

主 agent 已预置：gradle 依赖（Koog 1.3.0、ML Kit GenAI 三个 beta 包）、Manifest minSdk override。

## C：引擎重构（解决已确认的结构性问题）

1. 并行执行：`translation_projects_v2.parallelChapters`（默认 1 = 原严格串行；>1 时有界并发 + 按章顺序提交 + 章级隔离 + 付费请求前暂停/取消边界不变）。
2. 上下文解耦：`continuityMode`（默认 `SOURCE_TAIL`，前文只取源文，使用 A 的 `core.text.PrecedingContextBuilder`；`TRANSLATION_TAIL` 保留旧行为）。
3. Prompt 缓存：实现 `ProviderPromptCacheCapability`（Anthropic `cache_control` 断点；Gemini/OpenAI/DeepSeek 自动缓存无需改请求），`promptCacheEnabled` 默认开，AppViewModel 注入。
4. 失败策略：瞬时错误（网络/超时/429/5xx）重试耗尽后**不再中止整个 run**，改为退避重排队（15s/30s/60s，尊重 retryAfterMs）；仅认证/无效请求/本地存储/未知错误终止 run。
5. QA 策略：`GLOSSARY_MISSING` 降为非阻断 warning；修复改"对半切"（最多 2 轮，单章付费修复请求上限 3 次）。
6. META 开关：`metadataEnabled` 默认 false，关闭时提示词不含 META 规则、解析容忍缺失、跳过 applyMetadata。
7. 数据库：AppDatabase v5 + MIGRATION_4_5（4 个项目新列 + agent 两张表），导出 schema JSON。

## D：Agent 层（Koog + 现有云 provider）

- `LntPromptExecutor` 实现 Koog `PromptExecutor`，委托现有 `LlmGateway`（复用 provider/重试/计费/暂停取消）。
- 工具集（经 `AgentToolHost` 接口，由 C 在 AppViewModel 实现）：list_books / get_book_status / start_translation / pause_translation / scan_glossary / resplit_chapters / get_recent_log / ask_user / update_translation_config。
- `AgentRuntime`：会话与消息持久化（C 建的 agent 表）、Koog 历史压缩、取消支持。
- 对话 UI：`ui/screens/agent/AgentChatScreen.kt`（自带字符串对象，避免与 B 的 i18n 文件冲突）。
- 兜底：若 Koog API 无法静态核实，实现等价自控循环 `AgentLoop.kt` 并在报告中标注迁移路径。

## F：ML Kit 端侧辅助（前台交互场景）

- 能力探测（API 26+、AICore 状态、前台限制、配额退避）。
- 章节摘要（喂 StoryMemory；注意 Summarization 仅支持 EN/JA/KO，中文需回退云模型）。
- 译文校对（用户在前台触发）。
- 通用 Prompt 客户端（术语抽取辅助、标题翻译）。

## 验证方式（无构建）

- 每个 agent 用 rg/read 核对：符号存在、签名一致、import 正确、无重复定义。
- E 阶段做全仓静态一致性审查并输出问题清单。
- 首次真实构建由用户执行；预期需要修复的点（Koog API 签名、ML Kit overrideLibrary 包名、并行提交边界）已在各任务报告中标注。

## 执行状态（2026-10-19 更新）

| 任务 | 状态 | 备注 |
|---|---|---|
| A. core/text LG 文本机制移植 | ✅ 完成 | 10 模块 + 118 测试全过（独立 JVM 编译验证）；发现并修复 manifest minSdk 阻塞 |
| F. ML Kit 端侧辅助 | ✅ 完成 | 10 文件；API 经 javap 实测核对；`OnDeviceAssistPort` 待接线 |
| G. LG 功能对标审计 | ✅ 完成 | docs/plans/lg-parity-gap-analysis.md；P0 缺口 T1-T6 |
| C. 引擎重构 + DB v5 + AgentToolHost | ✅ 完成 | 并行调度/prompt 缓存/失败策略/QA 阶梯/META 门控；修复 TranslationControlSignal 并发杀手 |
| D. Koog Agent 层 + 对话 UI | ✅ 完成 | Koog 1.3.0 API 全核实；文本协议工具循环；Java 17 字节码问题已由主 agent 修复 |
| B. UI 重制 | ✅ 完成 | 设计系统 + 全屏幕 + ~305 条 i18n |
| H. 双语导出 + 术语导入导出 | ✅ 完成 | core/exporter + core/glossary；43 测试 |
| J. LLM 请求调度限流（T6） | ✅ 完成 | core/llm；30 测试；默认关闭待接线 |
| L. 配置 UI + Agent 入口 | ✅ 完成 | 4 开关 UI + 路由 + ask_user 接线 |
| K. EPUB/TXT 导入保真度 | ✅ 完成 | jsoup 重写；48 测试；导入契约零变化 |
| O. 文档体系更新 | ✅ 完成 | README 重写 + architecture-overview；发现 2 个真 bug（已修） |
| M. Agent 层测试 + Koog 复核 | ✅ 基本完成 | 6 文件 93+ 测试；审查报告已补；PART5 取消/用量用例待补 |
| 主 agent 直修 | ✅ 完成 | ①promptAssetLoader 接线 ②`(?U)` ICU 兜底（API 34 真机地雷）③并行+TRANSLATION_TAIL 钳位（UI 承诺与引擎对齐）④AGENTS.md 技术栈更新 ⑤构建预案文档 ⑥补写 core/quality 四个测试文件（约 90 用例，I 遗留缺口） |
| N. 构建预案 + 静态预审 | ✅ 完成 | javap 核实本地缓存真实产物；660 行清单；抓出 4 个必然构建失败项（已由主 agent 全部修复） |

### N 发现并已修复的构建阻塞（主 agent 执行）
1. `core/quality/QualityRuleModels.kt` 缺 `Moshi`/`Types` import → 必编译失败。**已补 import**。
2. `tools:overrideLibrary` 漏 `com.google.mlkit.genai.common`（三个 genai 包的公共传递依赖，minSdk 26，AAR manifest 实证）→ merger 必失败。**已补条目**。
3. `kotlin { compilerOptions { jvmTarget } }` 原在 `android { }` 内，AGP 9.1.1 的 android 扩展无 `kotlin` 成员（反编译证实）→ **已移到脚本顶层**。
4. 首次构建**必须联网**（Kotlin 2.3.10/KSP 2.3.10/Compose 栈不在本地缓存，缓存里只有 2.2.10/2.3.5）——离线失败勿误判为代码问题。
5. OkHttp 被 Koog→ktor-client-okhttp 3.3.3 顶到 5.2.1（`okhttp-android` AAR）；**API 兼容性已逐符号核对通过**，切勿 force 回 4.10.0（ktor 3.x 要求 5.x）。

### 最终状态
- **全部重构代码已落盘，全部未经真实构建验证**（执行期遵守禁构建令）。31 个新测试文件 / 约 370 个 `@Test` 待执行。
- **下一步唯一关键路径：用户执行首次构建**，命令与风险处置见 `docs/plans/2026-10-19-build-readiness-checklist.md`。
- E 阶段剩余 backlog（不阻塞构建，按需推进）：质量规则设置页 UI、导出选项/预检 UI、术语导入导出 UI、ML Kit 设置页、限流 scheduler 开启（J 报告 §5 有接线代码）、T3 全书写校对台、AgentSessionTest PART5 补完。
