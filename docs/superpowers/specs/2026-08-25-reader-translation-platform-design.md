# LightNovelTranslator 阅读 / 翻译一体化架构设计

日期：2026-08-25

状态：已完成交互设计确认，待实施计划

## 1. 目标

将 LightNovelTranslator 从“以翻译工程为中心的 Android 工具”重构为“以书籍阅读为中心、翻译作为书籍能力之一”的小说阅读与翻译平台。

核心原则：

- `Book` 表示作品本身。
- `Edition` 表示可阅读的具体文本版本。
- `TranslationProject` 表示某个 Edition 如何由另一个 Edition 生产出来。
- 阅读器围绕逻辑位置工作，不围绕某个具体译本的页码或滚动像素工作。
- 现有稳定性、重试、任务、请求审计、Token / 费用统计、EPUB/TXT 解析和导出能力尽量复用，但上层领域模型重构。
- 项目仍处于早期阶段，不考虑旧数据库、旧文件目录和旧工程的数据迁移兼容；允许破坏性 Schema 重构，以最终架构清晰度优先。

## 2. 本期范围与明确不做

### 本期范围

1. 书架作为首页。
2. 本地 TXT / EPUB 导入后直接形成可阅读 Book。
3. Book / Edition / LogicalChapter / LogicalSegment 领域模型。
4. 阅读器：原文、译文、原译对照、快捷编辑、纯净阅读、多种翻页方式。
5. TranslationProject 作为 Edition 生产系统。
6. 全书翻译、指定范围翻译、无感翻译。
7. 单书严格串行，多书 / 多工程并发。
8. 名词表、专业术语表、可预览批量替换。
9. Story Memory / Chapter Memory / Context Engine。
10. Prompt Cache、Token Budget 与费用优化。
11. 非破坏式 Segment Revision。
12. Edition 级 TXT / EPUB 导出。
13. 为未来 WebView 找书 / 网页抓取预留 Acquisition 接口。

### 明确不做

- 本期不实现 WebView 找书页。
- 本期不实现 DOM / XPath / 站点适配器 / 插件市场 / 规则 DSL。
- 不实现基于阅读速度动态预测的智能翻译缓冲。
- 不在单本书内部做章节并发。
- 不默认把章节切成 Chunk。
- 不为旧 `ProjectEntity`、旧目录或旧数据库保留兼容迁移层。
- 第一阶段不引入向量数据库或 Embedding 检索；上下文检索先采用实体、关键词、章节距离和结构化 Memory。

## 3. 总体架构

```text
本地文件 / 未来 Web Capture
            ↓
          Book
            ↓
     Original Edition
            ↓
 LogicalChapter / LogicalSegment
        ↙                    ↘
     Reader             TranslationProject
                              ↓
                         Context Engine
                              ↓
                    Translation Scheduler
                              ↓
                    Translation Engine
                              ↓
                      Draft QA / Revision
                              ↓
                Optional post-draft AI review / polish
                              ↓
                    Translation Edition
                              ↓
                            Reader
```

导入阶段先由 TXT / EPUB 解析器（或用户在 V2 工作台主动调用的 AI Chapter Split Agent）识别章节并建立稳定的 LogicalChapter / LogicalSegment，AI 结果必须先展示候选列表并经用户确认后才持久化，随后才允许 TranslationProject 选择范围。章节识别 Agent 只能返回可在原文中定位的标题与首句，不能改写或替换原文；EPUB 继续以解析器目录为准。

关键关系：

```text
Book
├── LogicalChapter
│   └── LogicalSegment
│
├── Edition A：原文
│   └── EditionChapter
│       └── EditionSegment
│
├── Edition B：AI 译文
│   └── EditionChapter
│       └── EditionSegment
│           └── SegmentRevision
│
└── TranslationProject
    ├── sourceEditionId = A
    ├── targetEditionId = B
    ├── Lexicon
    ├── StoryMemory / ChapterMemory
    ├── ContextSnapshot
    ├── TranslationRun / Batch / RequestLog
    └── Cost / Cache statistics
```

