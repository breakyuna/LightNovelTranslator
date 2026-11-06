---
name: epub
description: 读取或分析工程内 EPUB 的章节顺序、XHTML、目录与资源关系；工程外独立 EPUB 的打包受原生工具限制。
---

# EPUB 文件处理

工程内的翻译与审校按 `translation` 执行。本技能用于阅读 EPUB 结构、补充语境，以及用户明确要求的工程外独立文件。

## 原生工具与读取边界

- 用 `project_summary` 查看资产身份、路径、格式、顺序、启用状态和条目数；用 `list_items`、`read_item`、`search_items` 读取导入后的条目与正文。
- 用 `list_workspace_materials` 查看当前固定修订的只读材料；用 `read_workspace_material` 按不透明 `material_id` 读取严格 UTF-8 文本。
- `read_workspace_material` 不返回二进制内容。打包的 EPUB 是 ZIP 二进制，不能通过它读取 `META-INF/container.xml`、OPF、XHTML 或图片成员；本技能不提供 ZIP 解包、路径解析或成员遍历工具。
- `read_project_skill` 与 `read_project_skill_file` 只读取技能正文和参考文档，不读取工程资产。
- 不存在 Node、`node:fs/promises`、`node:path`、`jszip`、npm 模块或任意文件系统写入能力。不得承诺这些能力，也不得伪称已读取或生成压缩包。

## 阅读工程内 EPUB

### 1. 确认资产与条目顺序

先调用：

```
project_summary {}
```

从返回的 `assets` 中选择 `format` 为 EPUB 的资产，记录 `id`、`path`、`ordinal`、`enabled`、`items` 和 `sha256`。再用 `project_statistics` 查看整体条目状态。

按资产顺序分页读取条目元数据：

```
list_items {"limit":50}
list_items {"after_id":12345,"limit":50}
```

返回项包含 `item_id`、`row`、`status`、`asset_id`、`slot`、`source_chars`、`translation_chars` 和 `fingerprint`。`row` 与 `slot` 用于判断导入后的章节和条目顺序；需要正文时逐条调用：

```
read_item {"item_id":12345}
```

`read_item` 返回 `source`、`translation`、`status`、`revision`、`asset_id`、`slot` 和 `fingerprint`。不要根据文件名猜测章节顺序，也不要声称读取了 OPF `spine`；工程内以资产顺序和条目顺序为可验证依据，必要时用 `search_items` 核对目录标题：

```
search_items {"text":"第一章","limit":20}
```

### 2. 读取可用的文本材料

当前修订的材料清单包含工程启用的文本资产和 `datasets/assets.jsonl`。调用：

```
list_workspace_materials {}
```

按返回的 `id`、`kind`、`path`、`media_type`、`size` 和 `sha256` 选择文本材料，再读取：

```
read_workspace_material {"material_id":"<opaque-id>"}
```

只处理严格 UTF-8 且不超过读取上限的文本。二进制资产、压缩包和成员文件不会被解码；遇到这类材料时改为读取工程导入后的条目，并明确说明限制。

### 3. 结构与语境

- 资产 `ordinal` 决定文件之间的先后；`list_items` 返回顺序和 `slot` 决定文件内条目顺序。
- `project_summary` 的 `source_language`、`target_language`、`write_translated_names` 等设置用于理解工程语言和姓名输出，不替代原文证据。
- 目录标题、章节标题和正文引用通过 `search_items` 定位，再用 `read_item` 读取邻近条目，不能把搜索结果当作完整目录。
- 原书 `container.xml`、OPF `manifest`、`spine`、NCX 或导航 XHTML 的内部关系，只有在用户提供可读的文本副本时才可按文本分析；对打包 EPUB 本身，必须说明原生工具无法验证这些成员。
- 图片、字体、样式和脚注若未以文本材料提供，只能依据导入条目中的可见引用和用户补充材料判断，不推断二进制内容。

## 工程外独立 EPUB

原生工程没有 ZIP 写入、Node 运行时或 `jszip`，因此不能从工作区直接生成或重新打包 `.epub`。需要独立文件时：

1. 只处理可读的文本内容，例如从工程条目导出的 XHTML 文本、用户提供的解包文本或 `work/` 下的文本草稿。
2. 需要修改工作区文本时，用 `prepare_workspace_changes` 的脚本在 `work/` 下生成变更提案；`sources/`、`datasets/` 和二进制资产只读。脚本提交前核对当前工程修订；更新已有文件时，在变更项中提供当前 `baseSha256`，可从只读的 `workspace-files.jsonl` 材料核对。
3. 明确告知用户：原生工具只能准备文本，最终 ZIP 打包、`mimetype` 写入和 EPUB 校验必须在工程外完成；不要声称已生成 `.epub` 文件。

如果用户提供了已解包的文本成员，可按相对路径阅读并保留以下结构约定：

- `mimetype` 必须是无压缩的第一项，内容严格为 `application/epub+zip`。
- 保留 `META-INF/container.xml` 指向的 OPF 路径，以及 `manifest`、`spine` 和资源 `href` 的相对关系。
- 修改 XHTML 时保留文档结构、注音、图片、脚注、链接和目录与正文顺序。
- `href` 相对 OPF 所在目录解析；URL 片段与百分号编码分开处理，只解码一次，解析后的路径不得离开书籍目录。

## 结果与核验

交付时区分三类事实：

- 工程内已读取的资产、条目和文本材料，附 `asset_id`、`item_id`、修订和指纹。
- 用户提供的解包文本，附相对路径和来源说明。
- 未能读取的 ZIP 内部结构或二进制资源，列为限制，不补写猜测内容。

若用户要求核对最终 EPUB，说明原生工具无法解包验证；请用户在工程外重新打开，核对 `mimetype`、入口、章节顺序、目标正文和资源引用。排版相关任务还需结合实际阅读效果核验。
