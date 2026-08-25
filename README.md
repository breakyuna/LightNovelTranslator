# LightNovelTranslator

面向长篇小说的 Android 阅读 / 翻译一体化平台。应用以书籍阅读为中心：本地 TXT、EPUB 或粘贴文本导入后立即进入书架并可直接阅读，AI 翻译是书籍生成新文本版本（Edition）的一种能力，而不是首页的组织单位。

## 核心模型

- `Book`：作品本身，保存书名、作者、封面与书架状态。
- `Edition`：一份具体可读文本，包括导入原文、AI 译文和人工版本；未完成的 AI Edition 也能立即阅读。
- `LogicalChapter / LogicalSegment`：跨 Edition 稳定的剧情位置，阅读进度不依赖页码或滚动像素。
- `TranslationProject`：从源 Edition 生产目标 Edition 的过程，保存 Provider、模型、风格、上下文、词表和成本策略。
- `SegmentRevision`：AI 初译、自动修复、用户确认替换和人工编辑均保留历史；人工修改不会被后台任务无提示覆盖。

完整设计见 [`docs/superpowers/specs/2026-08-25-reader-translation-platform-design.md`](docs/superpowers/specs/2026-08-25-reader-translation-platform-design.md)。

## 当前能力

- 纯封面书架：首页只显示封面与作品名；存在翻译工程时叠加“翻”标志。单击进入详情，双击继续阅读；编辑模式支持改名、更换封面、排序、移出书架和彻底删除。
- Book Detail：集中展示 Editions、目录、翻译工程、词表、Story Memory 和历史/成本入口。
- 自适应阅读器：支持原文、译文、原译对照和快捷编辑；支持连续滚动、左右分页、上下分页，以及纯净、标准和翻译工作台布局。
- 连续跨 Edition 内容解析：目标译文缺失时自动回退原文；译文完成后按 LogicalSegment 热更新，保持逻辑视口锚点。
- TXT / EPUB / 粘贴文本导入；原始输入和 EPUB 共享图片均按 Book-centric 目录保留。
- 全书、指定范围、无感翻译共用一个 Engine；默认每次一章，用户最多可合批五章。
- 单书严格串行，多书并发；批内逐章解析、QA、提交和统计，末章截断不会重跑已完成章节。
- 超长章节只有在单章无法满足 Token 预算时才启用 Chunk 兜底，普通章节不会默认分块。
- Context Engine 根据正文命中选择已确认词条、相关 Story Memory 和最近 Chapter Memory；翻译响应同时携带 Memory 增量和词表候选。
- 稳定前缀 ContextSnapshot、Provider Prompt Cache 能力抽象、TokenBudgetPlanner 和请求级短 Segment ID。
- 本地确定性 QA：检查边界、Segment ID、空输出、异常长度和图片标记；局部失败只精确重试对应章节/Segment。
- 词表替换采用“扫描 → 预览 → 逐项确认 → Revision”，支持撤销，不会静默改写历史译文。
- Edition 级 TXT / EPUB 导出，使用当前生效的 Revision，而不是依赖 TranslationProject。
- 任务中心汇总跨 Book 的运行状态、Token、费用和错误；Provider、重试与 API Key 安全能力沿用原有实现。

## 存储边界

```text
files/books/book_<id>/
├── cover/
├── source/
├── shared/images/
├── editions/edition_<id>/chapters/
└── workspace/

cache/books/book_<id>/       # 可重建内容
files/exports/book_<id>/     # Edition 导出
```

Room 保存关系、状态、Revision、Memory、Lexicon、任务和审计数据；Book Storage 保存正文与二进制资产；Cache 中只存可重建内容。项目仍处于早期阶段，Reader Platform Schema 采用破坏性重建，不迁移旧 Project 数据。

## 构建

要求 JDK 17 和 Android SDK 36：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## 技术栈

- Kotlin、Jetpack Compose、Material 3
- Room、Coroutines、Flow
- OkHttp、Moshi 与多供应商 REST 适配
- JUnit4、Robolectric、Roborazzi

## 许可证

Apache License 2.0。详见 [`LICENSE`](LICENSE)。
