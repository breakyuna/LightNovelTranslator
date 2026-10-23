# LightNovelTranslator Agent 工作规范

本文件适用于整个仓库，是唯一的根级指令文件（仓库当前没有子目录 `AGENTS.md`、`CLAUDE.md`、`.cursorrules` 或 `opencode.json`）。所有 Agent 在分析、修改、审查或交付代码前必须遵守。用户当前指令优先；判断现状以代码和构建脚本为准，不能把目标设计写成已实现事实。

## 1. 项目定位

2026-10-22 用户决定全面重构：以 LG Termux 的完整功能为目标，重做原生 Android UI 和业务实现，仅保留并改造 LNT 的 Koog Agent 接入框架。新产品以工程和 Agent 工作流为中心：

```text
Project（工程）
├── Asset（固化原稿与格式身份）
├── Item / PDFPage（翻译、校对和写回定位）
├── Rules / Prompts / Revisions（规则、提示词和提交事实）
└── AgentChat / Runs（Koog 编排、工作区、审批与任务）
```

工程首页、打开工程默认进入 Agent、完整功能范围和 Koog 保留清单，以 [原生 LG 重构计划](docs/plans/2026-10-22-native-lg-rebuild/README.md) 为准。旧书架、阅读器、章节/Edition 模型、翻译引擎和 UI 均进入替换范围，不再要求保留旧首页、导航或视觉体系。

当前保留大量旧阅读平台源码作为迁移来源，MainActivity 已在源码中改接 nativeapp 原生工程入口（用户已确认本轮 TXT 手工切片的 CI/设备检查通过，边界见 HANDOFF §22）；`README.md`、2026-08-25 设计及 2026-10-19 计划用于理解现状和数据迁移，不约束新产品定位。`REFACTOR_REPORT.md` 是历史快照。计划编写不自动启动产品重构，按用户当前授权范围执行；旧数据须先保护、迁移和验证，不能因重构直接删除。

## 2. 构建、测试与验证命令

- 工具链：AGP 9.1.1、**Kotlin 2.3.10、KSP 2.3.10、Java 17 字节码**（Koog 的 Android 产物要求）、Gradle 9.3.1 wrapper、version catalog（`gradle/libs.versions.toml`）。`settings.gradle.kts` 设置 `FAIL_ON_PROJECT_REPOS`：**不得在模块构建脚本里写 `repositories {}`**，新增依赖一律加进 version catalog。
- catalog 里有大量未使用的历史依赖声明（firebase、camera、retrofit、coil 等）。用前先确认 `app/build.gradle.kts` 是否真的引入，不要假设存在即可用。
- SDK：`minSdk 24`、`targetSdk 36`、`compileSdk 36 (minorApiLevel 1)`。ML Kit GenAI 要求 API 26+，已用 `tools:overrideLibrary` 放行 merger，**所有 GenAI 调用点必须守卫 `Build.VERSION.SDK_INT >= 26` 与 feature 可用性**。`versionCode` 在 CI 取 `GITHUB_RUN_NUMBER`，本地为 7。
- **Android 14+（API 34）正则地雷**：`java.util.regex` 在 API 34 切到 ICU 实现。`core/text/TextProtectionRules` 已对 `(?U)` 做编译兜底；新增正则代码禁止裸用 ICU 不兼容的内联标志，必要时同样 try/catch 兜底。Robolectric 跑 JVM 正则**测不出**这类问题，必须真机验证。
- **命名分组按名取值为 API 26**：`match.groups[name]` 底层调 `Matcher.start(String)`，minSdk 24 下 lint 直接报错。一律用 `namedGroupIndexes(pattern)` 预解析成组序号再按序号取（core/quality 与 core/text 各有一份实现）。
- **其他 Kotlin 2.3 实测地雷**（`kotlinx.coroutines.CoroutineContext` 是 HIDDEN 弃用 typealias 要用 `kotlin.coroutines` 版、`resumeWithException` 是 stdlib 扩展需 import、`createTempDir` 是 ERROR 级弃用、`toList()` 对 0/1 元素不可变等）：完整清单见 `docs/plans/2026-10-19-build-and-ci-playbook.md`，写代码前必读。
- **测试纪律**：新增测试必须落地后可运行才算数；禁构建期间用 Gradle 缓存里的 `kotlin-compiler-embeddable` 做单文件编译 + JUnit 运行验证。测试期望依据用户目标、适用的设计与机器契约；先确认旧 KDoc/文档是否已被新计划替代，失败时先判定“测试错还是实现错”。
- **【最高优先级】本机禁止运行任何 gradle / 构建 / 测试 / lint 命令**：开发设备是 Android/Termux，内存与 CPU 撑不起 Gradle 守护进程 + Kotlin 编译 + Robolectric 的并发负载，并行 agent 同时构建会拖垮整机。所有 Agent（含 subagent）**只做静态验证**（`rg` / `read` / 符号核对 / 人工推演），验证结论必须注明"静态审查，未实际构建"。构建与测试由 CI（push 到 `main` / `beta` 或 `workflow_dispatch`）或用户在 PC 上执行；不得因为"没跑构建"而假装跑过，也不得为了跑构建而杀掉他人进程。若发现本机已有 gradle/java 守护进程在跑，先 `pkill -f GradleDaemon` 停掉再继续静态工作。
- **CI**：`test/lint/release` 三个并行 job；测试已配 `maxParallelForks`（勿改回单 fork）；artifact 名不能含 `/`。
- 本地标准命令（仅限 CI 或 PC 环境执行，本机禁跑）：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

