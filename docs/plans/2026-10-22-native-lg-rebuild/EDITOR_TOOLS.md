# TXT 编辑定位与草稿查找替换

当前进度与验证边界统一见 [HANDOFF §3、§49](HANDOFF.md)。本专题相关源码已纳入 `2643b2c` / Actions #146 的 test/lint/release 成功基线；新增设备/视觉、真实供应商与 Room schema 导出仍待补齐。

当前源码进度见 [HANDOFF §46—47](HANDOFF.md)，算法与列表范围见 [TEXT_QUALITY](TEXT_QUALITY.md) / [QUALITY_SCAN](QUALITY_SCAN.md)。相关用例已由 Actions #146 覆盖；本机未构建、测试或 Lint，新增设备验收仍缺。

## 上一条与下一条

`Editor → NativeProjectViewModel.navigateEditor → TextItemNavigationPort.adjacentTextItem`。

普通模式复用状态/原译字面查询的同一 SQL 谓词，在单次读事务内核对编辑基线修订，按实际 ID 正/倒序各取一条，不取相邻行号或只在当前 40 条页内跳转。查询值仍参数绑定，不解释 SQL 通配符/正则。质量结果模式按完整快照筛选 ID 邻接定位，重新读取完整条目并核对修订/指纹；旧索引禁用跳转。

没有相邻匹配项时保留当前草稿并显示边界提示。未保存草稿需显式确认放弃，取消保留草稿；跳转不调用保存、不产生新修订。进入新条目后重建编辑基线与命令、清除质量和替换预览。关闭编辑器回到原来固定页面，不因跨页阅读偷偷改变原选区。

## 当前译文草稿的字面查找替换

`Editor → NativeEditorReplacementDialog → prepareEditorReplacement → TextLiteralReplacer.preview → applyEditorReplacement → NativeEditDraft.changed`；最终保存仍走既有 `saveEditor → TextProjectPort.edit → ProjectWriteStore`。

只操作当前打开条目的完整译文草稿。用户输入查找/替换文本及大小写策略，显式预览后查看全部匹配次数和结果开头最多 240 字符；完整结果没有截断。匹配不重叠，不解释正则、美元捕获或反斜线替换。支持空替换与空白/换行查找，拒绝空查找。

预览计算在 Default 执行，可取消；用 RE2/J 字面匹配和可计数输入，查找最多 256、替换最多 4,096、输入/结果每侧最多 65,536 UTF-16 单元，NUL/损坏 Unicode、预算和结果膨胀失败不发布截断结果。预览绑定 Project/Instance/Generation 与不可变草稿命令，记录原文字指纹；改变查找参数后隐藏不匹配预览，修改草稿文字或状态后清除预览。只有当前仍持有的同一预览对象、相同身份/命令且确有变化才可应用。

应用只更新草稿与其保存命令，标记已处理并清除旧质量结果；预览/应用均不改数据库、不推进工程修订、不调用模型。用户确认后还须点击编辑器“保存”，执行层仍核对修订/条目指纹及持久终局回执。取消、无匹配或相同内容替换不会隐式保存。预览全文不进入 SavedStateHandle；应用后的普通编辑草稿继续沿用既有恢复机制。

这不是“当前选择/全部筛选结果”的工程级查找替换，没有跨条目原子差异审批、撤销或批量修复。完整范围保留在 UI_SPEC / FEATURE_MATRIX，不以当前单条切片代替。
