# 术语字面命中调查

核验候选术语在工程正文与姓名中的命中、计数和原文范围时使用。先按需要读取 writing-guide 的译名指南，字面命中仅提供候选，不能替代语境和实体身份判断。用 `list_project_skills` 查看技能，用 `read_project_skill`/`read_project_skill_file` 读取写作指南与本包参考文档。

|任务|参考|
|---|---|
|提取术语、发现遗漏、整理实体与关系|`references/extract.md`|
|判断收录资格、身份、字段、词根与覆盖|`references/rule.md`|
|字面命中、关系与词根脚本调用|`references/scripts.md`|

调用 `run_project_skill`，skill_id 为 `glossary`，入口 `scripts/main.js`，input_json 为对象字符串。执行前确认该技能已启用：

```json
{"patterns":[{"key":"example","text":"候选术语","case_sensitive":true}],"max_matches_per_pattern":20}
```

每个 key 必须唯一，text 非空且 case_sensitive 是布尔值。最多 64 个模式。max_matches_per_pattern 省略表示完整证据，0 只统计，非负整数限制每个模式收集的字段证据数。完整计数始终扫描全部固定修订条目。正文与姓名分别统计，同一条目按模式只计一次。

结果产物为 glossary-literal-matches.json。证据上限影响 matches_complete，不影响完整命中计数。产物超过宿主预算时失败，不报告为完整成功。不使用网络，不写工程规则。

规则变更必须通过原生策略审批：先用 `project_text_policy` 读取完整策略和基准指纹，再用 `prepare_text_policy` 提供 `base_fingerprint` 和完整 `policy_json`，准备包含全部现有规则与新 `TERM` 规则的完整替换；用户审批后用 `policy_proposal_status` 核对回执。未收到提交回执前不得声称规则已保存。`prepare_text_policy` 一次替换完整策略，不能只提交差异。

使用 NFKC、德语 ß 展开、希腊词尾 sigma 统一及字素到 UTF-16 原文范围映射；重叠命中保留。字素分割由 Android ICU 提供，跨 Android Unicode 版本的边界仍需设备对照。另外两个入口接受同样的 input_json 对象字符串：

- `scripts/relations.js`：传入 entries 数组，每个对象包含 id、src、case_sensitive。报告 glossary-relations.json 保留全部输入 ID，给出等价、包含及公共词根调查线索，不把字符关系当成实体关系。
- `scripts/literal-roots.js`：传入 forms 字符串数组，至少两种规范化后不同的词形。报告 glossary-literal-roots.json 按字素长度排序公共连续片段候选，不拆开组合字符或代理对。

三种入口均受固定修订、输入/输出和隔离执行预算约束。词根枚举适用于短术语；超时或超预算即失败，不返回截断结果。