原则：**Book 是作品，Edition 是可读文本，TranslationProject 是生产 Edition 的过程。**

## 4. 核心领域模型

### 4.1 Book

负责作品级信息，不承担 LLM 工程职责。

建议包含：

- `id`
- `title`
- `author`
- `coverPath`
- `description`
- `originalLanguage`
- `primaryEditionId`
- `preferredReadingEditionId`
- `hiddenFromShelf`
- `createdAt / updatedAt`

### 4.2 LogicalChapter / LogicalSegment

表示跨 Edition 稳定的剧情位置，不直接存“某种语言的文字”。

阅读位置、原译切换、对照编辑、热更新都建立在逻辑位置之上。

逻辑位置至少需要：

```text
Book + LogicalChapter + LogicalSegment + segmentOffset
```

不得以“第 N 页”或“scrollY”作为权威阅读进度，因为字号、设备宽度和 Edition 切换都会改变排版。

Derived Translation Edition 必须保持逻辑章节 / Segment 映射。

第一阶段不解决任意外部 Edition 之间自动章节对齐的问题；新翻译 Edition 都从一个已知 source Edition 派生，因此映射天然可控。

### 4.3 Edition

表示一份可阅读文本版本。

建议类型：

- `IMPORTED`
- `AI_TRANSLATION`
- `MANUAL`
- `WEB_CAPTURE`（仅预留）

Edition 可以不完整。AI Translation Edition 在只完成 27 / 300 章时已经可阅读，未完成位置由 Reader Resolver 回退到源 Edition。

### 4.4 EditionChapter / EditionSegment

Edition 的实际文本内容映射到 LogicalChapter / LogicalSegment。

Segment 映射必须允许：

- 1 → 1
- 1 → N
- N → 1
- N → N

不能假设 AI 永远严格保持原文段落数量。

### 4.5 SegmentRevision

所有人工编辑和已确认替换采用非破坏式修订历史，不直接无痕覆盖 AI 初译。

典型链路：

```text
AI_TRANSLATION
    ↓
AI_POLISH
    ↓
LEXICON_REPLACEMENT
    ↓
MANUAL_EDIT
```

每个 EditionSegment 指向当前采用的 Revision，同时保留历史 Revision。

自动任务不得无提示覆盖已经存在的人工修订。默认优先级：

```text
MANUAL_EDIT
> USER_CONFIRMED_REPLACEMENT
> AI_POLISH
> AI_TRANSLATION
> AUTO_REPAIR
```

## 5. 阅读进度与 Reader Session

Book 保存一个跨 Edition 的主逻辑阅读位置；Edition 可以额外保存辅助定位数据，但不能成为主进度来源。

ReaderSession 至少包含：

- `bookId`
- `preferredEditionId`
- `logicalPosition`
- `displayMode`
- `pagingMode`
- `readerLayoutMode`

### DisplayMode

- 译文
- 原文
- 原译对照
- 快捷编辑

这些不是四个不同页面，而是同一个 ReaderSession 的不同显示策略。

切换模式时保持 LogicalPosition 不变。

## 6. Reader Content Resolver 与连续跨 Edition 渲染

阅读器内部维护逻辑内容流，而不是“章节页”。

对每个逻辑内容单元：

1. 优先读取用户当前指定 Edition。
2. 如果目标 Edition 对应 Segment 已可用，则显示译文。
3. 如果目标 Edition 缺失，则自动 fallback 到 source / original Edition。
4. 原文和译文允许在同一个连续滚动视图中自然相邻。

因此可以出现：

```text
第17章末尾：中文译文
第18章开头：日文原文
```

这不是错误状态，而是正常解析结果。

### 热更新

当用户正在阅读 fallback 原文，后台译文完成后，**直接热更新当前可见正文为译文**。

