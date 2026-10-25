---
name: writing-guide
description: 创作、续写、改写、润色文本，进行角色扮演，翻译或译名选择，判断文本表达质量时使用。
---

- 用户指令与已有上下文足以确定应加载资源时，直接读取对应资源，然后按其中要求执行。
- 如有需要可以读取多份资源，然后组合使用。

|请求类别|加载内容|
|---|---|
|所有任务|当前可用且尚未加载的 `writing-guide` 及全部 `writing-guide-` 前缀技能|
|创作、续写、改写、润色、角色扮演叙事或创作质量诊断|`references/creative-guide.md`|
|翻译、译名选择或译文审校|`references/translation-guide.md`|


## 原生 Android 资源读取

使用 `list_project_skill_files` 浏览本包，使用 `read_project_skill_file` 读取上表中的相对路径。本文是写作参考资料；当前用户要求和实际工具能力优先。本文不提供脚本入口，不需要调用 `run_project_skill`。
