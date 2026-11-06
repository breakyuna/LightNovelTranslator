# 首次构建预案 + 静态预审报告

> 日期：2026-10-19 ｜ 审查方式：**纯静态**（rg / read / javap / AAR 解包），**未执行任何 gradle / 构建 / 测试命令**（遵守禁构建令）
> 仓库：`/data/data/com.termux/files/home/LightNovelTranslator` ｜ 工作区：约 84 项未提交改动
>
> **证据来源说明**：本报告引用的第三方 API 签名全部来自本机 Gradle 缓存里**已下载的真实产物**（`~/.gradle/caches/modules-2/files-2.1/`），用 `javap` / `unzip` 直接核对，不是文档记忆。这覆盖了 Koog 1.3.0（35 个 AAR）、ML Kit GenAI 三个 beta 包 + genai-common + common、OkHttp 4.10.0 / 5.2.1 / okhttp-android 5.2.1、Jackson 2.21.3、AGP 9.1.1、manifest-merger 32.1.1。
>
> **重要免责**：仓库正被多个 agent 并行修改，本报告快照时间约为 14:45。文中所有结论都可能已被后续编辑推翻——标注 🔄 的条目复检优先级最高。

---

## 目录

- [A. 首次构建验证清单（按优先级）](#a-首次构建验证清单按优先级)
- [B. 跨模块静态预审](#b-跨模块静态预审)
- [C. 构建阻塞项 Top 5](#c-构建阻塞项-top-5)

---

# A. 首次构建验证清单（按优先级）

## A0. 前置：先确认"能下载到"再跑

**发现（重要）**：本机 Gradle 缓存显示，本次重构新增的构建插件**从未被解析过**：

| 坐标 | 缓存中的版本 | 需要 |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 只有 `2.2.10` | 需要 `2.3.10` ❌ |
| `com.google.devtools.ksp:symbol-processing-gradle-plugin` | 只有 `2.3.5` | 需要 `2.3.10` ❌ |
| `org.jetbrains.kotlin:kotlin-stdlib` | `2.1.10 / 2.2.10 / 2.2.21 / 2.3.0-RC / 2.3.21` | **没有 `2.3.10`** ❌ |
| `androidx.compose:compose-bom` | `2024.09.00` ✅ | 但 `compose-ui / material3 / foundation` 等**全部缺失** ❌ |
| `com.android.tools.build:gradle` | `9.1.1` ✅ | OK |
| `ai.koog:*` | 1.3.0 全量 ✅ | OK |
| `com.google.mlkit:genai-*` | 全量 ✅ | OK |
| `com.squareup.okhttp3:okhttp-android` | 5.2.1 ✅ | OK |

证据：`ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin` → `2.2.10`；`ls .../com.google.devtools.ksp/symbol-processing-gradle-plugin` → `2.3.5`；`ls .../androidx.compose/` → 只有 `compose-bom`。

结论：**首次 `./gradlew` 必须联网**，且会下载 KGP 2.3.10、KSP 2.3.10、整个 Compose 栈、`kotlin-stdlib:2.3.10`。若设备离线，构建会在配置阶段就失败（不是代码问题）。

```bash
cd /data/data/com.termux/files/home/LightNovelTranslator
./gradlew help --offline 2>&1 | tail -30      # 先确认能否离线配置
./gradlew compileDebugKotlin                  # 第一次真实构建（会下载 + 编译，预计 10-30 分钟）
```

---

## A1. Kotlin 2.3.10 / KSP 2.3.10 升级（最高优先级）

### 1.1 全模块编译

```bash
./gradlew compileDebugKotlin 2>&1 | tee /data/data/com.termux/files/usr/tmp/opencode/build1.log | grep -E "^e:|^w: .*warning|FAILED|BUILD" | head -60
```

**已静态核对通过的部分**：

| 检查项 | 结论 | 证据 |
|---|---|---|
| Kotlin 版本与 Koog 的 stdlib 要求一致 | ✅ | `ai.koog:koog-agents:1.3.0` POM 依赖 `kotlin-stdlib:2.3.10`；catalog `kotlin = "2.3.10"`（`gradle/libs.versions.toml:11`） |
| Compose 编译器插件版本与 Kotlin 一致 | ✅ | `kotlin-compose = { version.ref = "kotlin" }`（`libs.versions.toml:116`） |
| KSP 版本与 Kotlin 一致 | ✅ | `googleDevtoolsKsp = "2.3.10"`（`libs.versions.toml:13`） |
| `compileOptions` 17 ↔ `jvmTarget` 17 一致 | ✅ | `app/build.gradle.kts:56-65` |

**⚠️ 高风险：`android { kotlin { compilerOptions { ... } } }` 位置可能不合法**

`app/build.gradle.kts:61-65`：

```kotlin
android {
  ...
  kotlin {                                    // ← 嵌套在 android {} 内，是本轮重构新增
    compilerOptions {
      jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
  }
  buildFeatures { ... }
}
```

`git show HEAD:app/build.gradle.kts` 确认这段是新增的。核对 AGP 9.1.1 真实产物：

- `com.android.build.gradle.internal.dsl.CommonExtensionImpl` 的全部 Kotlin 符号里**只有** `enableKotlin` / `getEnableKotlin` / `setEnableKotlin`，**没有** `kotlin` 成员；
- AGP 的 built-in Kotlin 支持（`BuiltInKotlinServicesKt.initBuiltInKotlinSupport`，已用 `javap -c` 反编译确认）执行的是 `project.extensions.add("kotlin", kotlinAndroidProjectExtension)`——即 `kotlin` 扩展注册在 **Project** 上，不是 `android` 扩展上。

因此 `android { kotlin { } }` 要么解析不到、要么靠 Gradle Kotlin DSL 生成的 `Project.kotlin` 访问器从外层隐式 receiver 兜住（**无法静态确认**）。

**首次构建操作**：如果报 `Unresolved reference: kotlin` 或 `Type mismatch`，把这段**移到脚本顶层**（无争议的合法写法，且完全等价）：

```kotlin
// app/build.gradle.kts，android { ... } 块之外
kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
  }
}
```

### 1.2 Koog 1.3.0 metadata 兼容性

**已静态核对通过（用缓存里的 35 个 Koog AAR 逐一 javap）**：

| app 代码符号 | Koog 1.3.0 实际签名 | 结论 |
|---|---|---|
| `ai.koog.agents.core.tools.ToolDescriptor` | 存在于 `agents-tools-android-1.3.0-release.aar`（koog-agents 的传递依赖） | ✅ |
| `ai.koog.prompt.executor.model.PromptExecutor` | abstract class；`execute(Prompt, LLModel, List)` / `executeStreaming` / `moderate` 为抽象成员（继承自 `PromptExecutorAPI`），另有 `ResolvedModel` 重载的具体实现 | ✅ 见下方说明 |
| `ai.koog.prompt.message.MessagePart.Tool.Result.{tool, output}` | `getTool()` / `getOutput()` 均存在 | ✅ |
| `ai.koog.prompt.message.MessagePart.Tool.Call.{tool, args}` | `getTool()` / `getArgs()` 均存在 | ✅ |
| `Message.Assistant(content=, metaInfo=, finishReason=)` | 有 `(String, ResponseMetaInfo, String, JsonObject, String)` 构造器 | ✅ |
| `ResponseMetaInfo(timestamp, totalTokensCount, inputTokensCount, outputTokensCount, modelId)` | 完全一致的 5 参构造器 | ✅ |
| `Message.textContent()` | `public java.lang.String textContent()` | ✅ |
| `ai.koog.prompt.llm.LLModel(provider=, id=, capabilities=)` | `LLModel(LLMProvider, String, List)` | ✅ |
| `ai.koog.prompt.llm.LLMProvider(id=, display=)` | `LLMProvider(String, String)`（open class，可子类化） | ✅ |
| `LLMCapability.Temperature` | object 存在 | ✅ |
| `AIAgent(promptExecutor=, llmModel=, strategy=, toolRegistry=, systemPrompt=, temperature=, maxIterations=, id=)` | `AIAgentFactoryKt` 有 11 参 functional 重载，参数名经字节码确认正是 `promptExecutor/llmModel/strategy/toolRegistry/systemPrompt/temperature/maxIterations/responseProcessor/id/clock/installFeatures` | ✅ |
| `ToolRegistry.EMPTY` | private static final，有 `access$getEMPTY$cp` | ✅ |
| `functionalStrategy<String, AgentTurnResult>("lnt-agent-chat") { }` | `AIAgentFunctionalStrategyKt.functionalStrategy(String, Function3)` | ✅ |
| `requestLLM(message)` | `AIAgentFunctionalContextBase.requestLLM(String)` | ✅ |
| `llm.writeSession { requestLLM() }` | `AIAgentLLMWriteSession.requestLLM()` | ✅ |
| `llm.readSession { prompt.messages.size }` | `AIAgentLLMReadSession` 继承自 Common，有 `prompt` | ✅ |
| `appendPrompt { user(...) / assistant(...) }` | `AIAgentFunctionalContextBaseCommon.appendPrompt(Function1, Continuation)` | ✅ |
| `compressHistory(HistoryCompressionStrategy.WholeHistory, preserveMemory = false)` | `compressHistory(HistoryCompressionStrategy, boolean, Continuation)`；`WholeHistory` 是 static final 字段 | ✅ |
| `ai.koog.prompt.dsl.ModerationResult`、`StreamFrame`、`Message.System/User`、`Prompt(messages, id=)`、`RequestMetaInfo(Instant)` | 全部存在于 `prompt-model-android` | ✅ |

**关于 `LntPromptExecutor` override 签名的重点说明**（任务书特别要求核对）：

Koog 1.3.0 的 `PromptExecutor` **同时**暴露两套 `execute`：
- `PromptExecutorAPI.execute(Prompt, LLModel, List)` —— **抽象**；
- `PromptExecutor.execute(Prompt, ResolvedModel, List)` —— **已实现**（委托给前者）。

所以 `LntPromptExecutor.kt:49-53` 用 `LLModel` 写的 override 覆盖的是接口里那个**抽象**重载，合法。同理 `executeStreaming` / `moderate` / `models()`。**✅ 静态通过**。但这是全报告里最"反直觉"的一处，首次构建时请确认没有 `execute overrides nothing` 报错。

### 1.3 Compose 编译器插件 × Compose BOM 2024.09.00

BOM `2024.09.00` → Compose UI/Material3 1.7.x。Kotlin 2.3.10 的 compose 编译器插件对 1.7.x runtime 是向后兼容的。

⚠️ **注意**：`androidx.compose:*` 的 jar/aar **完全不在本地缓存**，无法静态核对具体版本兼容性。若首次构建报 compose 相关错误，第一嫌疑是把 `composeBom` 升到 2025+：

```toml
# gradle/libs.versions.toml:12
composeBom = "2025.xx.xx"
```

### 1.4 Room 2.7.0 在 KSP 2.3.10 下

⚠️ Room 2.7.0 + KSP 2.3.10 的组合**没有静态证据**。Room 2.7.0 发布时 KSP 主线是 2.x，理论上兼容；但 KSP 2.3.10 是很新的版本，Room 的 KSP processor 若用了内部 API 可能崩。

**首次构建操作**：
```bash
./gradlew compileDebugKotlin 2>&1 | grep -iE "ksp|room|schema" | head -40
```
若 KSP 处理器崩溃（`ksp` task 抛异常而不是 `e:` 编译错误），先看堆栈顶是 Room 还是 KSP；Room 侧就降 `roomRuntime/roomKtx/roomCompiler/roomTesting`，KSP 侧就把 `googleDevtoolsKsp` 降到 2.3.x。

---

## A2. Manifest `tools:overrideLibrary`（**已定位一个确定问题**）

### ❌ 确定问题：`com.google.mlkit.genai.common` 缺失

`app/src/main/AndroidManifest.xml:8`：

```xml
<uses-sdk tools:overrideLibrary="com.google.mlkit.genai, com.google.mlkit.genai.prompt,
  com.google.mlkit.genai.summarization, com.google.mlkit.genai.proofreading,
  com.fasterxml.jackson.core, com.fasterxml.jackson.module,
  com.fasterxml.jackson.databind, com.fasterxml.jackson.annotation" />
```

从缓存里的 AAR 直接读 manifest（`unzip -p *.aar AndroidManifest.xml`）：

| AAR | manifest `package=` | `minSdkVersion` | 是否在 overrideLibrary 里 |
|---|---|---|---|
| `genai-prompt-1.0.0-beta4.aar` | `com.google.mlkit.genai.prompt` | **26** | ✅ |
| `genai-summarization-1.0.0-beta1.aar` | `com.google.mlkit.genai.summarization` | **26** | ✅ |
| `genai-proofreading-1.0.0-beta1.aar` | `com.google.mlkit.genai.proofreading` | **26** | ✅ |
| **`genai-common-1.0.0-beta4.aar`** | **`com.google.mlkit.genai.common`** | **26** | ❌ **缺失** |
| `common-18.11.0.aar` | `com.google.mlkit.common` | 21 | 不需要 |

`genai-common` 是三个 genai 包的**公共传递依赖**（见各自 POM 里的 `<artifactId>genai-common</artifactId>`），一定会进 manifest merger 的输入集。它的 minSdk 26 > app minSdk 24，**必然触发 merger 失败**。

manifest-merger 32.1.1 的真实错误文案（从 jar 常量池提取）：

```
uses-sdk:minSdkVersion 24 cannot be smaller than version 26 declared in library
com.google.mlkit.genai.common as the library might be using APIs not available in 24
Suggestion: use a compatible library with a minSdk of at most 24,
or use tools:overrideLibrary="com.google.mlkit.genai.common" to force usage (may lead to runtime failures)
```

**修复（首次构建时如果确实报错，按此改）**：

```xml
<uses-sdk tools:overrideLibrary="com.google.mlkit.genai.prompt,
  com.google.mlkit.genai.summarization, com.google.mlkit.genai.proofreading,
  com.google.mlkit.genai.common" />
```

**另外两点**：

1. `com.google.mlkit.genai` 这个条目在依赖图里**不存在对应 artifact**（缓存里没有 `com.google.mlkit:genai`）。已反编译 `com.android.manifmerger.OverrideLibrarySelector` 确认：`appliesTo()` 只做前缀匹配，**没有**"包名必须存在"的校验，所以多余条目是静默无害的。可以顺手删掉。
2. `com.fasterxml.jackson.*` 四个条目对应的是 **jar** 依赖（`com.fasterxml.jackson.core:jackson-databind:2.21.3` 等，由 `ai.koog:serialization-jackson` 传递引入），**jar 不参与 manifest merger**，所以这四个是 no-op。无害，可删。
   ⚠️ 顺带一个真实风险：jackson 2.21.3 被拉进一个 `minSdk 24` 的 APK；desugaring 应能处理，但首次构建后请跑 `./gradlew lintDebug` 看有没有 desugar 相关 Error。

### 若报错如何读错误并补包名（操作步骤）

1. `./gradlew mergeDebugMainManifest 2>&1 | tee .../merge.log`
2. 找 `uses-sdk:minSdkVersion ... cannot be smaller than version ... declared in library <包名>`；
3. `<包名>` 就是**真实包名**，直接把它（或它的父前缀 + `.*` 通配）补进 `tools:overrideLibrary`；
4. **通配符可用**：`OverrideLibrarySelector` 的构造器逻辑（已反编译）支持 `"<prefix>.*"` 后缀，`packageNamePrefix` 走 `startsWith`。所以 `com.google.mlkit.genai.*` 一条就能覆盖现在和将来的所有 genai 子包——**推荐用这个写法**，一劳永逸：
   ```xml
   <uses-sdk tools:overrideLibrary="com.google.mlkit.genai.*" />
   ```
5. 若暂时不想改代码，可用 `./gradlew mergeDebugMainManifest -Pandroid.minSdkLibraryCheck=false`（manifest-merger 里有 `--disable-minSdkLibrary-check` 开关）确认是不是这一类问题——**这只是诊断手段，不要作为长期方案**。

---

## A3. Room schema / 迁移

### 3.1 `5.json` / `6.json` 的 identityHash

- `app/schemas/.../5.json`：`identityHash = "d1586d5d1bd663401d9f4e8abef12ea2"`（**真实 hash**，mtime 12:54）
- `app/schemas/.../6.json`：`identityHash = "00000000000000000000000000000000"`（**手写占位符**，mtime 14:02）

已用脚本逐字段 diff 两份 JSON：**除 `quality_rule_sets` 新表外完全一致**（其余 23 张表的 `createSql` / `fields` / `foreignKeys` / `indices` 全等）。

**结论**：`6.json` 是忠实的 `5.json + quality_rule_sets`，KSP 首次构建会用真实 hash 覆盖它。**这不是阻塞项**。

**首次构建操作**：
```bash
./gradlew compileDebugKotlin          # KSP 会重写 6.json
git diff --stat app/schemas/          # 预期只有 6.json 的 identityHash 变化
```
若 diff 显示 `createSql` 也变了，说明手写 schema 与实体不同步——**以 KSP 输出为准**，并回头核对 `MIGRATION_5_6`。

### 3.2 `MIGRATION_5_6` 与 schema 一致性 —— ✅ 静态通过

`AppDatabase.kt:246-264` 的 `CREATE TABLE quality_rule_sets` SQL 与 `6.json` 的 `createSql` **逐字一致**（列名、类型、NOT NULL、复合主键 `PRIMARY KEY(translationProjectId, type)`、外键 `ON DELETE CASCADE`），也与 `QualityRuleSetEntity`（`data/model/QualityRuleEntities.kt:19-58`）的字段一一对应。

`MIGRATION_4_5`（`AppDatabase.kt:161-234`）同样与 `5.json` 的 `translation_projects_v2` / `translation_prompt_profiles` / `agent_sessions` / `agent_messages` 完全一致。

### 3.3 MigrationTest 资产

**仓库当前没有任何 MigrationTest**（`rg "MigrationTestHelper" app/src/test` → 0 命中）。所以"资产是否需要更新"这个风险**不存在**。但 `app/build.gradle.kts:71` 已经配好 `sourceSets.test.assets.srcDir("$projectDir/schemas")`，补测试时资产就在。

**建议**（不阻塞）：新增 `RoomMigrationTest`，用 `MigrationTestHelper` 从 `1.json` 逐版迁移到 `6.json`。这是验证 5→6 唯一可靠的方式。

```bash
./gradlew testDebugUnitTest --tests "*MigrationTest*"
```

---

## A4. ML Kit beta API（三个库）—— ✅ 静态核对通过

已解包三个 AAR 的 `classes.jar` 并用 `javap` 逐符号核对：

| app 调用 | 实际签名 | 证据 |
|---|---|---|
| `Summarization.getClient(SummarizerOptions)` | `public static Summarizer getClient(SummarizerOptions)` | `genai-summarization` |
| `SummarizerOptions.builder(context).setInputType(ARTICLE).setOutputType(ONE_BULLET).setLanguage(...).build()` | `builder(Context)` / `setInputType(int)` / `setOutputType(int)` / `setLanguage(int)` / `build()`；`InputType.ARTICLE`、`OutputType.ONE_BULLET`、`Language.ENGLISH/JAPANESE/KOREAN` 常量全部存在 | 同上 |
| `SummarizationRequest.builder(text).build()` | `builder(String)` / `build()` | 同上 |
| `client.checkFeatureStatus()` / `downloadFeature(cb)` / `runInference(req)` / `runInference(req, cb)` / `close()` | `ListenableFuture<Integer> checkFeatureStatus()`、`ListenableFuture<Void> downloadFeature(DownloadCallback)`、`ListenableFuture<SummarizationResult> runInference(...)`、`void close()` | 同上 |
| `result.summary` | `public abstract String getSummary()` | 同上 |
| `Proofreading.getClient(ProofreaderOptions)` | `public static Proofreader getClient(ProofreaderOptions)` | `genai-proofreading` |
| `ProofreaderOptions.builder(ctx).setInputType(KEYBOARD).setLanguage(ENGLISH)` | `setInputType(int)` / `setLanguage(int)`；`InputType.KEYBOARD/VOICE`、`Language.ENGLISH/JAPANESE/GERMAN/FRENCH/ITALIAN/SPANISH/KOREAN` 全部存在 | 同上 |
| `ProofreadingRequest.builder(text).build()` | `builder(String)` / `build()` | 同上 |
| `result.results` | `public abstract List<ProofreadingSuggestion> getResults()`（真实返回类型是实现 `List` 的内部类，app 已用 `.toList()` 转换，见 `OnDeviceProofreader.kt:158`） | 同上 |
| `suggestion.text` | `public abstract String getText()` | 同上 |
| `Generation.getClient()` | Kotlin `object Generation` 的 `getClient()` | `genai-prompt` |
| `model.checkStatus()` / `download()` / `warmup()` / `countTokens(req)` / `generateContentStream(req)` / `close()` | 全部为 `GenerativeModel` 接口成员；`checkStatus(): Int` 是 suspend | 同上 |
| `GenerateContentRequest.Builder(TextPart).setTemperature(Float)...build()` | `Builder(TextPart)` 构造器 + `setTemperature(Float)/setSeed(Integer)/setTopK(Integer)/setCandidateCount(Integer)/setMaxOutputTokens(Integer)` | 同上 |
| `countTokensResponse.totalTokens` | `public final int getTotalTokens()` | 同上 |
| `candidate.text` | `public final String getText()` | 同上 |
| `StreamingCallback.onNewText(String)` | `public abstract void onNewText(String)` | `genai-common` |
| `DownloadCallback.onDownloadStarted/Progress/Completed/Failed(GenAiException)` | 全部为 default 方法，签名一致 | `genai-common` |
| `FeatureStatus.{AVAILABLE,DOWNLOADABLE,DOWNLOADING,UNAVAILABLE}` | 4 个 int 常量全部存在 | `genai-common` |
| `GenAiException.ErrorCode.{BUSY,PER_APP_BATTERY_USE_QUOTA_EXCEEDED,BACKGROUND_USE_BLOCKED,NOT_AVAILABLE,NOT_SUPPORTED,AICORE_INCOMPATIBLE,NEEDS_SYSTEM_UPDATE}` | 7 个常量全部存在（`OnDeviceErrors.kt:52-61` 引用的每一个都对得上） | `genai-common` |
| `GenAiException.errorCode` | `public int getErrorCode()` | `genai-common` |

**⚠️ 两个非编译风险（真机才能验）**：

1. `OnDeviceGenAiStatus.kt:107` 与 `:115`：
   ```kotlin
   val promptStatus = runCatching { promptClient.checkStatus() }.getOrElse { FeatureStatus.UNAVAILABLE }
   val baseModelName = runCatching { promptClient.baseModelName }.getOrNull()
   ```
   `runCatching` 是 `inline` 且 lambda 非 `crossinline`，Kotlin 允许在 suspend 函数内的 inline lambda 里调用 suspend 函数，**静态判断可编译**。但 `runCatching` 会吞掉 `CancellationException`——`probeOnDeviceGenAi` 被取消时会静默返回 UNAVAILABLE 而不是向上抛。**行为瑕疵，不是编译错误**，建议首次构建后改成显式 `try/catch (CancellationException) { throw }`。
2. `SummarizerOptions.InputType.ARTICLE` 要求输入 > 400 字符（`OnDeviceTokenBudget.kt:12-13` 已注释说明）；短文会走 `REQUEST_TOO_SMALL` → `FAILED` → 回退云端。这是设计内的，但要确认 `OnDeviceSummarizer.summarize` 对短章节的行为符合预期。

---

## A5. Koog 传递依赖顶高 OkHttp —— ❗ 已确认发生，影响全 app

**这是本次升级最被低估的一处。**

依赖链（已从 `.module` 文件确认）：

```
ai.koog:koog-agents:1.3.0
  └─ ai.koog:koog-agents-android:1.3.0   (Android 变体)
       └─ io.ktor:ktor-client-okhttp:3.3.3
            └─ io.ktor:ktor-client-okhttp-jvm:3.3.3
                 └─ com.squareup.okhttp3:okhttp:5.2.1
                      └─ (android 变体 available-at) com.squareup.okhttp3:okhttp-android:5.2.1
                           └─ androidx.startup:startup-runtime + com.squareup.okio:okio-jvm:3.16.1
```

app 自己声明的是 `libs.okhttp = 4.10.0`（`libs.versions.toml:34`）。Gradle 取最高版本 → **整个 app 的 OkHttp 从 4.10.0 跳到 5.2.1**。且 `okhttp:5.2.1` 的 `releaseRuntimeElements-published` 变体 `available-at` 指向 `okhttp-android:5.2.1`，所以 APK 里实际打进去的是 **`okhttp-android-5.2.1` AAR**（package = `okhttp.okhttp3`，minSdk 21，带 `androidx.startup.InitializationProvider`）。

**已核对 API 兼容性（对 `core/llm/LlmClient.kt` 的全部用法）**：

| app 用法（文件:行） | OkHttp 5.2.1 实际签名 | 结论 |
|---|---|---|
| `OkHttpClient.Builder().connectTimeout(60, SECONDS).readTimeout(180, SECONDS).writeTimeout(60, SECONDS).build()` (`LlmClient.kt:56-60`) | `Builder.connectTimeout/readTimeout/writeTimeout` 均存在 | ✅ |
| `"application/json; charset=utf-8".toMediaType()` (`:61`) | `MediaType.Companion.toMediaType(String)` 扩展存在（已在 `MediaType$Companion.class` 常量池看到 `okhttp3.MediaType.Companion.toMediaType`） | ✅ |
| `body.toString().toRequestBody(jsonMediaType)` (`:386,400,508,516,596,606`) | `RequestBody.Companion.toRequestBody(String, MediaType)` 扩展存在 | ✅ |
| `Request.Builder().url(endpoint).get().header(k,v)` (`:95-97,125-130`) | `Builder.url(String)/get()/header(String,String)` 均存在 | ✅ |
| `client.newCall(request)` / `call.cancel()` / `call.enqueue(Callback)` (`:641-643`) | `OkHttpClient.newCall(Request)`；`Callback.onFailure(Call, IOException)` / `onResponse(Call, Response)` 签名一致 | ✅ |
| `response.use { ... it.code ... it.header(...) }` (`:649-657`) | `Response implements java.io.Closeable` | ✅ |
| `response.body ?: return ""` (`:669`) | `Response.body` 在 5.2.1 是 **`@NotNull`**（已用 `javap -v` 看到 `RuntimeInvisibleAnnotations: org.jetbrains.annotations.NotNull`） | ⚠️ Elvis 恒真 → **warning**，不是 error |
| `body.byteStream()` (`:673`) | `ResponseBody.byteStream()` 存在 | ✅ |
| `java.io.IOException` / `Call` / `Callback` / `Response` import | 全部仍在 `okhttp3` 包下 | ✅ |

**结论：编译层面不会挂。** 但有两个真实风险需要首次构建后处理：

1. **运行时行为变化**：OkHttp 5 是 Kotlin 重写版，连接池/TLS/HTTP2 默认值有调整；`okhttp-android` 还带 `androidx.startup` 的 `PlatformInitializer`（manifest merger 会把它合进来，`tools:node="merge"`）。首次构建后跑一次真实翻译请求确认没有崩溃。
2. `okhttp-android` 5.2.1 AAR 的 manifest 声明 `<uses-permission android:name="android.permission.INTERNET" />` —— app 已有该权限，无冲突。

**首次构建操作**：
```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -E "okhttp|okio|ktor"
```
预期看到 `okhttp-android:5.2.1`（不是 4.10.0）。确认后二选一：
- **接受升级**（推荐，改动最小）：跑一次真实翻译回归；
- **强制锁 4.10.0**（在 `app/build.gradle.kts` 加）：
  ```kotlin
  configurations.all {
      resolutionStrategy.force("com.squareup.okhttp3:okhttp:4.10.0")
  }
  ```
  但这会让 ktor-client-okhttp 3.3.3 与 okhttp 4.x 混用——ktor 3.x 明确要求 okhttp 5.x，**大概率运行时崩**。所以这条只应作为"确认根因"的实验，不是修复。

---

## A6. 测试：各新测试类的首次执行命令

单模块单 source set，全部走 `testDebugUnitTest`。按目录分批跑，避免一次全量失败时看不出根因：

```bash
cd /data/data/com.termux/files/home/LightNovelTranslator

# 第 1 批：纯 JVM、无 Robolectric、无 Android 依赖（最快，先跑）
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.text.*"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.quality.*"   # 当前无测试文件
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.exporter.*"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.llm.LlmRequestSchedulerTest"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.llm.RequestRatePoolTest"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.llm.RetryingLlmGatewaySchedulerTest"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.llm.RetryPolicyTest"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.agent.koog.AgentTurnTruncateTest"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.parser.*"

# 第 2 批：需要 Robolectric / org.json（Android SDK 桩）
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.agent.*"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.ondevice.*"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.glossary.*"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.logger.*"
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.ExampleRobolectricTest"

# 第 3 批：Koog 桥接层（会真实构造 Koog Prompt / Message）
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.core.agent.koog.*"

# 第 4 批：截图
./gradlew recordRoborazziDebug
./gradlew testDebugUnitTest --tests "com.breakyuna.noveltranslator.GreetingScreenshotTest"

# 全量
./gradlew testDebugUnitTest
```

**新增测试文件清单（33 个类）**：`core/text` ×10、`core/parser` ×8（`EpubHtmlExtractorTest` / `EpubParserFidelityTest` / `EpubTocParserTest` / `ImportTextNormalizerTest` / `TxtParserNormalizationTest` / `ChapterStructureDetectorTest` / `EpubParserTest` / `TxtParserTest`）、`core/agent/koog` ×6、`core/agent` ×3、`core/ondevice` ×5、`core/glossary` ×1、`core/exporter` ×1、`core/llm` ×3。

**已静态核对的测试侧符号**：`core/quality` 目前**没有任何测试文件**（任务书假设它有，实际没有）；`core/text` 全部测试只用 main 侧公开 API；`OnDeviceTokenBudgetTest` 用到 main 侧 `internal fun splitBySentence`——**同一模块的 test source set 可以访问 main 的 `internal`**（KGP 会设 friend-paths），✅。

---

## A7. Roborazzi 截图重录

`app/src/test/java/.../GreetingScreenshotTest.kt:25-27` 渲染的是 `NovelTranslatorTheme { Text("Novel Translator") }`。主题已完全重设计（`ui/theme/Color.kt` / `Type.kt` / `Shape.kt` / `Spacing.kt` / 新增 `DesignTokens.kt` 全部被改）。

**确定要重录**：

```bash
./gradlew recordRoborazziDebug
./gradlew testDebugUnitTest --tests "*ScreenshotTest*"   # 转验证
```

`app/src/test/screenshots/greeting.png` 是旧主题的产物，`verifyRoborazziDebug` 必然失配。

---

## A8. Anthropic `cache_control` 块数组请求体（需真实 API 实测）

代码位置：`core/llm/LlmClient.kt:446-503`。逻辑：

- `promptCacheBreakpoints = request.promptCacheHint != null && ...`（`:220`）
- 为 `true` 时，`system` 从纯字符串改为**内容块数组**：`[{"type":"text","text":...,"cache_control":{"type":"ephemeral"}}]`（`:473-480`）
- 同时最后一个 user 块也加 `cache_control`（`:487-498`）
- `thinking` 降级逻辑：`model` 含 `claude-3-7` / `claude-4` 且 `reasoningEffort ∈ {none, disabled}` 时发 `{"type":"disabled"}`；HTTP 400 时剥掉 `thinking` 重试一次（`:461-466` + `:503-517`）
- 能力声明：`AnthropicPromptCacheCapability`（`core/translation/ProviderPromptCache.kt:38-67`），仅 `ProviderType.ANTHROPIC_CLAUDE` 命中

**app 内 Provider 连接测试的验证步骤**：

1. 设置页 → 新增 Provider，`providerType = ANTHROPIC_CLAUDE`，`baseUrl` 填到 `/v1` 或不填（代码两种都处理），API Key 填真实 key，模型选一个 `claude-4-*`（否则走不到 cache breakpoint 相关分支，也不触发 thinking 降级）。
2. 点"测试连接"（`AppViewModel.testProvider` → `testProviderConnection`，`AppViewModel.kt:2234-2236`）。这会发一次最小请求，**不带** `promptCacheHint`，所以**不会**触发块数组路径——它只验证 key / 网络 / 模型名。
3. **真正的 cache_control 验证**需要建一个 `promptCacheEnabled = true` 的工程并翻译一章：
   - 在书架导入一本书 → 工作台把"提示词缓存"开关打开 → 保存 → 启动翻译；
   - 翻译完 1 章后，进任务中心 → 该 run 的 RequestLog 明细（`AppViewModel.getRequestLogDetail`）；
   - 看 `LlmResult.cachedTokens` 是否 > 0（`LlmClient.kt:41` 的字段，由 usage 里的 `cache_read_input_tokens` 填充——**需在代码里确认 Anthropic 分支确实解析了该字段**，见下方待验证项）。
4. 若请求失败：把工程 `promptCacheEnabled` 关掉（`AgentToolHost.updateTranslationConfig` 或工作台开关都能关），即可回到纯字符串 `system` 的旧路径。

**⚠️ 待验证（静态看不出）**：`cache_control` 块数组是 Anthropic 的预览/正式特性取决于模型与 API 版本；`anthropic-version: 2023-06-01` 这个头是否支持块数组 `system` 需要实测。另外 `cachedTokens` 的填充逻辑在 `LlmClient.kt` 的 Anthropic 分支里要单独确认（本报告未逐行核到 `cache_read_input_tokens` 的解析）。

---

# B. 跨模块静态预审

## B1. UI → AppViewModel —— ✅ 静态通过

`ui/**` 中通过 `viewModel.<member>` 引用的成员共 **79 个**，全部在 `AppViewModel.kt`（2264 行）中存在：

```
agentChatController  agentQuestions  allPlatformBooks  allPlatformEditions  allProviders
answerAgentQuestion  applyAgentBookChapterSplit  batchImportProgress  bookImagesDir  bookPlatformRepo
cancelAgentBookChapterSplit  cancelBookTranslation  cancelGlossaryScan  clearMessage  clearSystemLogs
configureEditionTranslation  confirmLexiconCandidate  confirmLexiconCandidatesBatch  confirmLexiconEntry
createTranslationEdition  currentLanguage  debugModeEnabled  deleteBookPermanently  deleteBooksPermanently
deleteEdition  deleteLexiconEntry  deleteProvider  discardAgentBookChapterSplit  dismissAgentQuestion
getRequestLogDetail  hiddenBooks  ignoreLexiconCandidate  importBooksFromUris  moveBook
observeAgentSessions  observeLexicon  observeLexiconCandidates  observePlatformRequestLogs
observePlatformTaskBatches  observePromptProfile  observeRunsByBook  observeRunsByProject
observeStoryMemory  pauseBookTranslation  platformTaskRuns  platformTranslationProjects
previewAgentBookChapterSplit  reSplitBookChapters  readingHistory  removeBookFromShelf
removeBooksFromShelf  renameBook  restoreBookToShelf  resumeBookTranslation  retranslateChapter
runBookTranslation  saveManualRevision  savePromptProfile  saveProvider  saveReaderProgress
scanGlossaryForBook  selectReadingEdition  setBookCover  setDebugModeEnabled  setDefaultProvider
setLanguage  setThemeMode  shelfBooks  showMessage  skipLexiconCandidate  systemLogs  testProvider
themeMode  updateShelfOrderList  updateTranslationProjectConfig  upsertLexiconEntry  userMessage
```

**签名抽查**（都匹配）：

| 调用点 | 声明 | 结论 |
|---|---|---|
| `EngineConfigurationSection.kt:252-267` | `AppViewModel.updateTranslationProjectConfig(projectId, providerId, modelName, mode, maxBatchChapters, rangeStart, rangeEnd, styleGuide, seamlessAheadChapters, highQualityReview, promptProfile, parallelChapters, continuityMode, metadataEnabled, promptCacheEnabled, onSuccess)`（`AppViewModel.kt:1325-1342`） | ✅ |
| `BookWorkbenchDetailScreen.kt:2143-2161` | `configureEditionTranslation(bookId, sourceEditionId, targetEditionId, providerId, modelName, mode, maxBatchChapters, parallelChapters, continuityMode, metadataEnabled, promptCacheEnabled, rangeStart, rangeEnd, seamlessAheadChapters, styleGuide, highQualityReview, promptProfile, startImmediately)`（`AppViewModel.kt:787-806`） | ✅ |
| `AgentChatHost.kt:80,82,103,127,132` | `agentChatController` / `observeAgentSessions()` / `agentQuestions` / `answerAgentQuestion(id, answer)` / `dismissAgentQuestion(id)`（`AppViewModel.kt:193,200,227,233,238`） | ✅ |
| `AgentChatHost.kt:105` | `AgentChatController(gateway, toolHost, store, configProvider)`（`AgentChatController.kt:28-35`）↔ `AppViewModel.kt:206-213` 传的 `llmClient / agentToolHost / agentSessionStore / ::resolveAgentSessionConfig` | ✅ |

**Agent 相关**：`AgentToolHost` 的 9 个工具方法 + `toolSpecs()` + `executeTool()` 全部由 `AppViewModel.AgentToolHostImpl`（`AppViewModel.kt:246-476`）实现，override 签名与接口（`core/agent/AgentToolHost.kt:55-201`）逐一对齐。

**4 个引擎开关**：`EngineConfigurationSection.kt:49-63` 的 `EngineConfigDraft` 读 `project.parallelChapters / continuityMode / metadataEnabled / promptCacheEnabled`，四个字段在 `TranslationProjectV2Entity`（`data/model/ReaderPlatformEntities.kt:186-195`）都存在；`continuityMode` 默认 `"SOURCE_TAIL"` 与 `TranslationContinuity.SOURCE_TAIL` 常量一致。

**导出选项**：`ExportOptions` / `ExportContent` / `ExportPrecheckReport` 全部只在 `core/exporter` 内部 + 测试中使用，UI 未接入（`EditionExporter.kt:22,41,93` 已带默认参数）。无跨模块断链。

**ask_user**：`AgentToolHost.askUser(prompt, options)` → `AppViewModel.kt:424-437` → `_agentQuestions.emit(AgentUserQuestion(...))` → `AgentChatHost.kt:102-110` 收集 → `AgentChatScreen.kt:81` 的 `question: AgentChatQuestion?`。类型在 `AgentChatHost.kt:104-108` 手工映射（`AgentUserQuestion` → `AgentChatQuestion`），✅。

## B2. `core/quality` → `core/text` —— ✅ 符号一致，但有一个 ❌ 编译错误

**符号核对（全部匹配）**：

| `core/quality` 引用 | `core/text` 实际声明 |
|---|---|
| `TextPreserveEntry(pattern, info)` | `core/text/TextProtectionRules.kt:10-13` data class |
| `TextReplacementEntry(src, dst, regex, caseSensitive)` | `core/text/TranslationSourcePreparer.kt:225-230` data class |
| `TextReplacements(entries)` / `.apply(text)` | `core/text/TranslationSourcePreparer.kt:233,299` |
| `TextProtectionPresets.base()` / `.resourceTokenPattern()` | `core/text/TextProtectionPresets.kt:15,39` |
| `LiteralMatcher` / `LiteralPattern(key,text,caseSensitive)` / `TextRange(start,end)` / `compileLiteralPatterns(...)` / `.match(text)` / `.ranges` | `core/text/LiteralMatcher.kt:8-17,87,273` |
| `TextProtectionRule(entries)` / `.collect/replace/analyze` | `core/text/TextProtectionRules.kt:51-100` |
| `QualityRuleType.label()`（Resolver 用） | `core/quality/QualityRuleValidator.kt:247` internal 扩展，**同包** ✅ |

### ❌ 确定编译错误：`QualityRuleModels.kt` 缺 `Moshi` / `Types` import

`app/src/main/java/com/breakyuna/noveltranslator/core/quality/QualityRuleModels.kt`：

```
1: package com.breakyuna.noveltranslator.core.quality
2:
3: import com.breakyuna.noveltranslator.core.text.TextPreserveEntry
4: import com.breakyuna.noveltranslator.core.text.TextReplacementEntry
5:
...
104:    private val moshi: Moshi = Moshi.Builder()
105:        .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
108:    private val textPreserveListType = Types.newParameterizedType(
113:    private val replacementListType = Types.newParameterizedType(
```

`Moshi` 和 `Types` 都是无限定名，而 `core.quality` 包里没有任何同名声明（已全仓 rg 确认：`Moshi` / `Types` 只在 `SystemLogger.kt`、`BookTranslationEngine.kt`、`SystemLoggerTest.kt` 里以**全限定 import** 形式出现）。

→ **`Unresolved reference: Moshi` / `Unresolved reference: Types`，编译必然失败。**

修复：
```kotlin
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
```

依赖侧没问题：`libs.moshi.kotlin`（`moshi-kotlin:1.15.2`，`libs.versions.toml:35,92`）已在 `app/build.gradle.kts:102` 引入，且 `moshi-kotlin` 产物里含 `com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory`。

> 🔄 复检提示：该文件 mtime 13:50，`QualityRuleEngine.kt` / `QualityRuleValidator.kt` mtime 14:15（更晚），说明 `core/quality` 仍在被活跃编辑。修复前先复检。

### 🔄 WIP：`QualityRuleEngine.QualityReplacements` 是死代码，且 resolver 走的还是旧 `(?U)` 路径

- `QualityRuleEngine.kt:62-130` 的 `QualityReplacements` **全仓无调用点**（rg `QualityReplacements` 只有类声明 + 3 处 KDoc 引用）。
- `QualityRuleResolver.kt:117` 实际调 `QualityRuleDefaults.compileReplacements(...)`（`QualityRuleDefaults.kt:185-190`）→ `TextReplacements(...)` → `core/text/TranslationSourcePreparer.kt:276` 的 `Regex("(?U)${entry.src}", ...)`。
- 而 `QualityRuleEngine.kt:12-31` 的类注释明确指出：`(?U)` 在 **Android 14 (API 34)+ 的 ICU 正则实现下会抛 `PatternSyntaxException`**。

也就是说：新的安全实现写好了但没接上，运行时仍走会崩的路径。**这是运行时 bug，不是编译错误。** 首次构建不会暴露，需要真机（API 34+）跑一次带自定义替换规则的翻译才能发现。

## B3. `core/agent/koog` → `core/agent/AgentToolHost` —— ✅ 静态通过

| koog 侧 | AgentToolHost 侧 | 结论 |
|---|---|---|
| `AgentToolSpec(name, description, parametersSchemaJson)` | `core/agent/AgentToolHost.kt:12-16` | ✅ |
| `AgentToolOutcome.ok / output / success() / failure(code,message)` | `core/agent/AgentToolHost.kt:24-43` | ✅ |
| `toolHost.toolSpecs()` | `AgentToolHost.kt:86`（默认实现返回 `AGENT_TOOL_SPECS`） | ✅ |
| `toolHost.executeTool(name, argumentsJson)` | `AgentToolHost.kt:92-201` | ✅ |
| `AgentToolProtocol.renderToolCatalog(specs)` 消费 `AgentToolSpec` | `core/agent/koog/AgentToolProtocol.kt:3,89-101` | ✅ |
| 9 个工具名常量 `TOOL_*` | `AgentToolHost.kt:204-212` | ✅ |
| `AgentUserQuestion(id, prompt, options)` | `core/agent/AgentToolHost.kt:265-269` | ✅ |

`AgentToolHost.kt:92-201` 的 `executeTool` 里 `requiredLong` 的强转 `(value as? Number)?.toLong() ?: value.toString().trim().toLongOrNull()` 在 `value` 为 `JSONObject`/`Boolean` 时会走到 `toLongOrNull()` 返回 null，行为安全。

**⚠️ 一个协议层隐患（非编译）**：`AgentToolProtocol.kt:23-36` 的标签字面量由字符串拼接而成（`CALL_TAG = "tool_call"` 等），这是**为了不在源码里出现完整闭合标签**（`:18-19` 注释说明）。功能上没问题，但意味着任何对 `CALL_TAG` / `RESULT_TAG` 的重命名必须同步 `TOOL_CALL_OPEN/CLOSE`、`TOOL_RESULT_OPEN/CLOSE` 四处，否则提示词与解析器会静默漂移。

## B4. `core/book` → `core/parser` —— ✅ 静态通过，但 🔄 WIP 风险高

| BookImporter 调用 | parser 侧声明 | 结论 |
|---|---|---|
| `EpubParser.parseEpubFile(preservedSource, files.sharedImagesDir(bookId), cropTableOfContents = ...)`（`BookImporter.kt:86-89`） | `core/parser/EpubParser.kt:83-86` `parseEpubFile(File, File?, Boolean)` | ✅ |
| `epub.title / epub.author / epub.coverFileName / epub.chapters` | `ParsedEpubBook(title, author, chapters, extractedImages, coverFileName)`（`EpubParser.kt:20-26`） | ✅ |
| `TxtParser.detectStructure(sourceFile, regexPattern)`（`BookImporter.kt:151`） | `TxtParser.kt:112` `detectStructure(File, String?)` → `ChapterDetectionResult` | ✅ |
| `detection.detectedPattern / .confidence / .headings / .warnings / .detectedTocRange` | `ChapterStructureDetector` 产物 | ✅ |
| `TxtParser.REGEX_CHINESE` / `inferChapterRegex(file, lang)` / `validateChapterRegex(r)` / `openDetectedReader(f)` / `chapterSequence(reader, regexPattern, fallbackChunkWords, cropTableOfContents)` / `countWords(s)` / `splitIntoChapters(...)` / `cropTableOfContents(text)` | `TxtParser.kt:195-199,137,202,98,305,530,231,220` | ✅ |
| `ParsedChapter(index, title, content, wordCount)` | `TxtParser.kt:12-17` | ✅ |

**🔄 WIP 风险（K 正在改 parser）**：`core/parser` 下 5 个新文件（`EpubHtmlExtractor.kt` / `EpubPaths.kt` / `EpubTocParser.kt` / `ImportTextNormalizer.kt` / 以及被修改的 `EpubParser.kt` / `TxtParser.kt`）全部是未提交状态，且新增了 5 个 parser 测试类。`EpubParser.kt:210` 已改走 `EpubHtmlExtractor.extract(html, htmlDir, imageNamesByPath)` 的块级提取（旧实现是整文档正则），`:424-441` 改走 `EpubTocParser.parseNav/parseNcx`。**导入契约 `ParsedChapter` / `ParsedEpubBook` 没变**，所以 BookImporter 不用改；但块级提取的行为变化（标题切分锚点、ruby 丢弃、`[IMG:]` 标记）需要 `EpubParserFidelityTest`（590 行）来兜。首次构建时必须连 parser 测试一起跑。

## B5. 重复类名 / 包名冲突 —— ✅ 静态通过

对 main 源码树全部 137 个 `.kt`、403 个顶层声明做了同名扫描（按简单名聚合）：

- 完全同名且**不同包**的顶层声明：**0 个**
- 同名但都是**私有嵌套类**（无冲突）：
  - `LiteralRule` / `RegexRule` / `Rule`：`core/text/TranslationSourcePreparer.kt:235-268`（`TextReplacements` 私有嵌套）与 `core/quality/QualityRuleEngine.kt:64-97`（`QualityReplacements` 私有嵌套）
  - `Rule`：另有一处 `core/text/TranslationSourcePreparer.kt:176`（`RubyCleaner` 私有嵌套 `data class Rule`）
  - `Success` / `Unsupported`：`OnDeviceSummarizeResult` 与 `OnDeviceProofreadResult` 各自 sealed interface 内的嵌套 data class
- **`AgentToolSpec` / `AgentToolOutcome` 只在 `core/agent/AgentToolHost.kt` 声明一次**，✅（任务书提到的历史重复未复现）

`core/gldevice` 包**不存在**（任务书也说它不存在），无需处理。

## B6. 资源与资产引用 —— ✅ 文件存在，⚠️ 一处未接线

| 代码引用路径 | 实际文件 | 状态 |
|---|---|---|
| `AgentPrompts.SYSTEM_PROMPT_ASSET = "agent/system-prompt.md"`（`AgentPrompts.kt:13`） | `app/src/main/assets/agent/system-prompt.md`（2513 B，mtime 13:09）✅ | ✅ 存在 |
| `OnDevicePrompts.TERM_EXTRACTION_ZH = "ondevice/prompts/term_extraction_zh.txt"`（`OnDevicePromptAssets.kt:12`） | `app/src/main/assets/ondevice/prompts/term_extraction_zh.txt`（729 B）✅ | ✅ 存在 |
| `TERM_EXTRACTION_EN` / `TITLE_TRANSLATION_ZH` / `TITLE_TRANSLATION_EN` | 三个文件都在 ✅ | ✅ |
| `assets/glossary/presets/*.json` | `01_示例.json`（1298 B）+ `02_常用菜单文本.json`（3395 B）✅ | ✅ 存在，且字段（`sourceTerm/targetTerm/kind/category/aliases/notes/caseSensitive/exactMatch/priority`）与 `GlossaryTransferEntry` 完全对齐；`kind:"PROPER_NOUN"` / `category:"CHARACTER"` 在 `LexiconKind` / `TermCategory` 枚举里都存在 |

**⚠️ `AssetsAgentPromptLoader` 从未被实例化**：

- 类定义：`core/agent/koog/AssetsAgentPromptLoader.kt:13`
- 全仓 rg `AssetsAgentPromptLoader` → **只有类自身 + KDoc 提及**，无 `AssetsAgentPromptLoader(context)` 调用点
- `AppViewModel.kt:206-213` 构造 `AgentChatController` 时 `promptAssetLoader` 用默认值 `null`（`AgentChatController.kt:33`）

结果：`assets/agent/system-prompt.md` **运行时不会被读取**，`AgentPrompts.resolveBasePrompt(null, null)` 直接落到 `DEFAULT_BASE_PROMPT`（`AgentPrompts.kt:19-25`）。功能上可用（兜底文案完整），但那份精心写的 2513 字节资产是死代码。首次构建后建议在 `TranslatorApplication` 或 `AppViewModel` 里接线。

`GlossaryIoTest.kt:386` 的 `presetDir()` 用相对路径 `src/main/assets/glossary/presets` / `app/src/main/assets/glossary/presets` 向上找 4 层。Gradle 的 `test` task 工作目录默认是模块目录 `app/`，所以第一个候选就命中。✅

## B7. 枚举 / 常量一致性 —— ✅ 静态通过

**`continuityMode`（SOURCE_TAIL / TRANSLATION_TAIL）三侧一致**：

| 层 | 位置 | 值 |
|---|---|---|
| core | `core/translation/TranslationContinuity.kt:12-13` | `const val SOURCE_TAIL = "SOURCE_TAIL"` / `TRANSLATION_TAIL = "TRANSLATION_TAIL"` |
| data | `data/model/ReaderPlatformEntities.kt:191` | `val continuityMode: String = "SOURCE_TAIL"` |
| data | `data/db/AppDatabase.kt:167`（MIGRATION_4_5） | `ADD COLUMN continuityMode TEXT NOT NULL DEFAULT 'SOURCE_TAIL'` |
| data | `data/repository/BookPlatformRepository.kt:353,400,431,685` | 默认参数 + `normalizeContinuityMode` 归一 |
| ui | `ui/screens/workspace/EngineConfigurationSection.kt:51,58,92,113,186-190` | 全部引用 `TranslationContinuity.*` 常量 |
| ui | `ui/viewmodel/AppViewModel.kt:452-456`（agent 工具） | 非法值归一到 `SOURCE_TAIL` |
| schema | `app/schemas/.../5.json` | `continuityMode TEXT NOT NULL` |

`AppViewModel.kt:452-456` 的归一逻辑是 `if (mode == TRANSLATION_TAIL) TRANSLATION_TAIL else SOURCE_TAIL`，会把任何非 `TRANSLATION_TAIL` 的值（包括 typo）静默变成 `SOURCE_TAIL`。安全但会掩盖用户输入错误——可接受。

**角色名（USER / ASSISTANT / TOOL_CALL / TOOL_RESULT / SYSTEM）一致**：

| 层 | 位置 | 值 |
|---|---|---|
| core | `core/agent/koog/AgentModels.kt:6-11` | `enum class AgentRole { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, SYSTEM }`，`storageKey = name` |
| data | `data/model/AgentEntities.kt:49` | 注释 `USER / ASSISTANT / TOOL_CALL / TOOL_RESULT / SYSTEM`，`role: String` |
| data | `data/db/AppDatabase.kt:211`（MIGRATION_4_5） | `role TEXT NOT NULL` |
| schema | `app/schemas/.../5.json` | `agent_messages.role TEXT NOT NULL` |
| ui | `ui/screens/agent/AgentChatScreen.kt:298+` | `AgentMessageRow` 按 `AgentRole` 分支渲染 |

`AgentRole.fromStorageKey` 未知值回落 `SYSTEM`（`AgentModels.kt:17`）——不会崩。✅

## B8. gradle 一致性 —— ✅ 静态通过

用脚本交叉核对 `app/build.gradle.kts` 的 43 个 `libs.*` 引用 vs `gradle/libs.versions.toml`：

- **`[libraries]` 里缺失的引用：0 个**（4 个"缺失"是 `libs.plugins.*`，它们在 `[plugins]` 段，正常）
- **`version.ref` 指向不存在的 version：0 个**（48 个 version 全部有对应 library 或 plugin）
- `[plugins]` 中 `app/build.gradle.kts:1-6` 用了 4 个；`secrets` / `google-services` 未被使用（AGENTS.md 已说明 catalog 有大量历史遗留）
- `[libraries]` 中 20 个声明未被 `app/build.gradle.kts` 引用（firebase / camera / retrofit / coil / datastore / credentials / logging-interceptor / accompanist 等）——AGENTS.md §2 明确这是**故意的历史遗留**，✅

`settings.gradle.kts` 用 `FAIL_ON_PROJECT_REPOS`，模块脚本无 `repositories {}` ✅。

---

## 附：其他静态发现（不阻塞，但建议修）

| # | 发现 | 位置 | 严重度 |
|---|---|---|---|
| 1 | `response.body ?: return ""` 在 OkHttp 5.2.1 上 Elvis 恒真（`body` 是 `@NotNull`）→ warning | `core/llm/LlmClient.kt:669` | 低 |
| 2 | `runCatching { suspendCall() }` 吞 `CancellationException` | `core/ondevice/OnDeviceGenAiStatus.kt:107,109-111,112-114,115` | 中（取消语义） |
| 3 | `(?U)` 正则在 API 34+ ICU 下抛 `PatternSyntaxException`，且 resolver 走的正是这条路径 | `core/text/TranslationSourcePreparer.kt:212,276` + `core/quality/QualityRuleDefaults.kt:188` | **高（运行时）** |
| 4 | `assets/agent/system-prompt.md` 未接线 | `AssetsAgentPromptLoader.kt:13` 无调用点 | 中（功能） |
| 5 | `android { kotlin { } }` 位置合法性未确认 | `app/build.gradle.kts:61-65` | **高（构建脚本）** |
| 6 | `com.google.mlkit.genai.common` 缺 overrideLibrary | `AndroidManifest.xml:8` | **高（构建）** |
| 7 | `QualityRuleModels.kt` 缺 `Moshi`/`Types` import | `QualityRuleModels.kt:104,108,113` | **致命（编译）** |
| 8 | `kotlin-stdlib` 会被解析到 **2.3.21**（ML Kit POM 要求）而非 KGP 的 2.3.10；2.3.21 的 metadata 版本应仍可被 2.3.10 编译器读取（同 2.3 线），但**未静态确认** | 依赖解析 | 中 |
| 9 | `QualityReplacements`（`QualityRuleEngine.kt:62`）零调用点 | 死代码 | 低 |
| 10 | `AgentTurnStrategy.kt:130` `compressHistory(...)` 会发起一次**付费请求**做 TLDR，且只触发一次 | 行为 | 信息 |

---

# C. 构建阻塞项 Top 5

| 排名 | 阻塞项 | 证据 | 首次构建操作 |
|---|---|---|---|
| **1** | **`QualityRuleModels.kt` 缺 `Moshi` / `Types` import → 必然编译失败** | `app/src/main/java/com/breakyuna/noveltranslator/core/quality/QualityRuleModels.kt:104,108,113` 用无限定 `Moshi` / `Types`，文件 import 区（1-4 行）只有两个 `core.text` import | 加 `import com.squareup.moshi.Moshi` + `import com.squareup.moshi.Types`；依赖 `libs.moshi.kotlin` 已在 `app/build.gradle.kts:102` 引入，无需改 gradle |
| **2** | **`AndroidManifest.xml:8` 的 `tools:overrideLibrary` 漏了 `com.google.mlkit.genai.common`（minSdk 26）→ `mergeDebugMainManifest` 必失败** | 从缓存 AAR 直接读 manifest：`genai-common-1.0.0-beta4.aar` 的 `package="com.google.mlkit.genai.common"` + `minSdkVersion="26"`，且它是三个 genai 包的公共传递依赖 | 把 `com.google.mlkit.genai.common` 加进列表；更稳的写法是用通配符 `com.google.mlkit.genai.*`（manifest-merger 32.1.1 的 `OverrideLibrarySelector` 支持 `.*` 后缀，已反编译确认） |
| **3** | **首次构建必须联网**：Kotlin 2.3.10 / KSP 2.3.10 / 整个 Compose 栈 / `kotlin-stdlib:2.3.10` 均不在本地缓存 | `~/.gradle/caches/.../kotlin-gradle-plugin` 只有 `2.2.10`；`.../com.google.devtools.ksp/symbol-processing-gradle-plugin` 只有 `2.3.5`；`.../androidx.compose/` 只有 `compose-bom` | `./gradlew help` 先确认网络；离线跑会在配置阶段失败，**不要**误判为代码问题 |
| **4** | **`android { kotlin { compilerOptions { jvmTarget } } }` 位置可能不合法**（AGP 9.1.1 的 `CommonExtensionImpl` 没有 `kotlin` 成员，built-in Kotlin 把 `kotlin` 扩展注册在 Project 上） | 反编译 `gradle-9.1.1.jar` 的 `CommonExtensionImpl`（只有 `enableKotlin`）+ `BuiltInKotlinServicesKt.initBuiltInKotlinSupport`（`project.extensions.add("kotlin", ...)`）；该块是 `git show HEAD` 后确认的新增 | 若报 `Unresolved reference: kotlin`，把该块移到 `app/build.gradle.kts` 顶层（`android {}` 之外），语义等价 |
| **5** | **OkHttp 被 Koog 传递依赖从 4.10.0 顶到 5.2.1（实际是 `okhttp-android:5.2.1` AAR）**，全 app 的 HTTP 栈跨大版本 | `koog-agents-android-1.3.0` → `ktor-client-okhttp:3.3.3` → `okhttp:5.2.1` → `available-at` `okhttp-android:5.2.1`；缓存里 `okhttp/5.2.1` + `okhttp-android/5.2.1` 均已下载 | `./gradlew :app:dependencies --configuration debugRuntimeClasspath \| grep okhttp` 确认；API 兼容性已逐符号核对**通过**（`LlmClient.kt` 全部用法在 5.2.1 都存在），但需跑一次真实翻译请求验证运行时。**不要** `resolutionStrategy.force("okhttp:4.10.0")`——ktor 3.x 要求 okhttp 5.x，会崩 |

**第 6 位（非构建阻塞但是最高运行时风险）**：`(?U)` 正则在 Android 14+ (API 34) 的 ICU 实现下抛 `PatternSyntaxException`，而 `QualityRuleResolver` → `QualityRuleDefaults.compileReplacements` → `core/text/TranslationSourcePreparer.kt:276` 正是这条路径；写好了不注入 `(?U)` 的 `QualityRuleEngine.QualityReplacements`（`QualityRuleEngine.kt:62`）却零调用点。首次构建发现不了，需要 API 34+ 真机 + 自定义替换规则才能触发。

---

## 报告使用说明

1. **先修阻塞项 1 和 2**（两个都是确定性问题，改完再跑第一次构建）。
2. 第一次 `./gradlew compileDebugKotlin` 若失败，只把**第一个** `e:` 错误发回给 agent；Kotlin 错误会级联，一次只修一类。
3. 阻塞项 3/4/5 是"环境/构建脚本"类，报错形态与代码错误完全不同，按上表对应操作处理。
4. 编译通过后按 A6 的分批命令跑测试，再 A3 核对 schema diff，再 A7 重录截图。
5. 本报告全部结论基于 2026-10-19 14:45 前后的仓库快照 + 本机 Gradle 缓存产物。**并行 agent 仍在修改**，执行时对标注 🔄 的条目先复检。