必须通过 `LogicalSegmentId + segmentOffset` 维护视口锚点，重新排版后恢复到近似相同视觉位置，避免明显跳屏。

如果用户正在进行滚动手势或编辑当前 Segment，可以等当前交互结束后立即应用更新。

允许轻量淡入淡出，但不显示阻断式弹窗。

## 7. 阅读模式与自适应布局

### 翻页方式

默认：纵向连续滚动。

额外支持：

- 左右分页
- 上下分页

动画作为独立设置：

- 无动画
- 平滑平移
- 淡入淡出
- 仿真翻页

翻页表现不得影响底层逻辑位置。

### 手机

- 默认单栏沉浸阅读。
- 原译对照默认采用逐段上下对照。
- 横屏且宽度足够时可提供左右双栏。
- 工具通过顶部 / 底部控制层和 Bottom Sheet 唤出。

### 平板

提供三种布局：

1. **纯净阅读**：全屏正文，控制层自动隐藏；正文保持合理最大行宽，不强制展示侧栏。
2. **标准阅读**：可唤出目录、Edition、设置等辅助面板。
3. **翻译工作台**：原文 / 译文双栏，支持术语、编辑、Revision 等辅助操作。

布局依据 Window Size Class（Compact / Medium / Expanded），不按“是否 tablet”硬编码。

## 8. 书架与信息架构

顶层导航本期为：

```text
书架
任务
系统设置
```

未来 WebView 找书实现后扩展为：

```text
书架
找书
任务
系统设置
```

### 书架首页

首页只展示：

- 封面
- 作品名称

如果 Book 存在至少一个 TranslationProject，则在封面右下角覆盖显示一个半角 `翻` 标志。

`翻` 只表示“存在翻译工程”，不表示运行 / 暂停 / 失败等状态。

交互：

- 单击书籍卡片：进入 Book Detail。
- 双击书籍卡片：直接继续上次阅读位置。
- 进入书架编辑模式后可以：调整顺序、修改作品名、更换封面、编辑基础信息、删除。
- 编辑模式期间关闭单击详情 / 双击阅读手势，避免冲突。

手机和平板都保持纯封面书架，不因为平板空间大而把卡片变成信息面板。

### Book Detail

Book Detail 是一本书的控制中心，核心入口为“继续阅读”。

至少包含：

- Editions
- 目录
- 创建 / 管理翻译
- 名词表 / 专业术语表
- Story Memory
- 翻译历史 / 成本
- 书籍信息

TranslationProject 不再作为首页主对象。

### Task Center

任务中心只负责跨 Book 的任务监控和控制：

- 运行中
- 等待 / 重试
- 暂停
- 失败
- 费用 / Token

Task Center 是监控器，不是内容管理器。

## 9. TranslationProject 与翻译策略

TranslationProject 负责：

- `sourceEditionId`
- `targetEditionId`
- 源 / 目标语言
- 模型与 Provider
- Style Guide
- Prompt Protocol Version
- Lexicon
- Context Policy
- Translation Mode
- 每次最多翻译章节数
- QA 策略
- Cost Policy
- 请求 / 缓存 / Token 统计

### 翻译模式

1. 全书翻译
2. 指定章节范围
3. 无感翻译

三种模式共享同一个 Translation Engine，只改变调度策略。

### 无感翻译

- 初始化先翻译前 5 章。
- 之后维持固定“提前 N 章”缓冲。
- N 为用户可配置值，例如 2 / 5 / 10 或自定义。
- 不根据阅读速度动态预测和自动扩缩容。
- 缓冲不足时，越靠近当前阅读位置的章节优先级越高。
- 缓冲耗尽时 Reader 自动 fallback 原文，译文完成后热更新。

## 10. 并发原则

### 单本书 / 单工程

严格串行。

不得同时翻译同一本书中的多个后续批次。必须先完成当前批次、QA、提交和 Memory 更新，再开始下一批。

