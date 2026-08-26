# LightNovelTranslator Agent 工作规范

本文件适用于整个仓库。所有自动化编程 Agent 在分析、修改、审查或交付代码前都必须遵守本文件；子目录若存在更具体的 `AGENTS.md`，以更具体的文件为补充约束。

## 1. 项目定位

LightNovelTranslator 是一个以本地书籍阅读为中心、以大模型翻译为核心增强能力的 Android 应用。

领域关系必须保持清晰：

```text
Book（作品）
├── LogicalChapter / LogicalSegment（跨版本稳定的逻辑位置）
├── Edition（原文、AI 译文、人工版本等可读文本）
├── ReaderProgress（基于逻辑位置的阅读进度）
└── TranslationProject（从源 Edition 生产目标 Edition 的工程）
    ├── Lexicon
    ├── StoryMemory / ChapterMemory
    ├── TranslationRun / Batch / RequestLog
    └── Token、费用、缓存和失败审计
```

不得把首页重新改回“翻译工程列表”，不得把阅读进度绑定到页码、像素滚动值或单一 Edition。完整架构背景见：

- `README.md`
- `docs/superpowers/specs/2026-08-25-reader-translation-platform-design.md`

## 2. 技术栈与目录

- Kotlin、Jetpack Compose、Material 3。
- Room、Coroutines、Flow。
- OkHttp、Moshi、`org.json`。
- JUnit4、Robolectric、Roborazzi。
- 最低 Android 版本和 SDK 配置以 `app/build.gradle.kts` 为准。

主要代码位置：

```text
app/src/main/java/com/breakyuna/noveltranslator/
├── core/
│   ├── agent/          # 术语提取、章节识别等 Agent
│   ├── book/           # 导入与书籍文件
│   ├── llm/            # Provider、请求、重试和 Token
│   ├── parser/         # TXT / EPUB 解析
│   ├── translation/    # 新版书籍翻译 Engine、协议、上下文和 QA
│   └── translator/     # 兼容的旧版翻译链路
├── data/
│   ├── db/             # Room Database 与 DAO
│   ├── model/          # Entity、领域模型和轻量查询模型
│   └── repository/     # 数据访问边界
└── ui/
    ├── navigation/     # 手机底栏、平板侧栏和路由
    ├── screens/        # 各页面
    ├── components/     # 可复用 Compose 组件
    ├── adaptive/       # 窗口尺寸适配
    └── i18n/           # 中英文字符串
```

## 3. 开始工作前

1. 先读取 `README.md`、相关设计文档和任务涉及的实现文件。
2. 使用 `git status --short --branch` 检查工作区；现有修改可能属于用户或其他 Agent，不得覆盖、回退或顺手整理。
3. 使用 `rg` / `rg --files` 定位符号和调用链，不要根据文件名猜测实现。
4. 修改前先追踪完整链路：UI → ViewModel → Repository / DAO → Engine / Storage。
5. 对需求作最小且完整的改动，不进行无关重命名、格式化或架构重写。

## 4. Git 与交付边界

- 除非用户明确要求，否则不要提交、推送、强制覆盖分支、创建 PR、合并分支或触发 GitHub Actions。
- 不得使用 `git reset --hard`、`git checkout --`、`git clean` 等可能破坏现有工作的命令。
- 不得覆盖与当前任务无关的未提交改动。
- 用户要求提交时，提交信息应说明实际行为和可靠性影响，不得使用含糊的 `update` / `fix stuff`。
- 用户要求推送时，先确认目标分支；不得默认把开发改动直接推送到 `main`。

## 5. 验证规则

根据变更范围选择静态检查、单元测试、Lint 和构建验证。执行耗时较长的完整构建前，先确认当前任务是否要求，以及当前环境是否具备对应 Android SDK。

静态验证至少包括：

```bash
git diff --check
git status --short --branch
rg -n "^(<<<<<<<|=======|>>>>>>>)" . -g '!**/build/**'
```

并根据改动补充以下检查：

