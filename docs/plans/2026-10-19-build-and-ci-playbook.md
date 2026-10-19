# 构建与 CI 实战手册（2026-10-19 重构实录）

> 本文记录本次重构的工具链问题、CI 历史证据与测试教训。2026-10-20 的配置调整尚待 CI 验证，不代表已测得提速效果。新增代码前先读本文，并以 `AGENTS.md` 的本机验证约束为准。

## 1. 工具链事实（以 `gradle/libs.versions.toml` 为准）

- Kotlin **2.3.10** / KSP **2.3.10**（新版本号 scheme 与 Kotlin 对齐）/ Java 17 字节码 / Gradle 9.3.1 / AGP 9.1.1
- **Koog 1.3.0 要求 Kotlin metadata 2.3**：1.2.0 与 1.3.0 皆然（已从 Maven `.module` 元数据证实）。升级 Koog 前先确认 Kotlin 版本
- ML Kit GenAI：`genai-prompt:1.0.0-beta4`、`genai-summarization:1.0.0-beta1`、`genai-proofreading:1.0.0-beta1`；**API 26+**；**仅前台可推理**（前台服务中也返回 BACKGROUND_USE_BLOCKED）；每 app 配额 + 电池配额；摘要仅 EN/JA/KO
- jsoup 1.23.2：`Element.allElements()` 已更名 `getAllElements()`；`TextNode.wholeText` 是**属性**不是方法（`node.wholeText`，加括号编译不过）
- OkHttp 被 Koog → ktor-client-okhttp 3.3.3 顶到 **5.2.1**（`okhttp-android` AAR）。LlmClient 全部用法已逐符号核对兼容；**切勿 force 回 4.10.0**（ktor 3.x 要求 5.x）

## 2. Kotlin 2.3 编译期地雷（全部实测踩过）

| # | 地雷 | 正确写法 |
|---|---|---|
| 1 | `import kotlinx.coroutines.CoroutineContext` 在 coroutines 1.10.x 是 **HIDDEN 弃用 typealias**，直接编译失败（同文件其他协程 API 却正常，极具迷惑性） | `import kotlin.coroutines.CoroutineContext` |
| 2 | `Continuation.resumeWithException` 是 **kotlin.coroutines 的扩展函数**，不是协程库成员 | `import kotlin.coroutines.resumeWithException` |
| 3 | `suspendCancellableCoroutine` 内 `continuation.resume(value)` 有重载解析歧义（报 "No value passed for parameter 'onCancellation'"） | `continuation.resumeWith(Result.success(value))` / `Result.failure(e)` |
| 4 | 字符串模板：`"${var}"` 被当模板解析；测试中断言含 `${...}` 的文本最易中招 | 字面量写 `\${var}`；写完 grep `'\${[a-zA-Z]'` 自查 |
| 5 | `kotlin.io.createTempDir` 在 Kotlin 2.3 是 **ERROR 级**弃用（不是 warning） | `kotlin.io.path.createTempDirectory(prefix = "…").toFile()` |
| 6 | `Regex.toPattern().groupCount()` 的 Kotlin/Java 互作不确定 | 纯 Kotlin 扫描器数捕获组（见 `core/quality/QualityRuleValidator.countCapturingGroups`） |
| 7 | `match.groups\[name\]`（`MatchGroupCollection.get(String)`）底层调 `Matcher.start(String)`，**API 26 才有**，minSdk 24 下 lint NewApi 直接报错 | 预解析 name→组序号再按序号取（见 `namedGroupIndexes`，两处实现：core/quality 与 core/text） |
| 8 | `List.toList()` 对 0/1 元素返回**运行时不可变**列表（`emptyList()`/`singletonList`），≥2 个才返回可改 ArrayList——可变性随size漂移 | 快照语义用 `toMutableList()` |
| 9 | Java 内联标志在组内泄漏：`(?:(?i)foo\|BAR)` 的 BAR 分支也忽略大小写 | 前缀标志改写为作用域组 `(?i:foo)`（见 `compileAlternation`） |
| 10 | `(?U)` 在 **Android 14+（API 34）ICU 正则**抛 `PatternSyntaxException`，Robolectric（JVM 正则）测不出 | 编译兜底：try `(?U)` catch 后退回无标志（见 `core/text/TextProtectionRules.compileRule`） |
| 11 | ML Kit `getBaseModelName` 这类 **getter 式命名的 suspend 函数保留原方法名**（`checkStatus` 无前缀才可直接调用） | `model.getBaseModelName()` |
| 12 | ML Kit `GenerateContentRequest.Builder` 用 Kotlin **属性赋值** | `apply { temperature = it }`，不是 `setTemperature(it)` |