### 多本书 / 多工程

允许并发。

全局 Scheduler 可以同时运行多个不同 Book / TranslationProject，但当前正在阅读的 Book 可以获得更高优先级。

## 11. 翻译 Batch 与 Chunk 策略

默认每次 API 请求翻译 **1 章**。

用户可以设置“每次最多翻译章节”：

- 1（默认 / 推荐）
- 2
- 3
- 4
- 5

规则：

```text
ActualBatchSize ≤ UserMaxBatchSize ≤ 5
```

TokenBudgetPlanner 可以因为上下文或预计输出过长自动降低实际 Batch Size，但绝不能自动高于用户设置。

多章请求仍逐章建立独立边界、独立解析、独立 QA、独立保存和统计。

### Chunk

Chunk 仅作为兜底机制：只有单独一章都无法安全放入模型输入 / 输出预算时才切 Chunk。

普通章节不得为了形式上的“工程化”默认拆成多个 API 请求。

原因：默认整章可显著减少重复发送名词表、摘要、Story Memory 和 Prompt 的 Token 成本，同时改善章节级语义连续性。

## 12. Lexicon：名词表与专业术语表

底层统一为 Lexicon，但产品上区分：

- 名词表 Proper Nouns
- 专业术语表 Terminology

建议字段：

- `sourceTerm`
- `targetTerm`
- `type / category`
- `aliases`
- `notes`
- `caseSensitive`
- `exactMatch`
- `priority`
- `enabled`
- `source`（AI / manual / imported）
- `reviewStatus`（candidate / confirmed）

### 翻译约束

Prompt 只注入当前 Batch 真正命中的词条；核心高频词条可以进入稳定上下文前缀。

### 已有译文批量替换

修改 Lexicon 时不自动偷偷修改历史译文。

流程：

1. 扫描受影响 Segment。
2. 显示匹配数量和替换预览。
3. 用户选择范围：当前段 / 当前章 / 指定章节 / 当前 Edition 全书 / 所有目标 Edition。
4. 支持逐项排除。
5. 用户确认后生成 `LEXICON_REPLACEMENT` Revision。
6. 支持撤销。

词表变化默认只影响后续翻译；是否修改已有译文必须由用户确认。

## 13. Context Engine 与 Story Memory

Context Engine 与 Translation Engine 同级，负责为每次翻译请求组装真正相关的上下文。

### 持久化资产

- Lexicon
- Story Memory
- Chapter Memory
- Style Guide
- Context Snapshot
- 历史 Segment / Revision（可作为 Translation Memory 数据源）

这些属于权威数据，不是可清理缓存。

### Runtime Cache

可重建内容才放缓存目录，例如：

- Token 估算结果
- 检索索引
- 派生 Context Package
- 未来可能的 Embedding 索引

清空 Android Cache 不得让翻译工程“失忆”。

### Chapter Memory

保持紧凑结构，例如：

- summary
- entities
- stateChanges
- newFacts
- unresolvedThreads

摘要目标为短摘要，不生成冗长剧情解析。

### Story Memory

保存事实级长期记忆，不让模型每章重写整份 Story Bible。

模型只产生 `StoryMemoryDelta`：

- ADD
- UPDATE

本地 MemoryUpdater 负责去重、合并、记录来源章节和冲突。

第一阶段不依赖模型自报 confidence 数值。

## 14. 翻译请求同时生成 Memory

默认主翻译 API 一次返回：

1. Translation
2. ChapterMemory
3. StoryMemoryDelta
4. LexiconCandidate

不额外单独请求 Story Memory，从而减少重复输入 Token 与 API 次数。

规则：

- Memory 只输出增量，不输出完整 Story Bible。
- Memory 解析失败不影响已经成功的译文。
- 失败的 Memory 可以后续补生成。
- 辅助模型仅作为 Memory 修复、旧内容分析或可选专项任务使用，而不是正常翻译链路的必需调用。

