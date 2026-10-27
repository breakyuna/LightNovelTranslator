# 文本保护规则调查

调查原稿中的控制标记、核验保护规则表达式、统计逐文件命中及定位漏保护时使用。保护规则应围绕格式标记，不把自然语言内容整体屏蔽。扫描只提供证据，不自动决定保护资格。

使用 `run_project_skill`，`skill_id` 为 `text-preserve`，入口 `scripts/main.js`。执行前确认该技能已启用。通过 `input_json` 传入 JSON 对象，例如：

```json
{"patterns":[{"key":"bracket","source":"\\[[^\\]\\n]+\\]"}],"max_matches_per_pattern":20}
```

- 每条模式有唯一非空 key 和字符串 source，最多 64 条。按各行独立使用 JavaScript `giu` 正则。
- `max_matches_per_pattern` 省略时保存全部证据，0 只统计，非负整数限制每条模式的证据数；计数始终覆盖完整工程。输出超出宿主预算时本次失败，不伪报完整结果。
- 无需网络；不启用 allow_fetch。
- 返回产物 `text-preserve-scan.json`，用 `list_workspace_artifacts`、`read_workspace_artifact` 或工作区页面查看/保存。
- 所有调查绑定当前工程修订。不会自动修改工程规则或译文。
- 规则修改通过原生策略完成：先用 `project_text_policy` 读取完整策略和基准指纹，再用 `prepare_text_policy` 提供 `base_fingerprint` 和完整 `policy_json` 准备替换，由用户审批；用 `policy_proposal_status` 核对回执。未收到提交回执前不得声称规则已保存。

## 参考文档

|任务|参考|
|---|---|
|提取保护结构、发现遗漏、整理规则与覆盖关系|`references/extract.md`|
|判断保护资格、字段、表达式安全和覆盖判据|`references/rule.md`|
|脚本调用、输入输出与产物字段|`references/scripts.md`、`references/scan-contract.md`|

用 `read_project_skill_file` 读取上述路径。`references/scan-contract.md` 是原生扫描结果契约；脚本逻辑位于 `scripts/main.js`，仅按静态文本检查，不在文档流程中改写。
