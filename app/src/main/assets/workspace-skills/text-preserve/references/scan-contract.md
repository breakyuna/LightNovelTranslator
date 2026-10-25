# 扫描结果契约

迁移自 LG `builtin/skills/text-preserve/scripts/scan-patterns.mjs`。原生入口把固定修订 JSONL 中的 source 映射为 src、保留 item_id 和 file_path，再同步逐条扫描；无 Node 文件系统和模块导入。

结果包含 scanned_item_count 和按输入顺序排列的 patterns。每个模式返回 key、source、error、match_count、matched_item_count、zero_length_match_count、matches、matches_complete 和 file_counts。非法正则保留编译错误，其余模式继续扫描。

matches 中的 line 从 1 开始，start/end 是行内 UTF-16 半开范围。match_count 与 file_counts 按次数统计，matched_item_count 按条目统计。零长度命中计入总数；Unicode 游标前进由 matchAll 处理。matches_complete 仅在没有编译错误且所有命中证据均已保存时为 true。

该脚本使用 JavaScript 正则调查；原生规则执行的正则引擎仍需独立核对，不以此报告代替 API 24/34 设备规则验收。病态表达式受隔离脚本执行超时约束。