对于多章 Batch，本批使用请求开始前的同一个 ContextSnapshot；本批新生成的 Memory 从下一批开始正式进入 Context。

## 15. Context Token Budget

Context Engine 追求“最相关的上下文”，不是“最多的上下文”。

上下文按价值 / Token 成本选择：

优先级高：

- 当前正文
- 硬性 Translation Protocol
- 当前正文命中的 confirmed Lexicon
- 当前人物 / 地点相关 Story Memory
- 最近章节的重要 Chapter Memory

优先级低：

- 很久以前且与当前章节无关的详细摘要
- 没有命中的术语
- 可由更短结构化事实替代的长文本

TokenBudgetPlanner 必须同时考虑：

- 模型 context window
- 当前输入长度
- 预计译文输出长度
- META 输出长度
- 安全余量

预算是上限，不要求用满。

## 16. Prompt Cache 与费用优化

供应商 Prompt Cache 与本地 Token 减少是两套互补机制。

### Prompt 结构

尽量采用“稳定前缀 + 动态后缀”：

```text
稳定前缀：
Translation Protocol
Style Guide Snapshot
核心高频 Lexicon
稳定 Story Memory Snapshot

动态后缀：
当前命中 Lexicon
相关 Story Memory
Recent Context
当前正文
```

### ContextSnapshot

不要因为每完成一章就重写稳定前缀。

ContextSnapshot 固定一段时间使用的：

- Protocol Version
- Style Guide Version
- Core Lexicon Version
- Story Memory Snapshot Version

新增 Memory 可以先作为动态检索内容使用，在合适检查点再产生新 Snapshot。

### Provider Cache Capability

上层使用统一 Cache Capability 抽象，不在 TranslationEngine 中散落 Provider 判断。

至少记录：

- provider / model
- cache fingerprint
- remote cache id（如适用）
- cached token count
- hit / miss token
- estimated saved cost
- expiry（如适用）

显式缓存只有在预计存在足够复用次数时才创建，不默认无脑创建。

### 其他 Token 优化

- Lexicon 按命中注入。
- Story Memory 按实体相关性注入。
- 远期历史逐级压缩。
- 已存在可信相同 Segment 译文时允许直接复用。
- 本地确定性 QA 优先于额外 LLM QA。
- 多章合批由用户主动控制，最多 5 章。
- 默认整章，不默认分 Chunk。

## 17. Prompt 与响应协议

### 输入

```text
[TRANSLATION_PROTOCOL]
[STYLE_GUIDE]
[LEXICON]
[STORY_MEMORY]
[RECENT_CONTEXT]
[SOURCE]
```

稳定内容尽量靠前，正文放最后。

### Segment ID

数据库内部使用稳定 LogicalSegmentId。

发给模型时映射成请求级短 ID，减少协议 Token，例如：

```text
<C id="12">
<S id="1">...</S>
<S id="2">...</S>
</C>
```

模型不需要复制原文，只返回译文。

### 输出

正文采用轻量标签，不使用巨大 JSON 包裹整篇小说：

```text
<TRANSLATION>
<C id="12">
<S id="1">译文...</S>
<S id="2">译文...</S>
</C>
</TRANSLATION>

<META>
{紧凑 JSON，仅包含 ChapterMemory / MemoryDelta / LexiconCandidate}
</META>
```

原则：

- 正文不用 JSON。
- 短元数据才使用 JSON。
- 不复制 source text。
- 不输出翻译解释。
- 章节 / Segment 有独立边界。
- 图片只传递占位标记并原样返回。
- 标题与正文类型分离。
- 默认禁止模型跨 Segment 重排内容。

### 多章 Batch

每章独立解析和提交。

如果 5 章请求中前 4 章完整、第 5 章截断：

- 前 4 章保留成功结果。
- 仅续写 / 重试第 5 章及其剩余部分。
- 不整批重新发送。

### Streaming

