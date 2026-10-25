# 文本保护规则调查

调查原稿中的控制标记、核验保护规则表达式、统计逐文件命中及定位漏保护时使用。保护规则应围绕格式标记，不把自然语言内容整体屏蔽。扫描只提供证据，不自动决定保护资格。

使用 `run_project_skill`，`skill_id` 为 `text-preserve`，入口 `scripts/main.js`。通过 `input_json` 传入 JSON 对象，例如：

```json
{"patterns":[{"key":"bracket","source":"\\[[^\\]\\n]+\\]"}],"max_matches_per_pattern":20}
```

- 每条模式有唯一非空 key 和字符串 source，最多 64 条。按各行独立使用 JavaScript `giu` 正则。
- `max_matches_per_pattern` 省略时保存全部证据，0 只统计，非负整数限制每条模式的证据数；计数始终覆盖完整工程。输出超出宿主预算时本次失败，不伪报完整结果。
- 无需网络；不启用 allow_fetch。
- 返回产物 `text-preserve-scan.json`，用 `list_workspace_artifacts`、`read_workspace_artifact` 或工作区页面查看/保存。
- 所有调查绑定当前工程修订。不会自动修改工程规则或译文。需要修改时先生成规则差异提案，由用户审批。

字段和边界见 `references/scan-contract.md`，通过 `read_project_skill_file` 读取。
