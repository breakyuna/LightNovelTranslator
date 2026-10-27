# 工程开发约定（按需查阅）

从原根规则移出的技术细则；修改相关实现时查阅对应小节，不要求每轮通读。用户当前指令和根 AGENTS.md 优先。工具链数值以构建脚本为准，进度与验证状态只看 HANDOFF；下列历史命令仅供 PC/CI，本机禁止构建。

## 2. 构建、测试与验证命令

- 工具链：AGP 9.1.1、**Kotlin 2.3.10、KSP 2.3.10、Java 17 字节码**（Koog 的 Android 产物要求）、Gradle 9.3.1 wrapper、version catalog（`gradle/libs.versions.toml`）。`settings.gradle.kts` 设置 `FAIL_ON_PROJECT_REPOS`：**不得在模块构建脚本里写 `repositories {}`**，新增依赖一律加进 version catalog。
- catalog 里有大量未使用的历史依赖声明（firebase、camera、retrofit、coil 等）。用前先确认 `app/build.gradle.kts` 是否真的引入，不要假设存在即可用。
- SDK：`minSdk 24`、`targetSdk 36`、`compileSdk 36 (minorApiLevel 1)`。ML Kit GenAI 要求 API 26+，已用 `tools:overrideLibrary` 放行 merger，**所有 GenAI 调用点必须守卫 `Build.VERSION.SDK_INT >= 26` 与 feature 可用性**。CI 的 Debug 验证命令传 `-Plnt.versionCode=7`，避免运行序号使 BuildConfig/清单缓存失效；Release 不传该覆盖值，`versionCode` 继续取 `GITHUB_RUN_NUMBER`，本地默认 7。
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
- 单元测试在 JVM 执行：Android 模块的相关用例使用 Robolectric，domain/engine 使用纯 JVM 测试；Android 测试任务需要 Android SDK，无需真机。**本机（Termux/Android 开发设备）禁止执行任何构建与测试**，理由见上文；静态审查（`rg`/`read`/符号核对）是唯一的本地验证手段，汇报时如实说明"未构建验证"。
- 截图测试：Roborazzi，用 `captureRoboImage(filePath = "src/test/screenshots/...")` 显式指定路径，产物在 `app/src/test/screenshots/`。改动 UI 后用 `./gradlew recordRoborazziDebug` 重新录制（同样只在 CI/PC 执行）。新增截图测试沿用现有 `@RunWith(RobolectricTestRunner)` + `@GraphicsMode(NATIVE)` + `@Config(qualifiers = Pixel8, sdk = [35])` 配置。
- 测试按实际断言判断价值：`ExampleUnitTest` 虽沿用旧名，包含内容保留、解析和记账回归；不可按模板命名整类删除。术语响应围栏/非法 JSON 用例集中在 `TermExtractionResponseTest`，因使用 Android `org.json` 保留 Robolectric。纯表单/文件逻辑（如 `NativeSkillMetadataTest`）使用普通 JUnit 与临时目录，避免无必要的 Android 沙箱。


## 4. 仓库特有约定（最容易踩的坑）

- **国际化迁移**：旧 UI 使用 `AppStrings` / `ChineseStrings` / `EnglishStrings`，维护旧链路时保持两种语言完整。新 Compose UI 按 `UI_SPEC.md` 迁至 Android 字符串资源，最终覆盖 LG 的中、英、日、韩、德五种语言；不要同时维护两套新文案权威来源。
- **导航全面重做**：新工程首页、Agent、工作台、校对和全部管理页面按 `UI_SPEC.md` 实现。手机和平板共用路由定义并保留等价功能，不再冻结书架/历史/任务/设置的旧顺序。
- **存储隔离**：旧库、原生 `.lnt` SQLite 与应用 Room 分开管理。修改 Entity/schema 时同步版本、迁移注册、DAO/投影；权威 schema 由 PC/CI KSP 导出并审阅，不手写、不使用破坏性迁移。当前版本与缺失导出见 HANDOFF。
- 若未来处理旧书籍文件，仍由 `BookFileManager` 定位，禁止绕过它清理源数据；当前不要求旧数据迁移。新原稿、工作区和产物按重构计划分配唯一文件拥有者。不得把大段正文或二进制塞进 UI 状态；请求日志列表只查轻量投影，完整正文按 ID 延迟加载。
- `remember` / `rememberSaveable` 的 key 必须包含实际依赖的 Project / Chat / Asset / Run 身份，维护旧页面时也包含 Book / Edition 身份，防止切换对象复用旧状态。
- 大列表必须用 `LazyColumn` / `LazyRow` / `LazyVerticalGrid` 并提供稳定唯一 key；不得在 Composable 或主线程做图片解码、整本文件读取、EPUB 解析、数据库循环和网络请求；高频 Flow（进度、任务状态、日志）只在可见时订阅，避免整页无条件重组。页面切换动画保持短促（现有 NavHost 用 90/70ms fade）。
- 旧书架交互不是新 UI 的保留要求。新工程和技能排序、触屏替代操作、编辑草稿、焦点及返回行为按新 UI 验收；迁移期间不要误报旧页面行为已实现。

## 5. 翻译与安全不变量

- 新翻译以工程条目、资产和 PDF 页为边界，规划、并发、前文、去重和重试对齐 LG，不继续套用旧小说的固定章节串行和 1–5 章配置。
- 所有模型调用和重试走新统一模型网关与调度器，禁止 UI 或 Koog 适配器另建付费请求旁路。区分网络、超时、限流、服务端、认证、上下文溢出、截断、解析和内容失败；非瞬时错误不得盲目重试。
- 逐批提交有效译文；后续失败或取消保留已提交事实。Token、缓存、思考、输出与请求次数按实际回执累计，估算与未知值明确区分。
- 暂停/取消后不得再发起付费请求；恢复从已持久化边界继续。
- 新响应协议必须保留条目/页面稳定身份、姓名和格式定位，校对规则与失败分类对齐 LG；旧 Chapter/Segment 协议只作历史参考，不进入新链路。
- 术语与材料处理覆盖用户选定的完整工程范围，结果绑定正确 Project/Chat；不保留“默认取第一个工程”等歧义行为。
- Agent 付费/写入参数必须无损验证类型、整数性和范围；审批绑定实际差异与工程身份，模型文案中的确认要求不能代替执行层授权。
- API Key 经新凭据存储端口读取，旧 `ApiKeyCipher` 仅作隔离的历史参考；禁止把 Key、Authorization Header、签名密钥写进日志、提示词、异常、Debug 数据、源码、测试或提交信息。对外部文件名、压缩包路径、工作区链接与图片标记做边界校验，防目录穿越。
- Debug 默认关闭，只记录开启后的新请求；关闭时仍保留轻量状态、Token、费用、错误分类和失败摘要。Debug 数据可能含小说正文，UI 需提醒谨慎分享。
- 新修改统一通过 `ProjectWriteStore` 及工程修订/对象指纹检查；保留已提交事实与可审计回执。旧 `SegmentRevision` 只作历史参考，不成为新工程模型。