- 搜索所有被修改类型、函数、路由和枚举的引用，检查签名是否同步。
- 检查 Compose 列表是否有稳定 key，状态是否正确 `remember`，Flow 是否只在需要时订阅。
- 检查 Room Entity、DAO 查询投影、数据库版本和 Migration 字段是否一致。
- 检查新增路由是否同时接入手机底栏、平板侧栏和 `NavHost`。
- 检查中英文字符串是否都能显示，不应在英文界面遗留关键中文操作。
- 检查失败链路是否保存错误分类、原始原因和用户可读说明。
- 纯逻辑变更应补充并执行对应单元测试；涉及 Compose、Room、资源或打包的变更，应根据影响范围补充 Lint、迁移测试或 APK 构建验证。

不得声称“编译通过”“测试通过”或“APK 可用”，除非确实执行了对应验证并获得成功结果。

## 6. Compose 与性能规范

- 大量数据必须使用 `LazyColumn`、`LazyRow`、`LazyVerticalGrid` 等惰性容器，不得在单个 `item` 中通过 `forEach` 一次性组合大量元素。
- 动态列表必须提供稳定且唯一的 key，通常使用数据库主键。
- 不得在 Composable 或主线程中解码全尺寸图片、读取整本文件、解析 EPUB、执行数据库循环或发起网络请求。
- 封面和插图使用后台解码、合理降采样和有界缓存；不得为列表卡片加载原始分辨率图片。
- 高频日志、任务状态或阅读进度不得导致整个复杂页面无条件重组；只在对应 Tab 可见时订阅高频 Flow。
- 大型 Debug 请求和响应在列表中只查询摘要，用户展开单条记录时再读取正文。
- `remember` / `rememberSaveable` 的 key 必须包含其依赖的 Book、Edition、Project 或 Run ID，防止切换对象后复用旧状态。
- 页面切换动画保持快速克制，不要引入长时间、阻塞操作感明显的动画。

## 7. 自适应 UI 与导航

- 手机使用底部导航栏，平板和宽屏使用侧边导航栏。
- 顶级导航当前顺序为：书架、阅读历史、工作台、设置；新增或修改顶级页面时必须保持两种导航一致。
- 书架以封面和作品名为主；单击进入详情，双击继续阅读，编辑/选择行为不得破坏长按拖拽排序。
- 书籍详情、Edition 详情、阅读器和工作台必须同时考虑窄屏竖屏与宽屏横屏。
- 不要硬编码只适合手机的固定宽高；优先使用 `weight`、`widthIn`、`BoxWithConstraints` 和窗口尺寸分类。
- 复杂功能应渐进展示，避免把全部日志、目录、词表和配置同时组合在一个首屏中。
- 优先复用 `ui/components/apple`、主题中的间距、形状和颜色，不随意创建不一致的视觉体系。

## 8. Room、文件与数据一致性

- Room 保存关系、状态、进度、词表、Memory、Revision、任务和审计信息。
- 正文、封面、EPUB 插图和导出文件保存到 `BookFileManager` / `ProjectFileManager` 管理的目录，不得把大段正文或二进制资源直接塞入普通 UI 状态。
- 阅读进度必须基于 `LogicalChapter` / `LogicalSegment`，不得以列表下标作为持久化身份。
- 修改 Entity 时必须同步检查：
  1. `AppDatabase` 版本；
  2. Migration；
  3. DAO 查询；
  4. 查询投影数据类；
  5. Migration 测试；
  6. 新装数据库和升级数据库的默认值。
- 多行排序、批量状态更新等操作应放入 Room 事务，避免中间状态连续触发 Flow 和 UI 抖动。
- 删除 Book 时必须遵循外键与文件清理边界；不得只删数据库记录而遗留大文件，也不得在未解析明确 Book ID 时递归删除目录。
- Revision 是非破坏式编辑边界；人工修改、术语替换和自动修复不得静默覆盖历史译文。

## 9. 翻译任务不变量

- 同一本书内严格串行；并发仅允许发生在不同书籍或独立工程之间。
- 默认按单章请求，用户可配置一次 1–5 章；只有单章确实超过 Token 预算时才分 Chunk。
- 已完成并通过 QA 的章节必须独立提交；后续章节失败不得回滚或重复计费已完成章节。
- 任何重试、续写、修复或重分块都必须经过统一的 `LlmGateway` / RetryPolicy，不得在 UI 中私自重复调用 API。
- 重试必须区分网络、超时、限流、服务端错误、认证、上下文溢出、截断、解析和 QA 错误。
- 认证、无效请求和内容过滤等非瞬时错误不得盲目循环重试。
- 暂停和取消后不得再发起新的付费请求；恢复时从已持久化边界继续。
- Token、费用和请求次数按实际尝试累计，不能只统计最后一次成功请求。
- Provider API Key 必须通过加解密边界读取，禁止从 DAO 直接取得密文后发送，禁止写入日志、提示词、异常或 Debug 数据。