供应商支持时允许 streaming，但流内容先进入 Temporary Buffer；只有 Segment / Chapter 边界完整并通过最低 QA 后才正式提交到 Edition。

## 18. QA 与错误处理

### Level 1：本地确定性 QA

不调用 LLM，检查：

- 空输出
- 截断
- Segment ID 丢失 / 重复 / 乱序
- 章节边界丢失
- 明显漏译
- 长度异常
- 图片标记缺失
- 强制术语违规
- 拒答 / 解释性废话
- 重复段落

### Level 2：规则修复 / 精确重试

- 截断 → 续写
- 格式错误 → 重试当前章节 / Segment
- 术语错误 → 精确重译受影响范围

不得因为一处局部错误无条件重跑整书或整批。

### Level 3：可选 LLM QA

仅在真正可疑或用户启用“高质量审校”时调用。

默认不让每次翻译都再花一次等量 Token 做 LLM QA。

## 19. 单书翻译执行顺序

单本书严格串行：

```text
导入 / 章节识别
  ↓
Draft Batch N
  ↓
Context Preparation
  ↓
Translation API
  ↓
逐章解析
  ↓
逐章本地 QA
  ↓
逐章提交 Edition / Revision
  ↓
逐章应用 ChapterMemory / StoryMemoryDelta / LexiconCandidate
  ↓
Draft scope complete?
  ├─ 否 → Draft Batch N+1
  └─ 是 → Optional post-draft AI review / polish
                    ↓
               AI_POLISH Revision
```

二次审校是工程级可选阶段而非逐章即时回调：只有当前翻译范围的所有章节都存在完整初稿并通过基线 QA 后才开始。审校失败、结构异常、QA 拒绝或预算不足时保留初稿并记录可追踪原因；人工编辑和已确认术语替换的 Revision 优先级高于 `AI_POLISH`。

如果 Translation 成功但 Memory 部分失败：

```text
Translation = SUCCESS
Memory = PENDING_REPAIR
```

译文仍立即可读。

## 20. 配置变化与 Edition 边界

用户在翻译过程中改变模型、Prompt、Style Guide 等重要配置时，不强制自动创建新 Edition。

对于明显的重大配置变化，提示用户选择：

- 继续当前 Translation Edition
- 创建新的 Translation Edition

若继续当前 Edition，TranslationProject 历史中记录配置边界，便于追溯不同章节使用的：

- Provider / Model
- Protocol Version
- Style Guide Version
- Lexicon Version
- Context Snapshot Version

## 21. 文件存储

采用 Book-centric 存储：

```text
files/
└── books/
    └── book_<id>/
        ├── cover/
        ├── source/
        ├── shared/
        │   └── images/
        ├── editions/
        │   ├── edition_<id>/chapters/
        │   └── ...
        └── workspace/

cache/
└── books/book_<id>/
    ├── context/
    ├── token/
    └── indexes/

exports/
└── book_<id>/
```

### 权威边界

- Room：关系、状态、Revision、Memory、Lexicon、任务、日志等结构化权威数据。
- Book Storage：正文与二进制资产。
- Cache：全部可重建。

原始 TXT / EPUB 导入文件必须保留，便于重新解析和恢复。

EPUB 图片默认作为 Book 共享资源，各 Edition 通过 ImageAsset 引用同一份文件，避免重复复制。

涉及文件 + 数据库的关键写入继续采用 staging / temp / atomic commit / rollback 思路。

## 22. 导入与导出

### 导入

```text
TXT / EPUB
   ↓
Book
   ↓
Original Edition
   ↓
LogicalChapter / LogicalSegment
   ↓
加入书架
```

导入本身不自动创建 TranslationProject。

用户完全可以只把应用作为阅读器使用。

### 导出

以 Edition 为单位导出，不以 TranslationProject 为单位。

第一阶段继续支持：

- TXT
- EPUB

导出时使用 Edition 当前采用的 SegmentRevision。

