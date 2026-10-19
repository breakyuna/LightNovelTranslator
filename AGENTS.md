# LightNovelTranslator Agent 工作规范

本文件适用于整个仓库，是唯一的根级指令文件（仓库当前没有子目录 `AGENTS.md`、`CLAUDE.md`、`.cursorrules` 或 `opencode.json`）。所有 Agent 在分析、修改、审查或交付代码前必须遵守；与现行代码冲突时以代码和构建脚本为准。

## 1. 项目定位

以本地书籍阅读为中心、LLM 翻译为增强能力的单模块 Android 应用（`app`）。领域边界必须保持：

```text
Book（作品）
├── LogicalChapter / LogicalSegment（跨 Edition 稳定的逻辑位置）
├── Edition（原文 / AI 译文 / 人工版本等可读文本）
├── ReaderProgress（基于逻辑位置，不绑定页码、滚动像素或单一 Edition）
└── TranslationProject（从源 Edition 生产目标 Edition）
    ├── Lexicon / StoryMemory / ChapterMemory
    └── TranslationRun / Batch / RequestLog（Token、费用、失败审计）
```

不得把首页改回"翻译工程列表"。架构背景见 `README.md` 与 `docs/superpowers/specs/2026-08-25-reader-translation-platform-design.md`；`REFACTOR_REPORT.md` 是 2026-08-24 历史快照，自述不作为现行架构清单，勿据此改代码。

## 2. 构建、测试与验证命令

- 工具链：AGP 9.1.1、**Kotlin 2.3.10、KSP 2.3.10、Java 17 字节码**（Koog 的 Android 产物要求）、Gradle 9.3.1 wrapper、version catalog（`gradle/libs.versions.toml`）。`settings.gradle.kts` 设置 `FAIL_ON_PROJECT_REPOS`：**不得在模块构建脚本里写 `repositories {}`**，新增依赖一律加进 version catalog。
- catalog 里有大量未使用的历史依赖声明（firebase、camera、retrofit、coil 等）。用前先确认 `app/build.gradle.kts` 是否真的引入，不要假设存在即可用。
- SDK：`minSdk 24`、`targetSdk 36`、`compileSdk 36 (minorApiLevel 1)`。ML Kit GenAI 要求 API 26+，已用 `tools:overrideLibrary` 放行 merger，**所有 GenAI 调用点必须守卫 `Build.VERSION.SDK_INT >= 26` 与 feature 可用性**。`versionCode` 在 CI 取 `GITHUB_RUN_NUMBER`，本地为 7。
- **Android 14+（API 34）正则地雷**：`java.util.regex` 在 API 34 切到 ICU 实现。`core/text/TextProtectionRules` 已对 `(?U)` 做编译兜底；新增正则代码禁止裸用 ICU 不兼容的内联标志，必要时同样 try/catch 兜底。Robolectric 跑 JVM 正则**测不出**这类问题，必须真机验证。
- 本地验证（README 记录的标准命令）：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

- 跑单个测试类：

```bash
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.translation.TranslationProtocolTest"
```

- CI（`.github/workflows/android.yml`）：push 到 `main` / `beta` 或 `workflow_dispatch` 触发，执行 `./gradlew testDebugUnitTest lintDebug assembleRelease`，需要 4 个签名 Secrets；本地打签名 Release 需提供 `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` 环境变量。
- 单元测试全部是 JVM Robolectric，无需真机，但需要 Android SDK。在没有 SDK 的环境（如 Termux）不要假装执行过构建；能跑多少跑多少，汇报时如实说明。
- 截图测试：Roborazzi，用 `captureRoboImage(filePath = "src/test/screenshots/...")` 显式指定路径，产物在 `app/src/test/screenshots/`。改动 UI 后用 `./gradlew recordRoborazziDebug` 重新录制。新增截图测试沿用现有 `@RunWith(RobolectricTestRunner)` + `@GraphicsMode(NATIVE)` + `@Config(qualifiers = Pixel8, sdk = [35])` 配置。
- `app/src/test` 下的 `ExampleUnitTest` / `ExampleRobolectricTest` 是模板遗留，不代表真实覆盖。