## 10. 翻译协议、QA 与术语扫描

- 翻译响应必须保持 `TranslationProtocol` 的 Chapter / Segment ID 边界；持久化顺序以源 Segment 顺序为准，不依赖 Map 迭代顺序。
- QA 应拦截缺段、空输出、异常长度、拒绝文本、图片标记变化和已确认术语违规，但不得使用容易误伤正常译文的过严启发式规则。
- QA 首次失败应优先精确修复缺失章节或 Segment；修复仍失败时必须记录具体问题，不能只显示“QA 错误”。
- 超长章节的每个 Chunk 同样必须经过 QA 和必要的局部修复。
- 术语扫描必须覆盖用户选择的章节范围；范围较大时分批串行处理，不得静默只取前若干章。
- 术语解析应兼容合理的 JSON 代码围栏、数组包装对象和常见字段别名，但不得从完全不可验证的自然语言中猜测入库。
- 扫描结果必须绑定明确的 TranslationProject，按源术语去重，并在 API 或解析失败时向用户报告真实错误。
- AI 自动产生的词表和 Story Memory 必须保留来源、审核状态和对应 Project，避免跨书污染。

## 11. Debug 与日志

- Debug 模式默认关闭，只记录开启后新产生的完整提示词和模型响应。
- Debug 数据可能包含小说正文，UI 必须提醒用户谨慎分享。
- 即使 Debug 关闭，也应保留轻量的任务状态、Token、费用、错误分类和失败摘要。
- Debug 开启时可保存 System Prompt、User Prompt、模型正文、重试轨迹和 QA 原因，但禁止保存 API Key、Authorization Header 或其他凭据。
- 请求日志列表使用轻量投影，完整正文按 ID 延迟加载；不得让大型 Prompt 随每次 Flow 更新进入整个列表状态。
- 失败页面应同时提供机器错误分类、原始错误信息和用户可理解的原因，不得只显示异常类名。

## 12. 网络与安全

- 所有 LLM 请求必须使用已校验的 Provider 端点和 HTTPS；本地 Provider 的例外行为应由现有 Provider 类型明确控制。
- 不得在日志中输出 API Key、自定义 Authorization Header、签名密钥、GitHub Secret 或完整凭据对象。
- 不得把真实密钥写进源码、测试、README、示例配置或提交信息。
- Provider 连接测试、模型列表拉取和翻译请求必须复用统一的端点拼接和错误解析逻辑。
- 对外部文本、文件名、EPUB 路径和图片标记进行边界校验，避免目录穿越和任意文件读取。

## 13. 测试与变更审查重点

纯逻辑修改应优先补充小而确定的测试：

- `TranslationProtocol` 解析与截断恢复。
- QA 的真阳性和典型误报回归。
- RetryPolicy 的最大次数、退避与不可重试错误。
- 术语 JSON 的兼容解析和去重。
- Room Migration 的新增列、空值和旧数据保留。
- 阅读历史、排序和轻量日志投影的 DAO 查询语义。

最终审查至少回答：

1. 是否改变了 Book / Edition / LogicalPosition 的领域边界？
2. 是否可能重复发起付费 API 请求或重复计费？
3. 是否可能因单章失败终止或覆盖已完成章节？
4. 是否在主线程执行了图片、文件、数据库或网络重任务？
5. 是否引入无界列表、无界缓存或高频整页重组？
6. 是否泄露密钥、提示词或用户正文？
7. 是否同时适配手机和平板导航与布局？
8. 是否需要数据库版本和 Migration？

## 14. 最终汇报格式

交付时简要说明：

- 完成了什么以及用户可见行为。
- 找到的根因和关键实现决策。
- 修改的主要模块。
- 已执行的静态验证及结果。
- 说明实际执行了哪些测试、Lint 或构建验证；未执行的项目也应如实说明。
- 是否提交、推送或触发 Actions；没有执行时必须直接说明。
- 仍存在的真实限制或需要用户实机验证的交互，不得用笼统的“应该没问题”代替。