- 跑单个测试类：

```bash
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.translation.TranslationProtocolTest"
```

- CI（`.github/workflows/android.yml`）：push 到 `main` / `beta` 或 `workflow_dispatch` 触发，执行 `./gradlew testDebugUnitTest lintDebug assembleRelease`，需要 4 个签名 Secrets；本地打签名 Release 需提供 `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` 环境变量。
- 单元测试全部是 JVM Robolectric，无需真机，但需要 Android SDK。**本机（Termux/Android 开发设备）禁止执行任何构建与测试**，理由见上文；静态审查（`rg`/`read`/符号核对）是唯一的本地验证手段，汇报时如实说明"未构建验证"。
- 截图测试：Roborazzi，用 `captureRoboImage(filePath = "src/test/screenshots/...")` 显式指定路径，产物在 `app/src/test/screenshots/`。改动 UI 后用 `./gradlew recordRoborazziDebug` 重新录制（同样只在 CI/PC 执行）。新增截图测试沿用现有 `@RunWith(RobolectricTestRunner)` + `@GraphicsMode(NATIVE)` + `@Config(qualifiers = Pixel8, sdk = [35])` 配置。
- `app/src/test` 下的 `ExampleUnitTest` / `ExampleRobolectricTest` 是模板遗留，不代表真实覆盖。

## 3. 旧实现代码地图（迁移来源）

下图描述重构前的实际布局，不是新架构约束。新模块职责见重构计划中的 `ARCHITECTURE.md`；源码迁移时同步更新本节，不能把目标目录当成已存在实现。

```text
app/src/main/java/com/breakyuna/noveltranslator/
├── core/
│   ├── agent/          # AgentToolHost（21 个全流程工具 + 文本协议调度）、章节识别、术语抽取
│   │   └── koog/       # Koog 编排：会话、PromptExecutor 桥接（原生 tools + 文本协议双通道）、
│   │                   # 协议解析加固（全角归一化/变体标签/宽容 JSON/疑似调用/熔断）、持久化
│   ├── book/           # BookFileManager（正文/封面/文件唯一管理者）、BookImporter
│   ├── exporter/       # EditionExporter
│   ├── llm/            # LlmGateway（所有 LLM 调用唯一入口）、LlmClient、RetryPolicy、TokenCalculator、TranslationPrompts
│   ├── crash/         # CrashReporter（崩溃落盘 + last-crash 读取，重启不丢）
│   ├── logger/         # 脱敏系统日志（内存 Flow + jsonl 落盘轮转 + 导出）
│   ├── parser/         # TXT / EPUB 解析
│   ├── sample/         # 示例数据
│   ├── security/       # ApiKeyCipher（Key 加解密边界）
│   └── translation/    # BookTranslationEngine、TranslationProtocol、ContextEngine、QA、ScopePlanner、FailurePolicy
├── data/
│   ├── db/             # AppDatabase、DAO（ReaderPlatformDao、ApiProviderDao 等）
│   ├── model/          # Entity、领域模型、轻量查询投影
│   └── repository/     # BookPlatformRepository 等数据边界
└── ui/
    ├── adaptive/、components/apple/（旧视觉组件，按新 UI 计划替换）、theme/
    ├── i18n/           # AppStrings（Kotlin 实现，见 §4）
    ├── navigation/     # AppDestination、TopLevelDestination、AppBottomBar、AppNavigationRail、AppNavigation
    ├── screens/        # bookshelf、history、reader、workspace、tasks、settings
    └── viewmodel/      # AppViewModel（旧阅读平台 ViewModel）
```

实施新增 `core/domain`、`core/engine`、`core/agent`、`core/storage`、`core/formats`、`core/workspace` 模块，包名使用 `com.breakyuna.noveltranslator.nativecore`。domain/engine 为 Kotlin/JVM；其余为 Android Library。engine 已有进程内协调与生命周期原型；agent 已有类型化请求/只读工具边界，统一网关尚未完整接入；core/agent 新增 Koog 固定查询/准备差异适配，持久单条审批及结构化对话切片已有 CI 证据（见 HANDOFF §26—27），不代表完整 Agent/审批可用。storage 的独立 Room 索引、原生 `.lnt` TXT 数据库、手工事务与一致快照已有指定提交的 CI 证据；新增外部导入、复制、恢复、双语导出和临时产物清理的实现及验收边界见 HANDOFF §20—22。formats/workspace 完整业务尚未实现。

