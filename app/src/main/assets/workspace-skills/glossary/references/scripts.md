# 术语取证脚本

需要字面扫描、词根枚举或字符关系线索时读取本参考。三个入口都通过 `run_project_skill` 执行，`skill_id` 为 `glossary`，`input_json` 是 JSON 对象字符串。脚本读取固定修订的完整工程条目快照，不依赖 Node、文件系统、网络或模块导入。

## 字面命中

入口 `scripts/main.js`。输入：

```json
{"patterns":[{"key":"candidate","text":"セラ","case_sensitive":false}],"max_matches_per_pattern":20}
```

调用：

```
run_project_skill {"skill_id":"glossary","entry_point":"scripts/main.js","input_json":"{\"patterns\":[{\"key\":\"candidate\",\"text\":\"セラ\",\"case_sensitive\":false}],\"max_matches_per_pattern\":20}"}
```

`patterns` 中 `key` 唯一，`text` 是保留原始空白的非空连续字面量，`case_sensitive` 必填，最多 64 个模式。`max_matches_per_pattern` 省略时保存全部证据，`0` 只统计，非负整数限制每个模式保存的字段证据数。

返回产物 `glossary-literal-matches.json`，用 `list_workspace_artifacts`、`read_workspace_artifact` 或工作区页面查看：

- 结果包含 `scanned_item_count`、去重的 `matched_item_count` 和按输入模式顺序排列的 `patterns`。
- 每个模式包含 `key`、`matched_item_count`、`field_item_counts`、`matches_complete` 和 `matches`。
- 每条字段证据为 `{ item_id, field, ranges: [{ start, end }] }`，按条目、正文 `src`、姓名 `name_src` 排列。范围是原字段的 UTF-16 半开区间，用原字段的 `slice(start, end)` 取回原文。
- 完整覆盖比较须确认各模式的 `matches_complete`。证据上限影响保存量，不影响完整计数。

脚本单次完整扫描 `source` 和 `name_source`，正文与姓名共同命中只计一个条目。规范化使用 NFKC、德语 ß 展开、希腊词尾 sigma 统一和字素到 UTF-16 原文范围映射；重叠命中保留。字素分割由 Android ICU 提供，跨 Android Unicode 版本的边界仍需设备对照。

### 邻近语境

当前工具没有 `queryItemContexts` 或 `@lg/workspace/item-contexts`。取得命中 `item_id` 后，用 `list_items` 的 `after_id` 分页取附近条目，再逐个 `read_item` 读取必要语境。不要伪造批量语境接口。

## 公共词根

入口 `scripts/literal-roots.js`。输入：

```json
{"forms":["ドトール家","ドトール領"]}
```

```
run_project_skill {"skill_id":"glossary","entry_point":"scripts/literal-roots.js","input_json":"{\"forms\":[\"ドトール家\",\"ドトール領\"]}"}
```

输入已确认语义相关的短词形，至少应有两种规范化后不同的写法。返回产物 `glossary-literal-roots.json`：`candidates: [{ root, grapheme_length }]`，保留首个词形的原始写法，按可见字符长度升序、等长时按发现顺序排列。词根枚举最坏复杂度较高，只适合短术语；超时或超预算即失败，不返回截断结果。

用字符重合寻找候选，再核验身份、译法及全部命中的安全性。

## 字符关系

入口 `scripts/relations.js`。输入为完整分析集合，每项包含唯一 `id`、非空 `src` 和布尔 `case_sensitive`。可从 `project_text_policy` 的 `TERM` 规则转换：

```json
{"entries":[{"id":"term-1","src":"セラ","case_sensitive":true},{"id":"term-2","src":"セラ王女","case_sensitive":true}]}
```

```
run_project_skill {"skill_id":"glossary","entry_point":"scripts/relations.js","input_json":"{\"entries\":[...]}"}
```

返回产物 `glossary-relations.json`，包含 `entry_ids` 和 `relations`，孤立对象仍保留在 `entry_ids`。关系包括：

|`reason`|字段与含义|
|---|---|
|`equivalent`|`entry_ids` 为两个存在规范化相等写法的对象，大小写按规则策略比较|
|`contains`|`entry_ids` 为 `[包含者, 被包含者]`，按被包含者的大小写策略判断|
|`shared_root`|`root` 为规范化公共片段，`entry_ids` 为成员集合。片段至少包含两个字素，且覆盖每个成员词形的至少一半。相同成员集合保留最长片段|

公共片段关系不传递，所有字符关系均需语义确认。结果直接引用对象 ID，按当前问题安排阅读范围并保存进度。核验字符关系后，将已确认的实体和语义关系加入关系图。

三个入口都受固定修订、输入/输出和隔离执行预算约束。结果超出宿主预算时失败，不报告为完整成功。规则变更通过 `prepare_text_policy` 准备完整策略替换，并由用户审批；`policy_proposal_status` 回执确认前不得声称规则已保存。