原译双语 EPUB 是未来自然可扩展能力，但不是本期硬需求。

## 23. 删除语义

区分：

### 移出书架

仅设置 `hiddenFromShelf = true`，数据和文件保留，可恢复。

### 彻底删除

删除：

- Book
- Editions
- TranslationProjects
- Revision
- Memory
- Lexicon
- 正文 / 图片 / 缓存

彻底删除必须明确二次确认。

## 24. WebView / 找书接口预留

本期不实现 WebView，但预留 Acquisition 层。

来源概念：

- `LOCAL_FILE`
- `PASTED_TEXT`
- `WEB_CAPTURE`（预留）

未来 WebView 只负责把“用户已经看到的渲染后页面”转换成标准化内容：

```text
AcquiredBook
├── title
├── author?
├── cover?
├── sourceUrl?
├── chapters[]
│   ├── title
│   ├── sourceUrl
│   └── renderedText
└── assets[]
```

之后统一交给 BookImporter。

Reader、TranslationEngine 和 ContextEngine 不知道 WebView 的存在。

未来网页内容流程应为：

```text
WebView 渲染
→ 提取渲染后正文
→ 用户确认 / 标准化
→ 保存为本地 Book + Original Edition
→ 后续全部离线走统一 Reader / Translation 流程
```

本期不实现 SiteAdapter、脚本插件、XPath 规则系统等扩展。

## 25. 费用与可观测性

TranslationProject / TranslationRun 继续记录真实请求级统计，并扩展：

- input tokens
- output tokens
- cache hit tokens
- cache miss tokens
- normal estimated cost
- actual cost
- estimated cache savings
- translation reuse savings
- attempt count
- duration
- error category

CostPolicy 可支持：

- 无限制
- 单工程最高预算
- 每日预算
- 达到阈值暂停

Scheduler 在发起请求前进行粗略 Token / Cost 预测，避免明显超预算后才失败。

## 26. 测试与验收重点

### 数据模型

- Book 可在无 TranslationProject 时正常导入、阅读和导出。
- 一个 Book 可拥有多个 Edition。
- Derived Translation Edition 的 LogicalSegment 映射稳定。
- Revision 历史不会因自动任务被无痕覆盖。

### Reader

- 单击书架进详情，双击直接继续阅读。
- 原文 / 译文 / 对照 / 编辑切换保持逻辑位置。
- fallback 原文和译文可存在于同一个连续阅读流。
- 译文到达后热更新不造成明显位置跳跃。
- 手机 / 平板 / 横屏 / 折叠宽屏按 Window Size 自适应。
- 平板纯净模式无强制侧栏干扰。

### Translation

- 默认单章请求。
- 用户设置最多 2～5 章后严格遵守上限。
- Token 不足时只向下缩减 Batch，不向上增加。
- 单书严格串行，多书可并行。
- 单章过长才进入 Chunk fallback。
- 多章请求局部失败不整批重跑。
- Translation 成功而 Memory 失败时译文仍可提交。

### Context / Cost

- Lexicon 只注入相关词条。
- Story Memory 不每章重写完整资料。
- Prompt Cache 统计可追踪。
- 清理 cache 目录不丢失工程长期记忆。
- 同一 source Edition 的结构化分析可被多个翻译工程复用。

### 编辑与术语替换

- 快捷编辑生成 Revision。
- 修改词条默认不自动修改既有译文。
- 批量替换必须预览并由用户确认。
- 替换可撤销且有来源记录。

## 27. 实施边界

本设计是架构规格，不是实现计划。

实施时应分阶段推进，但必须保持上述边界，尤其禁止为了快速完成而重新把 Book、Edition、TranslationProject 合并成单一 Project 模型。

下一步应基于本规格编写详细实施计划，明确数据库 Schema、Repository / UseCase 接口、Reader Resolver、Context Engine、Translation Scheduler、UI 页面迁移顺序及测试策略。
