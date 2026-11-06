---
name: translation
description: 翻译、补译、重译工程内 TXT/EPUB 文本，或检查、审校和修正工程译文时使用。
---

先加载当前可用且尚未加载的写作技能，包括 `writing-guide` 和所有以 `writing-guide-` 开头的技能。使用 `list_project_skills` 查看技能，使用 `read_project_skill` 读取主文，使用 `read_project_skill_file` 读取参考文档。

随后按任务需要读取资源：

- 用户指令与已有上下文足以确定所需资源时，直接读取并按其中要求执行。
- 任务对象不明时，用 `project_summary` 查看工程语言、资产、格式和修订，用 `project_statistics` 查看条目状态，再按 `list_items` 的元数据区分候选。足以选择后立即停止初探。
- 任务需要多份资源时，读取后组合使用。

## 原生工具

|用途|工具|
|---|---|
|工程范围、资产、语言与修订|`project_summary`|
|整体状态计数|`project_statistics`|
|条目分页与元数据|`list_items`（`after_id`、`limit`、`status`）|
|读取单条原文、译文与指纹|`read_item`（`item_id`）|
|字面搜索姓名、术语与片段|`search_items`（`text`、`limit`、`status`）|
|读取术语和保护规则|`project_text_policy`|
|准备条目译文/状态变更|`prepare_item_changes`，随后用 `item_proposal_status` 核对回执|
|准备完整规则或提示词替换|`prepare_text_policy`（`base_fingerprint` + 完整 `policy_json`），随后用 `policy_proposal_status` 核对回执|
|读取技能参考|`read_project_skill_file`|

不存在 `ws.contract`、`ws.contract.datasets`、`ws.contract.changes`、`batch_item_translation`、`queryItemContexts`、`workspace_run` 或 `apply` 契约。不要引用或伪造这些接口。PDF 页流程已冻结：本技能不读取或提交 PDF 页面，不使用 `pages` 数据集、页码流程或页面提案工具。

## 任务分工

|任务对象|翻译任务|审校任务|对应文件格式|
|---|---|---|---|
|`items`|`references/item-translate.md`|`references/item-review.md`|TXT、EPUB 及其他导入为条目的文本格式|

PDF 资产不属于本流程。工程内 EPUB 的翻译以导入后的条目为目标，结构阅读按 `epub` 技能；不要声称读取打包 EPUB 的内部成员。

## 执行顺序

1. 用 `project_summary` 和 `project_statistics` 确认工程身份、语言、资产、修订和目标状态。
2. 用 `list_items` 按资产和条目顺序分页；用 `read_item` 读取目标正文、现有译文和当前指纹；用 `search_items` 补充姓名、称谓、术语和上下文线索。
3. 按对应参考文档形成完整译文或审校结论。需要规则变更时，先读取 `project_text_policy`，再按用户要求使用 `prepare_text_policy` 准备完整替换；不要直接修改规则。
4. 需要写回条目时，用当前 `fingerprint` 构造 `prepare_item_changes` 提案。未收到 `item_proposal_status` 的已提交回执前，不得声称译文已经保存。
5. 交付时说明实际读取范围、拟议变更、已提交结果、未决事项和未覆盖范围。

术语与保护规则的提取、整理分别使用 `glossary` 和 `text-preserve` 技能；本技能只消费当前已保存的策略，不代替用户审批规则提案。