新入口位于 `app/.../nativeapp`：`NativeAppGraph` 注入共享运行协调器和存储端口；`NativeProjectViewModel` 管理工程、分页预览、单条草稿与系统文件选择结果；`NativeProjectApp` 提供工程首页、Agent 入口、工作台、人工校对和编辑器，文案使用五语 Android 资源。MainActivity 已改接此入口，用户 CI/真机确认和已查看设备截图的范围见 HANDOFF §22；Agent 页已有明确标注的本地 Koog 查询/准备差异和单条审批演练（CI 已覆盖指定版本，设备验收待补），§25 新增自由文本对话、共享远程网关/兼容传输与模型设置实现（已有 CI，真实供应商未联调）；校对页已新增需确认的本页本地模拟流水线（已有 CI，新增设备验收待补），经 NativeModelGateway/翻译运行租约与 ProjectWriteStore 写回，不调用旧引擎。旧源码与用户文件保留，旧库尚未迁移。§28—30 工作区另增持久执行记录/中断核对、对话式 Agent 页、HTTP 端点/可选凭据、GET 模型列表及兼容 SSE 流式预览，均尚未构建；元数据获取不发送聊天或测试推理。实际进度与验证边界只记入重构 HANDOFF。

理解旧行为时追踪 UI → AppViewModel → Repository / DAO → Engine / BookFileManager。新链路按 UI → 应用服务 → 业务核心/存储端口组织，Agent 与手动操作共用写入口，不再向旧 `AppViewModel` 继续集中新业务。用 `rg` 定位实际调用链。

## 4. 仓库特有约定（最容易踩的坑）

- **国际化迁移**：旧 UI 使用 `AppStrings` / `ChineseStrings` / `EnglishStrings`，维护旧链路时保持两种语言完整。新 Compose UI 按 `UI_SPEC.md` 迁至 Android 字符串资源，最终覆盖 LG 的中、英、日、韩、德五种语言；不要同时维护两套新文案权威来源。
- **导航全面重做**：新工程首页、Agent、工作台、校对和全部管理页面按 `UI_SPEC.md` 实现。手机和平板共用路由定义并保留等价功能，不再冻结书架/历史/任务/设置的旧顺序。
- **旧 Room 库与新存储分开**：旧库 `light_novel_translation_platform.db` 当前为 v6，迁移与 `app/schemas/` 用于旧数据读取/迁移验证，不能直接清库或当成新 `.lnt` 存储。LNT 原生工程统一使用 `.lnt`，由专用 SQLite 适配器管理独立版本与迁移；`.lg` 仅用于 LG 兼容导入/导出，不得靠改后缀冒充转换。新应用记录使用独立 Room schema；当前源码 NativeAppDatabase 已升至 v3，保留 v1→v2 并新增 v2→v3 迁移；v2 的 Agent 历史/payload、请求/审批、会话和模型配置表已有指定提交 CI 证据，v3 持久执行记录与中断核对尚未构建，2.json/3.json 导出仍待 PC/CI 的 KSP 生成与审阅（保留既有 1.json，不手写）。改各自 Entity/schema 时同步版本、迁移注册、DAO/投影和导出 schema。不要复活旧阅读平台主链路，也不要用破坏性迁移绕过数据转换。
- 旧书籍文件迁移前仍由 `BookFileManager` 定位，禁止绕过它清理源数据；新原稿、工作区和产物按重构计划分配唯一文件拥有者。不得把大段正文或二进制塞进 UI 状态；请求日志列表只查轻量投影，完整正文按 ID 延迟加载。
- `remember` / `rememberSaveable` 的 key 必须包含实际依赖的 Project / Chat / Asset / Run 身份，维护旧页面时也包含 Book / Edition 身份，防止切换对象复用旧状态。
- 大列表必须用 `LazyColumn` / `LazyRow` / `LazyVerticalGrid` 并提供稳定唯一 key；不得在 Composable 或主线程做图片解码、整本文件读取、EPUB 解析、数据库循环和网络请求；高频 Flow（进度、任务状态、日志）只在可见时订阅，避免整页无条件重组。页面切换动画保持短促（现有 NavHost 用 90/70ms fade）。
- 旧书架交互不是新 UI 的保留要求。新工程和技能排序、触屏替代操作、编辑草稿、焦点及返回行为按新 UI 验收；迁移期间不要误报旧页面行为已实现。

## 5. 翻译与安全不变量

