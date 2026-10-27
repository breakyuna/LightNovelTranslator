# 文本保护扫描

需要核验表达式或查找相同正则表达式时读取本参考。原生入口使用 `run_project_skill`，`skill_id` 为 `text-preserve`，入口 `scripts/main.js`，通过 `input_json` 传入 JSON 对象字符串。脚本读取固定修订的完整工程条目快照，不依赖 Node 文件系统或模块导入。

## 运行扫描

```json
{"patterns":[{"key":"player","source":"\\{player_name\\}"},{"key":"bracket","source":"\\[[^\\]\\n]+\\]"}],"max_matches_per_pattern":20}
```

调用：

```
run_project_skill {"skill_id":"text-preserve","entry_point":"scripts/main.js","input_json":"{\"patterns\":[...],\"max_matches_per_pattern\":20}"}
```

将示例表达式替换为当前方案。每个 `key` 必须唯一，`source` 是 JavaScript 正则源码；最多 64 条模式。需要限制证据量时传入 `max_matches_per_pattern`：

- 省略时保存全部命中证据。
- `0` 只统计数量。
- 非负整数限制每个模式保存的命中数；计数和分布始终覆盖完整输入。

结果以 `text-preserve-scan.json` 产物发布，用 `list_workspace_artifacts`、`read_workspace_artifact` 或工作区页面查看。输出超出宿主预算时本次失败，不返回截断的成功结果。

## 结果字段

返回 `scanned_item_count` 和按输入顺序排列的 `patterns`。每个模式包含 `key`、`source`、`error`、`match_count`、`matched_item_count`、`zero_length_match_count`、`matches`、`matches_complete` 和 `file_counts`。

- `error` 为编译错误文字或 `null`。非法正则的 `matches_complete` 为 `false`；合法表达式仅在所有命中均已保存时为 `true`。
- 每条证据包含 `item_id`、`file_path`、从 1 开始的 `line`、行内 UTF-16 半开范围 `start`/`end` 和 `text`。
- `file_counts` 按命中次数统计；`matched_item_count` 按条目计数。
- 零长度命中同时纳入命中总数和零长度计数；重叠命中保留。

详细契约见 `references/scan-contract.md`。扫描会独立核验每条规则，实际保护执行会处理重叠并忽略空片段，因此调查单条表达式时应使用独立扫描结果。

取得位置和计数后，还需判断保护资格、自然语言反例和边界安全。安全结论只覆盖已检查的材料。遇到执行超时，按已保存材料缩小问题范围并修正表达式。

## 查找相同表达式

先用 `project_text_policy` 读取完整策略，按 `PROTECT` 规则的 `source` 分组；成员使用规则 `id`。相同源码说明表达式写法重复，具体行为关系仍需结合扫描结果判断。不要从 `ws.contract.datasets.text_preserve` 读取规则数据集，也不要使用 Node 的 `readFile`。

需要比较多个表达式的命中时，把它们的源码分别放进同一次 `patterns` 输入，例如：

```json
{"patterns":[{"key":"a","source":"<br\\s*/?>"},{"key":"b","source":"<[^>]+>"}],"max_matches_per_pattern":0}
```

用完整计数和逐文件分布判断覆盖差异，再对候选规则取得完整位置证据。最终规则集通过 `prepare_text_policy` 准备完整策略替换；`policy_proposal_status` 回执确认前不得声称规则已保存。
