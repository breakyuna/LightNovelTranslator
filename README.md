# AI 小说翻译助手（Novel Translator）

面向长篇网络小说、轻小说和 EPUB 的 Android 翻译工作台。项目使用 Jetpack Compose、Room、Kotlin Coroutines 和 OkHttp，可连接 OpenAI 兼容接口、Anthropic、Gemini、DeepSeek、OpenRouter、国内兼容服务和本地 Ollama。

## 已实现能力

- 导入 TXT、EPUB 或粘贴文本；单文件上限 100 MB，EPUB 解析有解压大小和条目数保护。
- 中文、英文、Markdown、自定义正则分章；AI 分章以重叠窗口扫描全文（为控制费用，上限 200 万字符），不再只分析开头片段。
- 按模型上下文预算和自然段切分长章节，输出截断时最多续写三次。
- 翻译前按模型窗口注入最近一至三章摘要；8K 以上窗口还注入上一章原文/译文结尾，并动态选择当前章节相关的已审核术语。
- 翻译后校验空输出、拒答、篇幅异常、段落覆盖和 `[IMG:filename]` 标记；失败时自动重试一次。
- 全书术语分批提取。AI 结果先作为候选项，人工确认后才成为强制翻译规则。
- 双语阅读、段落编辑/重译、项目级模型选择，以及持久化的阅读主题、字体、字号、行距和缩进。
- 导出 TXT 或 EPUB，记录 token、费用、耗时和失败原因。

## 上下文与术语策略

单纯逐章独立翻译容易丢失人物指代、语气、伏笔和跨章事件。本项目采用滚动上下文：每章完成后生成摘要；下一章按模型窗口使用最近一至三章摘要，8K 以上窗口再补充紧邻上一章的原文与译文结尾。超长章节分块时，还附带上一译文块的末尾作为衔接参考。

术语提取覆盖全部章节并分批执行，明确源语言与目标语言。自动结果不会直接污染正式术语表：候选项必须由用户确认，翻译时只注入在当前原文实际出现的已确认术语，避免术语表过大挤占上下文。

这套方法显著优于“每章零上下文”的调用方式，但不是全书级记忆的完全替代品。复杂作品仍建议在开始批量翻译前审核主要人物、地点、称谓和世界观术语，并抽查前几章的摘要与译文。

## 安全与隐私

- API Key 与非空自定义鉴权请求头使用 Android Keystore 的 AES-GCM 非导出密钥加密后存入 Room；旧版明文配置会在启动时迁移。
- 应用数据库、配置和项目文件明确排除系统备份；FileProvider 只暴露导出目录。
- 云端接口必须使用 HTTPS。明文 HTTP 仅允许 Ollama 的 localhost、`.local` 或私有局域网地址。
- 本地 Ollama 可以离线翻译；云端供应商会按其服务条款接收所提交的小说文本和上下文。

## 构建

要求 JDK 17 和 Android SDK 36：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

CI 会执行相同的测试、lint 和 Debug APK 构建流程。Release 构建默认不附带签名，发布者应通过 Android Studio 或自己的 CI 安全配置签名材料。

## 使用

1. 在设置中新增供应商，填写 API Key、模型 ID、上下文长度与实际单价，然后测试连接。
2. 导入 TXT/EPUB，确认源语言、目标语言、风格与分章结果。
3. 执行全书术语扫描，审核候选词；需要时手动补充固定译名。
4. 在翻译控制台选择该项目使用的供应商，先试译一章，再启动连续翻译。
5. 在双语阅读器抽查和修订，最后导出 TXT 或 EPUB。

## 技术栈

- Kotlin、Jetpack Compose、Material 3
- Room、Coroutines、Flow
- OkHttp 与各供应商原生 REST 适配
- JUnit4、Robolectric、Roborazzi

## 许可证

Apache License 2.0。详见 `LICENSE`。