- 新翻译以工程条目、资产和 PDF 页为边界，规划、并发、前文、去重和重试对齐 LG，不继续套用旧小说的固定章节串行和 1–5 章配置。
- 所有模型调用和重试走新统一模型网关与调度器，禁止 UI 或 Koog 适配器另建付费请求旁路。区分网络、超时、限流、服务端、认证、上下文溢出、截断、解析和内容失败；非瞬时错误不得盲目重试。
- 逐批提交有效译文；后续失败或取消保留已提交事实。Token、缓存、思考、输出与请求次数按实际回执累计，估算与未知值明确区分。
- 暂停/取消后不得再发起付费请求；恢复从已持久化边界继续。
- 新响应协议必须保留条目/页面稳定身份、姓名和格式定位，校对规则与失败分类对齐 LG；旧 Chapter/Segment 协议只用于旧数据读取和迁移校验。
- 术语与材料处理覆盖用户选定的完整工程范围，结果绑定正确 Project/Chat；不保留“默认取第一个工程”等歧义行为。
- Agent 付费/写入参数必须无损验证类型、整数性和范围；审批绑定实际差异与工程身份，模型文案中的确认要求不能代替执行层授权。
- API Key 经新凭据存储端口读取，旧 `ApiKeyCipher` 仅作为迁移来源；禁止把 Key、Authorization Header、签名密钥写进日志、提示词、异常、Debug 数据、源码、测试或提交信息。对外部文件名、压缩包路径、工作区链接与图片标记做边界校验，防目录穿越。
- Debug 默认关闭，只记录开启后的新请求；关闭时仍保留轻量状态、Token、费用、错误分类和失败摘要。Debug 数据可能含小说正文，UI 需提醒谨慎分享。
- 新修改统一通过 `ProjectWriteStore` 及工程修订/对象指纹检查；保留已提交事实与可审计回执。旧 `SegmentRevision` 作为迁移来源，不成为新工程模型。

## 6. 工作方式与交付边界

1. 本次全面重构先读 `docs/plans/2026-10-22-native-lg-rebuild/README.md`、对应专题与交接记录，再核对实际实现；维护旧迁移来源时再查旧 README 和设计。
2. `git status --short --branch` 检查工作区；现有修改可能属于用户或其他 Agent，不得覆盖、回退或顺手整理。
3. 按用户授权的整体重构范围完成必要架构和 UI 替换；不把行数最少当目标，也不做计划外重命名、格式化或旁支改造。只要求计划时不启动产品实现。
4. 除非用户明确要求，不提交、不推送、不强推、不建 PR、不合并、不触发 Actions。禁止 `git reset --hard`、`git checkout --`、`git clean` 等破坏性命令。用户要求提交时，提交信息说明真实行为和可靠性影响；要求推送时先确认目标分支，不得默认推 `main`。
5. 交付前至少执行：`git diff --check`、`git status --short --branch`、冲突标记检查 `rg -n "^(<<<<<<<|=======|>>>>>>>)" . -g '!**/build/**'`，并按改动范围跑对应测试/Lint/构建。
6. **未实际执行成功的验证，不得声称"编译通过""测试通过""APK 可用"。**

## 7. 汇报要求

交付时简要说明：用户可见行为变化；根因与关键实现决策；改动的主要模块；实际执行了哪些验证及结果（未执行的如实说明）；是否提交/推送/触发 Actions（没有就明说）；仍存在的真实限制或需实机验证的交互，不用"应该没问题"敷衍。

## 8. 重构计划与历史索引

当前目标入口：[2026-10-22 原生 LG 全面重构计划](docs/plans/2026-10-22-native-lg-rebuild/README.md)。实际进度只记入同目录 `HANDOFF.md`。

以下 2026-10-19 文档保留为旧阅读平台重构的历史材料，部分状态已经过时，使用时必须核对当前代码：

| 文档 | 内容 |
|---|---|
| `2026-10-19-master-refactor-plan.md` | 主计划、任务分工与执行状态、剩余 backlog |
| `2026-10-19-build-and-ci-playbook.md` | **实战手册**：Kotlin 2.3/AGP 9/CI 全部实测地雷、CI 结构与耗时数据、测试纪律 |
| `2026-10-19-architecture-overview.md` | 现役架构总览（模块地图/执行流/配置矩阵） |
| `lg-parity-gap-analysis.md` | 与 LinguaGacha 的功能对标差距分析 |
| `2026-10-19-agent-layer-review.md` | Koog Agent 层 API 核实与测试覆盖 |
| `2026-10-19-build-readiness-checklist.md` | 首次构建验证预案（javap 实证） |

规划基线分支为 `refactor/translation-agent-2026-10`，后续开始前重新核对。CI 的 test/lint/release 全绿是交付必要条件；新产品还必须完成计划规定的数据兼容、格式闭环、真机交互、视觉及后台恢复验收，不能仅凭 CI 全绿判定完整重构完成。
