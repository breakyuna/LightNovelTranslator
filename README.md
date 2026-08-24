# LightNovelTranslator

[![Android CI](https://github.com/breakyuna/LightNovelTranslator/actions/workflows/android.yml/badge.svg)](https://github.com/breakyuna/LightNovelTranslator/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)

LightNovelTranslator 是一款面向长篇小说与轻小说的 Android 本地翻译工作台。它负责导入、分章、术语管理、批量翻译、双语校对和导出，并将每一次真实模型请求及每一个翻译分块持久化，尽量避免网络波动、输出截断、暂停或应用重启造成进度丢失和重复付费。

应用本身不提供模型额度。使用云端模型时，需要自行配置对应服务商的 API Key、模型和价格；也可以连接本机或局域网中的 Ollama。

## 项目状态

项目目前处于持续开发阶段，版本号为 `1.0`（`versionCode = 1`），最低支持 Android 7.0（API 24）。仓库暂未发布正式签名的 Release APK，可自行构建 Debug APK，或从成功的 [GitHub Actions 构建](https://github.com/breakyuna/LightNovelTranslator/actions/workflows/android.yml)中下载 `LightNovelTranslator-debug` 产物。

## 主要功能

### 导入与项目管理

- 导入 TXT、EPUB，或直接粘贴文本创建项目。
- 支持中文、英文、Markdown 和自定义正则分章，也可使用模型辅助识别章节。
- AI 分章使用重叠窗口扫描全文；为限制费用，参与扫描的文本上限为 200 万字符。
- EPUB 按 spine 顺序流式读取正文，插图直接写入项目目录，并限制原文件大小、条目数和解压总量。
- 项目、章节、原文、译文、插图和临时分块均保存在设备本地。

### 翻译与上下文

- 根据模型上下文窗口、提示词、术语、摘要和输出预留动态切分章节。
- 每章完成后生成滚动摘要；后续章节按上下文容量引用最近一至三章摘要。
- 对上下文窗口不小于 8K 的模型，可补充上一章原文与译文结尾。
- 长章节分块翻译时，将上一译文块末尾作为衔接参考。
- 只向模型注入当前原文实际命中的已确认术语，减少无关上下文占用。
- 摘要或渐进术语提取失败会留下记录，但不会推翻已经验证并保存的章节译文。

### 术语、校对与导出

- 支持全书术语分批提取；模型结果先进入候选区，人工确认后才成为正式翻译规则。
- 双语阅读器支持原文/译文对照、段落编辑和 AI 段落重译。
- 原文段落使用稳定 Segment ID，并保存一对一、一对多或多对一关系，避免模型合并段落后编辑错位。
- 图片标记作为独立 Segment 处理，阅读器可显示 EPUB 导入的本地插图。
- 可导出 TXT 或 EPUB3；TXT 可选择附带术语表和双语内容。

## 翻译可靠性

LightNovelTranslator 的批量翻译不是简单的“逐章循环”。Room 数据库会保存翻译运行、固定分块边界、子分块、请求明细和稳定段落关系，当前数据库版本为 6。

### 错误分类与重试

供应商适配层将 HTTP 和网络异常转换为明确错误类型，业务层不依赖错误字符串判断是否重试。

| 情况 | 当前行为 |
| --- | --- |
| 临时断网、DNS/连接错误、超时、HTTP 408 | 指数退避并加入随机抖动，最多 5 次实际请求 |
| HTTP 429 | 优先遵循 `Retry-After`，否则指数退避，最多 5 次实际请求 |
| HTTP 500、502、503、504 | 最多 4 次实际请求 |
| 空响应、响应解析失败 | 最多 3 次实际请求 |
| HTTP 401、403 | 不重试，暂停整次翻译并提示检查凭据 |
| HTTP 400、404、422、内容过滤 | 默认不重试 |
| 上下文溢出 | 不重复发送相同请求，由翻译流程缩小分块 |
| 本地文件或数据库失败 | 立即暂停，避免继续产生模型费用 |
| 用户取消 | 不自动重试，并记录用量可能未知的取消请求 |

普通退避单次最多等待 60 秒；服务商返回的 `Retry-After` 可以更长。退避等待可响应暂停和取消。单章耗尽可用重试后会标记失败，批量任务仍可继续处理后续章节；认证、Provider 丢失、币种冲突和本地存储等系统性问题则暂停整次任务。

### 输出截断与完整性校验

应用识别 OpenAI Compatible 的 `finish_reason = length`、Anthropic 的 `stop_reason = max_tokens` 和 Gemini 的 `finishReason = MAX_TOKENS`。

1. 首次截断后，在保留已生成内容的基础上请求续写。
2. 每个分块最多续写 2 次，并在合并时去除边界重复。
3. 合并结果需要通过空输出、拒答、长度、段落覆盖和 `[IMG:...]` 标记校验。
4. 仍然截断或校验失败时，放弃不可靠结果，将原始分块按自然段、句子或安全字符边界拆成更小的持久化子分块，从头翻译。
5. 只有通过校验的完整结果才会提交为成功译文。

### 暂停、取消与恢复

- **暂停**：先记录 `PAUSE_REQUESTED`，允许当前正在进行的请求结束并保存结果，然后停止启动新请求并进入 `PAUSED`。
- **取消**：可以取消当前网络请求，但保留已经完成的分块，并清理章节的运行中状态。
- **应用重启**：旧的 `RUNNING`、`RETRY_WAIT` 和 `PAUSE_REQUESTED` 任务会转为 `INTERRUPTED`，不会自行继续产生费用。用户确认恢复后，从第一个未完成分块继续。
- **原子提交**：分块先写同目录临时文件，再原子替换目标文件；启动恢复时会校正文件与数据库状态不一致的情况。

恢复前会检查原文哈希、Provider、币种和临时文件。已完成且校验有效的分块不会重复请求；如果进程在请求途中消失而无法确认服务商是否计费，请求会以未知用量记录并提示用户。

### 请求级审计

每一次实际 LLM 请求都会立即生成独立记录，包括：

- 操作类型：翻译、续写、质量重试、摘要、术语提取、AI 分章或段落重译。
- Run、章节、分块、父子分块和尝试编号。
- Provider、模型、输入/输出单价及币种快照。
- Prompt token、Completion token、费用、开始时间和耗时。
- HTTP 状态、请求 ID、finish reason、错误分类和错误说明。
- 用量来源：供应商返回的 `ACTUAL`、本地估算的 `ESTIMATED` 或无法确定的 `UNKNOWN`。

界面中的费用是按配置价格聚合的本地记录和估算，不等同于供应商最终账单。

## 支持的模型服务

| 接入类型 | 内置预设或用途 |
| --- | --- |
| OpenAI Compatible | OpenAI、DeepSeek、SiliconFlow、OpenRouter、通义千问、智谱 GLM、自定义兼容端点 |
| Anthropic Messages API | Anthropic Claude |
| Gemini API | Google Gemini Direct，API Key 通过 `x-goog-api-key` 请求头发送 |
| Ollama | Android 设备本机或同一局域网内的 Ollama 服务 |

设置页支持自定义 Base URL、模型 ID、上下文长度、温度、输入/输出单价、币种和字符串到字符串的自定义 Header。拉取模型、测试连接和正式翻译使用同一套自定义 Header。切换 Base URL 不会静默清空已经保存的凭据。

> 模型名称、上下文窗口和价格会随服务商变化，内置值仅用于快速创建配置。开始长篇翻译前，请按服务商控制台的当前信息核对这些字段。

## 快速开始

1. 打开“设置”，选择预设或创建自定义 Provider。
2. 填写 API Key、模型 ID、上下文长度、温度和实际价格，然后执行模型拉取或连接测试。
3. 导入 TXT/EPUB 或粘贴文本，确认源语言、目标语言、翻译风格和分章结果。
4. 可选：扫描全书术语并审核候选译名。
5. 在翻译控制台选择 Provider，先试译少量章节并检查质量与费用，再启动连续翻译。
6. 在双语阅读器中校对，最后导出 TXT 或 EPUB。

## 数据与隐私

- API Key 和非空自定义鉴权 Header 使用 Android Keystore 中不可导出的 AES-GCM 密钥加密后存入 Room；旧版本明文配置会在启动时迁移。
- 数据库、配置和项目目录已排除 Android 系统备份；FileProvider 只暴露导出目录。
- 系统日志会持久化并轮转，但不会写入 API Key、自定义鉴权头或完整小说正文；清除日志会同时清理内存和文件。
- 云端 Provider 会收到完成当前操作所需的正文、提示词、术语和上下文，请在使用前确认其隐私政策与服务条款。
- 明文 HTTP 只允许 Ollama 的 localhost、`.local` 和私有局域网地址；其他远程接口必须使用 HTTPS。

## 构建与测试

### 环境要求

- JDK 17
- Android SDK 36（项目使用 `compileSdk 36.1`）
- Android Studio 或命令行 Android 构建环境

克隆仓库并构建：

```bash
git clone https://github.com/breakyuna/LightNovelTranslator.git
cd LightNovelTranslator
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如有可用模拟器或真机，可执行：

```bash
./gradlew connectedDebugAndroidTest
```

GitHub Actions 会在推送到 `main`、向 `main` 提交 Pull Request 或手动触发时执行单元测试、Lint 和 Debug APK 构建，并上传 APK 产物。现有自动化测试覆盖重试策略、`Retry-After`、EPUB 流式解析与安全限制、稳定 Segment 解析，以及 Room 1→6 和各相邻版本迁移。

## 项目结构

```text
app/src/main/java/com/breakyuna/noveltranslator/
├── core/
│   ├── agent/       # AI 分章与术语提取
│   ├── exporter/    # TXT / EPUB 导出
│   ├── llm/         # LLM 调用边界、供应商请求、错误分类与重试
│   ├── parser/      # TXT / EPUB 解析
│   ├── project/     # 项目文件、临时分块与原子提交
│   ├── security/    # Provider 凭据加密
│   └── translator/  # 翻译状态机、质量校验与稳定 Segment
├── data/
│   ├── db/          # Room Database、DAO 与 Migration
│   ├── model/       # 项目、章节、任务、分块与请求记录实体
│   └── repository/  # 数据访问封装
└── ui/              # Compose 页面、导航、组件、主题与 ViewModel
```

Room Schema 会导出到 [`app/schemas`](app/schemas)，数据库升级使用显式 Migration，不启用破坏性迁移。

## 当前限制

- 应用尚未使用 WorkManager 或前台服务，无法保证在 Android 后台限制或系统回收进程时持续翻译；重新打开应用后可以恢复已落盘的进度。
- 暂无正式 Release APK 和发布签名配置，仓库及 CI 当前主要产出 Debug APK。
- EPUB 重点保留章节顺序、基础元数据、封面和插图，不保证还原复杂 CSS、脚本、交互排版或受 DRM 保护的内容。
- Token 估算和费用统计用于任务控制与审计，供应商侧计费规则、缓存折扣、批处理价格和取消中的请求可能导致最终账单不同。
- 模型输出仍可能存在误译、遗漏或风格漂移；长篇批量翻译前应先试译，并人工审核关键术语与成品。

## 参与开发

欢迎通过 Issue 报告可复现的问题，或提交范围明确的 Pull Request。涉及数据库结构时，请同时提供保留旧数据的 Migration 与迁移测试；涉及模型调用时，请使用 Fake Gateway 和虚拟等待，测试不得访问真实 API。

提交前建议运行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
git diff --check
```

## License

本项目采用 [Apache License 2.0](LICENSE)。
