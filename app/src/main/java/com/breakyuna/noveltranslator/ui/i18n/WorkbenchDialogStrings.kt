package com.breakyuna.noveltranslator.ui.i18n

/**
 * 工作台对话框文案（AI 分章 / 删除版本 / 章节操作）。
 *
 * 从 [WorkbenchUiStrings] 拆出：原类有 255 个字段，主构造器参数加 this 达到
 * 256 个槽位，超过 JVM 类文件规范的 255 上限——Robolectric 的 JVM 类加载直接
 * ClassFormatError（Android 的 dexer 更宽容，所以真机上不暴露）。拆出后主类
 * 237 字段，留足余量；新增文案优先放这里。
 */
data class WorkbenchDialogStrings(
    // AI chapter split dialogs
    val aiSplitIntroTitle: String,
    val aiSplitIntroBody: String,
    val aiSplitStart: String,
    val aiSplitRunningTitle: String,
    val aiSplitRunningBody: String,
    val aiSplitStop: String,
    val aiSplitPreviewTitle: (Int) -> String,
    val aiSplitPreviewMore: (Int) -> String,
    val aiSplitApply: String,
    val aiSplitDiscard: String,
    val cancel: String,

    // Edition delete / chapter action dialogs
    val deleteEditionTitle: String,
    val deleteEditionMessage: (String) -> String,
    val confirmDelete: String,
    val chapterActionTitle: (String) -> String,
    val chapterActionBody: (Int) -> String,
    val previewChapter: String,
    val retranslateChapter: String,
)