## 3. Android / AGP 9 地雷

| # | 地雷 | 处置 |
|---|---|---|
| 1 | AGP 9 **禁止 manifest 里 `android:minSdkVersion`**（`processDebugMainManifest` 失败） | 只写 `tools:overrideLibrary` |
| 2 | `kotlin { compilerOptions { … } }` 必须放在 `build.gradle.kts` **顶层**——AGP 9 的 android 扩展没有 `kotlin` 成员（反编译证实） | 移到 `ksp { }` 旁边 |
| 3 | `tools:overrideLibrary` **只骗过 manifest merger，骗不过 D8 dexer**：jar 引用 API 26+ 时仍报 "Increase the minSdkVersion to 26" | 先试 core library desugaring；不行再 exclude |
| 4 | 依赖要求更高 minSdk 的正确顺序：① `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:…")`；② 仍失败则 exclude 并用不上的传递依赖（Koog 的 jackson 四件套就是这样排除的——它只被 Koog 快照/持久化功能使用，本应用持久化走 Room） | 见 `app/build.gradle.kts` |
| 5 | `actions/upload-artifact` 的 name **不能含 `/`**（分支名 `refactor/xxx` 会炸；原工作流只在 main/beta 跑所以没暴露） | `${GITHUB_REF_NAME//\//-}` 消毒 |
| 6 | ML Kit 三个包 + jackson 的 overrideLibrary 清单（含易漏的 `com.google.mlkit.genai.common` 公共依赖，从 AAR manifest 实证） | 见 `AndroidManifest.xml` |

## 4. CI 结构与耗时（2026-10-19 实测数据）

**结构**（`.github/workflows/android.yml`）：三个**并行 job**，各自只编译自己需要的变体——
- `test`：`./gradlew testDebugUnitTest --profile`（不编译 release）
- `lint`：`./gradlew lintDebug --profile`
- `release`：签名校验 + `./gradlew assembleRelease --profile` + apksigner 验证 + 上传 artifact

**提速措施与效果**：
| 措施 | 效果 |
|---|---|
| `testOptions { unitTests.all { maxParallelForks = CPU 核数 } }`（Robolectric 跨 fork 安全） | 测试 20min → 7min |
| job 并行化（时间从求和变取 max） | 总墙钟再降 |

**实测**：改前 **31 min**（单 job 顺序：配置 2min + release 编译 4.5min + 测试 20min）→ 改后 **9 min 42s**（test 7m11s / lint 9m42s / release 9m35s 三者并行）。

### 2026-10-20：开发分支缓存与失败诊断（待 CI 验证）

