# LightNovelTranslator 深度审计报告

日期：2026-08-25
主题：代码冗余排查与导入/阅读加载性能分析

## 1. 未使用的死代码与冗余组件

在审计翻译模块和 UI 组件时，发现以下文件和模块为**废弃残留的死代码**，可以直接删除：

### 1.1. 旧版翻译引擎 (`core.translator`)
整个 `core.translator` 包已经废弃，被新版的 `core.translation` 和 `core.task`（如 `BookTranslationEngine` 和 `TranslationTaskManager`）所取代。包含文件：
- `TranslationManager.kt`（长达 1579 行的旧版核心逻辑，含 `TranslationJobState`）
- `TranslationQualityValidator.kt`
- `StableSegment.kt`

> **关联发现：** `AppViewModel` 中存在大量包装 `translationManager` 的旧方法（如 `startContinuousTranslation`、`translateRange`、`pauseTranslation` 等），这些方法也应一并清理。

### 1.2. 旧版翻译 Runner 屏幕
对应旧引擎的 UI 界面也已不再使用。包含文件：
- `TranslationRunnerScreen.kt`（包含翻译的旧版控制台与进度展示，579 行代码）
- 在 `AppNavigation.kt` 和 `AppDestination.kt` 中关联的 `AppDestination.Translation` 路由。

这些旧模块与当前的 `BookWorkbenchDetailScreen` 和 `ProjectWorkspaceScreen` 在职责上重叠，属于 V1 版本的历史遗留。

## 2. 性能瓶颈分析：导入与阅读加载缓慢

针对用户反馈的“书籍导入和阅读加载时间较长”，审计发现以下根源，并提出相应的优化建议：

### 2.1. 书籍导入阶段 (`BookImporter.kt`)
**问题：大量零碎的 SQLite 事务（N+1 Transaction Problem）**
在解析并持久化书籍章节（`persistTxtStreaming` 与 `persistNormalized`）时，`BookImporter` 在循环 `while (chapters.hasNext())` 内直接调用了 `persistChapter`，而 `persistChapter` 内部包含了独立的 `database.withTransaction`。
- 如果一本书包含 1000 个章节，会导致 1000 次数据库事务的开启与提交。
- `withTransaction` 伴随协程上下文切换，开销巨大。

**优化建议：**
- 将整个导入流程（或批次章节，比如每 100 章）包裹在**单一的外部 `withTransaction`** 中。
- 利用 Room 的批量插入（`@Insert(onConflict = OnConflictStrategy.REPLACE)` 支持 `List` 传参）代替循环中的单条 Insert。

### 2.2. 阅读器加载阶段 (`PlatformReaderScreen.kt` / `BookPlatformRepository.kt`)
**问题：主内存全本拉取阻塞响应（Missing Pagination / Lazy Loading）**
在 `BookPlatformRepository` 的 `resolveReader(inputs: ReaderInputs)` 方法中：
- 每次打开阅读器，不论跳转到哪个章节，应用都会去查询整本书的全部数据：
  - `getLogicalSegmentsByBook(book.id)` 获取整本书的所有段落（可能高达数万行）。
  - `getEditionSegmentsByEditions` 获取翻译版本所有的段落映射。
- 随后使用 `groupBy` 和 `associateBy` 在内存中构建巨大的 `HashMap` 与 `List` 以交叉对齐双语段落。
- 这不仅直接阻塞了协程（影响加载耗时），还可能导致大内存占用甚至 OOM（内存溢出）。

**优化建议：**
- **分页查询 / 按章节加载**：`resolveReader` 的查询条件应当加上**当前可见（或预加载）的章节 ID 或区间**。
- Dao 中应新增按 `logicalChapterId` 获取 `LogicalSegments` 和 `EditionSegments` 的接口，而不是用 `getLogicalSegmentsByBook` 拉取全集。
- 只有在翻页到新章节时，再去请求相邻章节的内容。

## 3. 结论

- **减负**：建议彻底清理 `core.translator` 包与 `TranslationRunnerScreen` 相关的千余行废弃代码。
- **提速**：必须重构数据库的访问模型，对 `BookImporter` 应用批处理事务，对阅读器的数据层应用懒加载（按章获取段落），这两项修改将解决绝大部分的性能卡顿问题。