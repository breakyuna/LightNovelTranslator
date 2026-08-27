## 2024-05-24 - 添加 Icon 的 contentDescription 以提升无障碍支持
**经验：** 在 Compose 界面中，当 IconButton 的唯一可见子元素是一个 Icon 时，如果不为该 Icon 提供 contentDescription (即设为 null 或者使用了无效/不一致的硬编码文本例如 "清空")，屏幕阅读器将无法正确朗读按钮功能，造成无障碍使用上的困扰。
**行动：** 在设计或修复具有功能性的 IconButton 时，必须确保其内部的 Icon 具有正确的、多语言化的 contentDescription（例如 `strings.clearSearch`）。后续应该继续搜查并修复类似传入 `null` contentDescription 的交互元素。