## 3. 代码地图

```text
app/src/main/java/com/breakyuna/noveltranslator/
├── core/
│   ├── agent/          # 章节识别、术语抽取
│   ├── book/           # BookFileManager（正文/封面/文件唯一管理者）、BookImporter
│   ├── exporter/       # EditionExporter
│   ├── llm/            # LlmGateway（所有 LLM 调用唯一入口）、LlmClient、RetryPolicy、TokenCalculator、TranslationPrompts
│   ├── logger/         # 脱敏系统日志
│   ├── parser/         # TXT / EPUB 解析
│   ├── sample/         # 示例数据
│   ├── security/       # ApiKeyCipher（Key 加解密边界）
│   └── translation/    # BookTranslationEngine、TranslationProtocol、ContextEngine、QA、ScopePlanner、FailurePolicy
├── data/
│   ├── db/             # AppDatabase、DAO（ReaderPlatformDao、ApiProviderDao 等）
│   ├── model/          # Entity、领域模型、轻量查询投影
│   └── repository/     # BookPlatformRepository 等数据边界
└── ui/
    ├── adaptive/、components/apple/（复用这套视觉组件和主题，勿另起体系）、theme/
    ├── i18n/           # AppStrings（Kotlin 实现，见 §4）
    ├── navigation/     # AppDestination、TopLevelDestination、AppBottomBar、AppNavigationRail、AppNavigation
    ├── screens/        # bookshelf、history、reader、workspace、tasks、settings
    └── viewmodel/      # AppViewModel（当前唯一 ViewModel）
```

修改前先追踪完整链路：UI → AppViewModel → Repository / DAO → Engine / BookFileManager。用 `rg` 定位符号和调用链，不要按文件名猜实现。

## 4. 仓库特有约定（最容易踩的坑）

- **i18n 是纯 Kotlin 的，不用 XML 字符串资源**：`AppStrings` 接口 + `ChineseStrings` / `EnglishStrings` 两个 object，经 `LocalAppStrings`（`staticCompositionLocalOf`，默认中文）下发；`res/values/strings.xml` 只有 `app_name`。新增任何用户可见文案必须同时补 `ChineseStrings` 和 `EnglishStrings`（阅读平台页面用 `PlatformUiStrings`），只改一处会出现中英界面文案缺失。顶层导航 label 在 `TopLevelDestination.getLabel()` 里硬编码判断 `strings === EnglishStrings`，改 label 要同步。
- **导航双端必须一致**：顶级顺序固定为 `BOOKSHELF（书架）→ HISTORY（阅读历史）→ TASKS（工作台，route 是 "tasks"）→ SETTINGS`。`AppBottomBar`（手机底栏）、`AppNavigationRail`（平板侧栏）和 `NavHost`（`AppNavigation.kt`）要同时改；新增顶级页三处都要接入。
- **Room schema 已入库**：`exportSchema = true`，KSP `room.schemaLocation` 指向 `app/schemas/`（已提交 1/3/4.json），`sourceSets.test.assets` 包含该目录供 Migration 测试使用。当前 `version = 4`，数据库名 `light_novel_translation_platform.db`，迁移 `MIGRATION_1_2 / 2_3 / 3_4` 在 `AppDatabase.getDatabase()` 手动注册。改 Entity 必须同步：`@Database` 版本与实体列表、新 Migration 及 `addMigrations` 注册、DAO 查询与投影、schema JSON。旧版项目/章节/任务表是故意移除的，不要复活。
- 正文、封面、EPUB 插图、导出文件统一走 `BookFileManager`（`files/books/book_<id>/...` 与 `cache/` 分层），不得把大段正文或二进制塞进 UI 状态；请求日志列表只查轻量投影，完整正文按 ID 延迟加载。
- `remember` / `rememberSaveable` 的 key 必须包含依赖的 Book / Edition / Project / Run ID，防止切换对象复用旧状态。
- 大列表必须用 `LazyColumn` / `LazyRow` / `LazyVerticalGrid` 并提供稳定唯一 key；不得在 Composable 或主线程做图片解码、整本文件读取、EPUB 解析、数据库循环和网络请求；高频 Flow（进度、任务状态、日志）只在可见时订阅，避免整页无条件重组。页面切换动画保持短促（现有 NavHost 用 90/70ms fade）。
- 书架卡片支持长按拖拽排序，改交互时不得破坏；当前 `onDoubleClick` 参数存在但调用方传 `null`，"双击继续阅读"并未启用，不要按 README 旧描述实现或声称它存在。