- [run #93](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39281267954) 的三个 job 均记录 `cache-read-only: true`、Gradle 缓存未命中与退出时不保存；当前分支为 `refactor/translation-agent-2026-10`，`setup-gradle@v4` 默认只允许默认分支写回缓存。工作流显式设置 `cache-read-only: false`，让当前分支的后续运行能够复用依赖和构建缓存。适用范围为当前的仓库分支 push / `workflow_dispatch`；变更触发来源时重新核对缓存策略。
- Robolectric 的 `~/.m2/repository` 独立恢复与保存，测试失败后仍保存已下载内容；首次缓存为空仍需下载，精确命中时不重复保存。
- 保持 `gradle.properties` 中已使用的 Kotlin 编译策略与并行测试配置；本轮不切换编译 daemon。Lint 只关闭 HTML 报告，保留 XML 和原有测试源码检查。
- 三个 Gradle 命令使用 `--profile` 输出任务耗时报告，随测试报告或 Lint XML 上传为保留 7 天的 artifact；步骤失败后仍尝试上传已生成的报告。job 超时分别为 30 / 25 / 40 分钟，只限制任务执行时间。
- #93 的测试 / Lint / Release 主要步骤分别为 5m35s / 9m27s / 8m09s；最后一个 job 在创建后约 9m55s 完成，但 GitHub 记录整次运行 33m19s。差额没有对应的构建步骤日志，原因未决；不能据此推断编译耗时，也不能声称 job 超时设置已解决差额。
- 后续验收：首轮确认缓存写入，下一轮确认恢复命中；读取 profile 报告比较配置、编译、测试与 Lint 的耗时。上述配置与报告上传尚未在 CI 运行，不登记为提速已验证。

**其他事实**：
- 修改 `build.gradle.kts` 或 `libs.versions.toml` 会使下次运行**部分缓存失效并重新下载依赖**（+2~3 min），不要误判为代码问题
- 仓库是 public：可用免费 8 核 larger runner（`ubuntu-latest-8-cores`）再提速约 30-40%（未启用）
- 签名 Secrets 四个（ANDROID_KEYSTORE_BASE64/PASSWORD/ALIAS/KEY_PASSWORD）自 2026-08 起已配置；证书 SHA-256 固定校验 `a59e9fb8…1946`
- 本地无 SDK 时**不要**跑 gradle（设备发烫且用户明令禁止）；CI 是唯一构建验证通道

## 5. 测试教训（首轮 19 个失败的解剖）

**结论先行：数量不是问题（50 文件 / 550 测试，仅 8 个类用 Robolectric），"盲写的测试"才是。**

首轮 CI 的 19 个失败拆解：
- **13 个测试期望错误**：盲写时把"以为的行为"写成断言。典型：大小写不敏感以为只替换第一个；`" +"` 被 `trim()` 成非法正则；字面量替换期望写反；工具消息粒度理解错
- **5 个实现真 bug**（测试立了功）：`toList()` 不可变快照、`(?i)` 组内泄漏、无 OPF 抛错类型不一致、GlossaryIo 排版违反自身 KDoc、`groups[name]` API 26

**纪律（写进 AGENTS.md）**：
1. 新增测试需由 CI 或 PC 环境验证；本机只做静态检查，不运行 Gradle、单文件 Kotlin 编译或 JUnit。历史单文件验证方法不作为本机执行入口，约束以 `AGENTS.md` 为准。
2. **KDoc / 设计文档是测试期望的权威来源**——断言前先读实现的文档化意图
3. 判定失败时先问"是测试错还是实现错"，用证据说话，不要无脑改期望值

## 6. 历史状态与当前验证边界

- 工作分支 `refactor/translation-agent-2026-10`（**未合 main**，遵守 AGENTS.md §4 交付边界），12 个提交
- 早期重构版本曾有三个 job 全绿的记录；该历史结果不覆盖后续提交。最新核查的 #93（`1e8b3dd`）为 Release 和 Lint 通过，测试报告 522 个、1 个失败、1 个跳过。
- #93 剩余失败位于 `AgentProtocolRobustnessTest.cleanFinalTextStripsResidualBlocksAndStrayTags`：移除落单标签并 `trim()` 后的期望应为 `"残留"`；全角调用块移除后保留两侧换行，期望为 `"结论\n\n以上"`。两处期望已在工作区修正，尚未重新运行 CI。
- 剩余 backlog（不阻塞构建）：质量规则/导出选项/术语导入导出/ML Kit 的设置页 UI、限流 scheduler 开启（`LlmRequestSchedulerFactory` 接入 AppViewModel）、T3 全书写校对台、`AgentSessionTest` PART5 两个用例、Robolectric 截图重录（`recordRoborazziDebug`）
- 相关文档：`2026-10-19-master-refactor-plan.md`（主计划与状态）、`lg-parity-gap-analysis.md`（LG 对标）、`2026-10-19-architecture-overview.md`（架构总览）、`2026-10-19-agent-layer-review.md`（Agent 层审查）、`2026-10-19-build-readiness-checklist.md`（构建预案）
