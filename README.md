# 📖 AI 小说翻译助手 (Novel Translator)

一款专为长篇网络小说、轻小说与电子书量身打造的 **现代化、端侧驱动的 LLM 智能翻译工作台**。基于 Android + Jetpack Compose + Room 架构，深度集成大语言模型，支持超长章节智能切分、术语表统一、上下文剧情连贯翻译、图文 EPUB 解析与导出。

---

## ✨ 核心特性

### 1. 📚 全格式解析与智能切章
- **EPUB / TXT 全面支持**：支持直接导入 TXT 文本与 EPUB 电子书。
- **正则与 AI 智能识别**：内置中文（“第X章/卷/节”）、英文（“Chapter X / Part X”）等多语言智能正则分章，支持自定义正则表达式。
- **插图与排版完整保留**：解析 EPUB 时精准保留内嵌插图标记（`[IMG:...]`），并在导出时自动重组图文混排。
- **内置示例书库**：开箱即用，提供中英双语多题材示例小说，便于快速体验。

### 2. 🤖 多模型接入与本地 LLM 支持
- **主流云端大模型**：
  - **DeepSeek**（DeepSeek-V3 / DeepSeek-R1）
  - **SiliconFlow**（硅基流动 / Qwen2.5 / DeepSeek）
  - **Google Gemini**（Gemini 2.5 Flash / Pro 原生直连）
  - **Anthropic Claude**（Claude 3.5 Sonnet / Haiku 原生直连）
  - **OpenRouter** & **OpenAI 兼容协议**（DashScope 通义千问、Moonshot 月之暗面、智谱 GLM 等）
- **100% 离线隐私翻译**：
  - 支持 **Ollama** 本地或局域网私有化模型（如 `qwen2.5:7b`、`deepseek-r1:8b`、`llama3.1`），完全无需网络连接，保障数据隐私。
- **截断检测与自动续写**：
  - 自动识别输出 Token 限制导致的截断（`finish_reason == length / max_tokens`），无缝触发续写请求并自动拼接，彻底告别断句漏字。

### 3. 🧠 术语表管理与剧情上下文连贯
- **AI 智能术语提取**：一键分析全书提取人名、地名、门派、功法技能与专有名词。
- **强制术语一致性**：翻译提示词严格注入术语约束，保证全书专有名词翻译精准统一。
- **章节剧情滚动摘要**：每章翻译完成后自动提炼精炼剧情摘要，作为下一章节的连贯性背景指引，有效解决大模型“读到后面忘记前面设定”的问题。
- **动态 Token 预算与自然段智能分块**：针对超长章节，自动按段落双换行与 Token 预算进行分块翻译，保留句子自然完整性。

### 4. 👓 双语对照阅读与校对
- **沉浸式双语排版**：支持原文与译文段落级双语对照、纯译文阅读两种模式。
- **深度个性化排版**：支持调整字体大小、行高以及主题配色（日间明亮、羊皮纸护眼、暗黑夜间）。
- **实时校对与单章重译**：支持对不满意的翻译章节手动修改编辑或单独发起重新翻译。

### 5. 📤 成果导出与 Token 消耗统计
- **标准格式一键导出**：
  - **EPUB 电子书**：自动构建标准 OEBPS 结构、`content.opf` 元数据、`toc.ncx` 章节目录以及高清插图资源包。
  - **TXT 文本**：干净整洁的段落排版导出。
- **精细化计费与日志**：
  - 实时追踪 Prompt Tokens、Completion Tokens 消耗。
  - 支持多币种（USD / CNY）单价自定义与实时费用计算。
  - 具备全流程日志记录与失败重试机制。

---

## 🏗️ 架构与技术栈

- **UI 框架**：Jetpack Compose + Material Design 3 (M3) 动态色彩系统
- **架构模式**：MVVM + Clean Architecture + Kotlin Coroutines & Flow
- **本地持久化**：Android Jetpack Room Database（项目、章节、术语表、API 配置、日志）
- **网络与通信**：OkHttp + Kotlinx Serialization + 原生 REST API 适配器
- **电子书引擎**：基于 `java.util.zip` 的轻量级高性能 EPUB 解析与打包导出器
- **测试框架**：Robolectric + JUnit4 单元测试

---

## 🚀 快速上手

### 1. 配置 API 供应商
1. 打开应用，进入 **设置 (Settings) -> API 供应商 (API Providers)**。
2. 点击 **添加供应商**，可直接从预设快速选择（如 DeepSeek、Gemini、SiliconFlow、Claude、Ollama 等）。
3. 填入对应的 API Key（或 Ollama 本地地址 `http://localhost:11434/v1`）。
4. 点击 **测试连接** 确保配置正确后设为默认。

### 2. 创建并翻译项目
1. 在首页点击 **+ 新建项目**。
2. 选择 **导入 TXT / EPUB** 文件，或直接选择内置示例小说。
3. 设定源语言（如 Japanese / English / Chinese）与目标语言（如 Chinese / English）。
4. 在项目详情页点击 **AI 提取术语** 自动提取专有名词（可手动增删改）。
5. 点击 **开始翻译**，应用将实时显示翻译进度、分块状态与 Token 消耗。

### 3. 预览与导出
1. 翻译过程中或完成后，随时进入 **双语阅读器** 查看与校对译文。
2. 点击右上角 **导出** 按钮，选择导出为 **EPUB** 或 **TXT**，分享至阅读器或本地文件。

---

## 📄 许可证

本项目基于 Apache 2.0 License 开源，欢迎自由使用与定制。