## 5. 翻译与安全不变量

- 同一本书内翻译严格串行；并发只允许跨书或跨独立工程。默认按单章请求，用户可配 1–5 章；只有单章超 Token 预算才分 Chunk。
- 所有重试、续写、修复、重分块必须走统一 `LlmGateway` / `RetryPolicy`，禁止在 UI 私自重复调用 API。重试需区分网络、超时、限流、服务端、认证、上下文溢出、截断、解析、QA 错误；认证/无效请求/内容过滤等非瞬时错误不得盲目重试。
- 已完成并通过 QA 的章节独立提交；后续章节失败不得回滚或对已完成章节重复计费。Token、费用、请求次数按实际尝试累计（失败调用也计）。
- 暂停/取消后不得再发起付费请求；恢复从已持久化边界继续。
- 翻译响应必须保持 `TranslationProtocol` 的 Chapter / Segment ID 边界；持久化顺序以源 Segment 顺序为准，不依赖 Map 迭代顺序。QA 拦截缺段、空输出、异常长度、拒答、图片标记变化和已确认术语违规，但不得用误伤正常译文的过严规则；首次失败优先精确修复，修复仍失败必须记录具体检查项。
- 术语扫描必须覆盖用户选定的完整章节范围，分批串行，不得静默只取前几章；结果绑定 TranslationProject、按源术语去重；AI 产生的词表和 Memory 必须保留来源、审核状态和 Project，避免跨书污染。
- API Key 只经 `ApiKeyCipher` 边界读取；禁止把 Key、Authorization Header、签名密钥写进日志、提示词、异常、Debug 数据、源码、测试或提交信息。对外部文件名、EPUB 路径、图片标记做边界校验，防目录穿越。
- Debug 默认关闭，只记录开启后的新请求；关闭时仍保留轻量状态、Token、费用、错误分类和失败摘要。Debug 数据可能含小说正文，UI 需提醒谨慎分享。
- 人工修改、术语替换、自动修复走 `SegmentRevision`，不得静默覆盖历史译文。

## 6. 工作方式与交付边界

1. 先读 `README.md`、相关设计文档和任务涉及的具体实现文件，再动手。
2. `git status --short --branch` 检查工作区；现有修改可能属于用户或其他 Agent，不得覆盖、回退或顺手整理。
3. 只做最小且完整的改动，不做无关重命名、格式化或架构重写。
4. 除非用户明确要求，不提交、不推送、不强推、不建 PR、不合并、不触发 Actions。禁止 `git reset --hard`、`git checkout --`、`git clean` 等破坏性命令。用户要求提交时，提交信息说明真实行为和可靠性影响；要求推送时先确认目标分支，不得默认推 `main`。
5. 交付前至少执行：`git diff --check`、`git status --short --branch`、冲突标记检查 `rg -n "^(<<<<<<<|=======|>>>>>>>)" . -g '!**/build/**'`，并按改动范围跑对应测试/Lint/构建。
6. **未实际执行成功的验证，不得声称"编译通过""测试通过""APK 可用"。**

## 7. 汇报要求

交付时简要说明：用户可见行为变化；根因与关键实现决策；改动的主要模块；实际执行了哪些验证及结果（未执行的如实说明）；是否提交/推送/触发 Actions（没有就明说）；仍存在的真实限制或需实机验证的交互，不用"应该没问题"敷衍。
