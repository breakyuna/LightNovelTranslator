# 原生 LG 重构交接记录

最后更新：2026-10-23。

这是本轮全面重构的唯一进度记录。§7 起的开发轮记录保留当时的源码与验证事实；其中“未提交/未构建/待运行”等历史状态不得覆盖 §3 和最新 CI 结论。目标见 [README](README.md)，任务定义见 [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md)，功能范围见 [FEATURE_MATRIX](FEATURE_MATRIX.md)。旧 2026-10-19 计划的完成状态不自动继承。

## 1. 初始规划轮结论（历史）

- 用户要求彻底重构 LNT，包括 UI，全面实现 LG Termux 功能；只保留 LNT 的 Koog Agent 框架。
- 用户追加说明：`AGENTS.md` 冲突条款也以本次目标为准。已同步项目定位、UI/导航、数据和业务边界、工作方式与验收口径，保留本机验证限制及数据保护规则。
- 初始规划轮只编写规划与同步根规则；用户随后已授权“开始修改”，实施与后续修订记录见第 7 节起的各轮记录。
- 已建立六份 Markdown 文档和一份源码基线 JSON：目标、架构、98 项功能矩阵、15 页面/系统界面 UI 规格、40 项实施任务、交接及源码指纹。
- “仅保留 Koog”已细化到源码入口和必须解除的旧依赖；新业务不会继续接入旧 AppViewModel、章节/Edition 引擎或旧 UI。

## 2. 源码与验证基线

| 对象 | 本轮开始时状态 | 说明 |
|---|---|---|
| LNT | `refactor/translation-agent-2026-10` / `4bd2ae0`；22 个已有修改 | 全部保留；本次在其中的 AGENTS 上追加目标同步 |
| LG | `termux-port` / `23796556`；67 个修改 + 12 个未跟踪文件 | 只读参照，不编辑、不提交 |
| 规划时 CI 证据 | [run 39375817891](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39375817891) | 旧已提交版本 test/lint/release 成功，不覆盖规划时脏代码和新目标 |
| 源码指纹 | `SOURCE_BASELINE.json` | 保存规划前状态，检测漂移；不包含完整 diff，不能代替实施快照 |

本轮实际新增：本目录全部文件。本轮已有文件编辑：根 `AGENTS.md`。根 README、旧计划、产品源码、测试、数据库和 LG 仓库均未由本轮修改。

## 3. 实施状态

当前已提交且通过 CI 的代码基线为 `refactor/translation-agent-2026-10` / `8e74fff`（2026-10-23 覆盖 §34—40 源码）；[Actions #136 / 39949770529](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39949770529) test/lint/release 全部成功，1010 项用例 0 失败。此前 `f766cee` 的 #132 仍是 §28—32 的证据；§33 记录当次 887 项结果，§41 记录本次推送、三次失败修复与最终全绿。代码 CI 后已提交 docs-only 交接 `b1f61c4`；§42 文档修正轮从该干净基线开始；后续未提交产品源码见 §43—47，尚未构建；§34—40 不再是“未提交/未构建”。设备、供应商、schema 2/3 导出与截图证据仍未取得，不能继承 CI 全绿关闭 S1/S2。

| 范围 | 当前状态 | 实际证据 / 未完成 |
|---|---|---|
| R00 基线与行为索引 | 已建立，细项继续核对 | 194 条消费者定位、98 个功能 ID 均有可追踪来源；不等于 98 项已实现或验收 |
| R01 模块装配 | CI 构建已通过，完整 M1 未通过 | 新模块可编译并产出签名 APK；依赖锁定、完整 G1—G5 和设备边界仍需补齐 |
| TXT 工程与人工校对 | 手工切片已获 CI/用户真机确认 | 创建、导入/复制、编辑、保存重开、四种导出与原生重导入见 §22；不扩展为所有存储故障/格式验收 |
| TXT 校对查询与批量操作 | §37—38 已构建并通过 CI，设备验收待补 | 状态/原译查询、统计、窗口成员保留、本页批选/状态/清空与原子回执已由 Actions #136 覆盖；§45—47 新增单条检查、已保存译文分页扫描/警告与缺失术语筛选统计、相邻匹配定位及单条草稿替换源码未构建；跨页批写/全查询替换/完整质量兼容仍缺 |
| S1 翻译 | 既有写回/持久记录与 §34—39 前台执行/整工程源码均有 CI | 当前页/整工程显式选择、分页读取/分批提交、跨页失败/停止、Token 事实分离已有运行用例；完整模拟设备流程未验收；§43 新增远程 TXT/持久回执源码未构建，不能关闭 S1 |
| S2 原生 Agent | 既有对话/单条审批、HTTP/模型发现、SSE/表单及 §39 调度/派发接线均有 CI | Koog 工具循环、结构化历史、可选凭据、正文/思考预览、取消回执及共享调度边界已有夹具测试；真实供应商、KeyStore 真机和 Agent 端到端设备流程未验收 |
| 应用存储与审批 | Room v3 与迁移/中断核对已有 CI；§43 源码 v4 未构建 | v1 升级链、v2→v3 和审批冲突/回执用例通过；2.json/3.json 导出仍缺；§43 增加远程请求表和 v3→v4 迁移，4.json 同待生成；设备升级、全对象/部分接受仍待完成 |
| R02/R06 运行与后台 | 进程内协调及持久记录有 CI；前台 Service 已由 CI 构建 | §34—35 应用级任务、通知停止、UI 重订阅和宿主生命周期隔离已编译并跑组件用例；真机前台/通知/重建、Agent 前台化、队列、暂停/同 Run 跨进程接续仍未验收 |
| R30—R35 原生界面 | TXT 手工页已实机确认，Agent/模拟/校对增量有 CI | app 704 项组件/单元用例通过，含横屏小视口、模型选择/搜索/取消、校对筛选与批选；仍无 native PNG 产物，完整管理页、多设备视觉/键盘/设备交互仍缺 |
| R14 规则与提示词 | §44 新增源码未构建 | `.lnt` v2 规则/提示词、事务回执、原生页和远程文本流水线已接源码；v1 升级/混合历史/RE2/J/设备及完整 LG 规则交换仍待验证或实施 |
| R11 共享推理调度 | §39 已构建并通过 CI | Agent/翻译共用 FIFO、固定并发、RPM/隐藏 RPS、停止检查及派发登记边界已覆盖；§43 增加远程翻译持久 Token 记录源码，未构建；完整 Key 池、自动探测、费用/跨 Agent 账本和协议全集仍缺 |
| R03/R05/R20—R29 全格式/工作区 | 主要业务未落地；§39 字幕纯文本核心已通过 CI | SRT/LRC/ASS/SSA 解析/写回 17 项用例运行通过，但尚未接入工程资产；VTT、.lg/Pi、PDF/EPUB/游戏格式、脚本/技能和联网工具仍待实现 |
| R36—R39 升级交付 | 字段映射样本已写，正式迁移未开始 | 旧数据保留，未迁移/删除或完成旧主链路退役；完整升级、性能和全功能验收未完成 |

整体已推进到 TXT 手工闭环已实机确认、模拟翻译/校对批选/Agent/持久记录/流式/调度切片可构建且 CI 全绿的阶段。完整 S1/S2、98 项功能矩阵和 G1—G5 仍开放，不按源码行数或局部测试数计算完成百分比。具体测试数量见 §33 与 §41，不能用 CI 全绿替代供应商、设备或完整产品验收。

## 4. 下一轮顺序

1. 补齐权威 Room v2/v3 schema 导出并审阅；本次 #136 仍只产出 1.json，且无 native PNG（Roborazzi 录制未开启），需在 PC/CI 专门生成后查看。已有运行迁移测试不代替导出 schema、截图与设备升级证据。
2. 验收模拟翻译、Agent 多会话/审批/中断核对、模型发现、SSE/思考/工具提示/停止/断网，以及横屏/小屏/键盘和 KeyStore 的设备闭环；真实模型联调与付费调用须另有明确授权。
3. 在持久执行记录基础上推进 Agent 应用级前台执行、持久策略检查点、原 Run 执行接续、请求调度/用量账本及完整审批与工具；当前重启核对不自动执行或重发；本地模拟前台 Service 已有 CI，后续补设备验收与 Agent 应用级执行。
4. 按原计划推进复杂格式、.lg/Pi 兼容、脚本/技能工作区、规则与完整管理 UI、旧数据迁移，维持 G1—G5 和全量验收门槛。
5. 后续源码开发可按实际授权继续，但未取得的设备/供应商证据不得登记为完成；§33 轮仅核对 Actions/同步文档；用户随后授权继续推进，源码增量见 §34—40，后续 CI 证据见 §41。

## 5. 待原型解决的决策

| 决策 | 默认方向 | 形成最终决定的任务 |
|---|---|---|
| Koog 检查点/序列化与依赖 | 保留 1.3.0 接入，补应用执行记录，不继承旧消息存储限制 | R02 |
| 最低 Android 版本 | 从 API 24 保持起步，不用 Manifest override 掩盖真实不可用 | R02/R05/R06 |
| 脚本引擎 | QuickJS 首个候选，独立受控能力桥；不运行 Node 业务服务 | R03 |
| 工作区隔离与停止 | 隔离 Service 原型，真实拒绝/取消/回收证明后定型 | R03/R06 |
| .lg 与 .lnt/Pi 历史双向映射 | 原生 .lnt；.lg 经兼容层双向转换，框架私有执行状态分开 | R04 |
| PDF/RGSS/EPUB 底层库 | 真实读写与输出后锁定版本/ABI/资源 | R05 |
| 文件/内存/展开预算 | 目标设备测量后冻结，不直接复用 Web 暂存上限 | R06 |

以上是有具体解决入口的未决技术项，不是功能缩减授权。任何未通过的项继续阻止完整版本验收。

## 6. 初始规划轮验证与交付（历史）

已执行的静态核对：

- 六份 Markdown 的相对链接、代码围栏、空白、不可见字符与基线 JSON 结构。
- 98 个唯一功能 ID、R00—R39 的 40 个任务定义、全部任务引用及依赖无环。
- 功能矩阵中的任务映射、15 个 UI 条目及引用的 LG 代表源码路径存在性。
- 根规则旧定位/导航/UI/章节约束已替换，新计划入口存在。
- `git diff --check` 与冲突标记扫描。
- 两仓库在 SOURCE_BASELINE 所列源码范围重新计算指纹，与规划前一致；产品代码、测试和运行资源未由本轮改变。

这些检查只证明文档结构、引用、任务关系与编辑范围，不证明新架构已经编译或运行。未执行 Gradle、测试、Lint、APK、原生 UI 截图、真机或真实模型调用；未提交、推送、触发 CI 或发布。

## 7. 实施启动：源码保护与发现索引（2026-10-22）

授权：用户要求“开始修改”。本节起进入实施；前文第 2/6 节保留为规划轮历史证据。

本轮新增：

- `tools/rebuild/capture_sources.py`：按显式源码范围捕获两仓库工作区实际文件、HEAD 差异、文件模式与逐文件摘要；拒绝已知敏感文件名及符号链接，输出必须在两仓库外且不得覆盖旧目录。
- `tools/rebuild/index_sources.py`：从 LG 源码提取静态发现索引，不执行 LG 或模型请求。
- [快照收据](SNAPSHOT_RECEIPT.json)、[行为发现索引](BEHAVIOR_INDEX.json)、[源码保护和迁移索引说明](BASELINE_GUIDE.md)。
- 根 README 顶部增加重构期说明，保留下方旧平台资料，不覆盖其他已有修改。

实际执行与结果：

1. 检查当前分支与脏文件，两仓库 HEAD 与规划记录相同；保留全部已有修改。进程列表未发现真实 GradleDaemon，无需终止进程。
2. 捕获到私有目录 `~/.local/share/lnt-rebuild-baselines/2026-10-22-r00/`：LNT 252 个文件，LG 1612 个文件，无删除项。逐个重新读取归档源码并校验 SHA-256；收据保存两份归档摘要。
3. 静态发现索引：118 路由、21 公开设置字段、14 页面、15 格式、5 工作区外壳 API、10 预装包、11 技能入口。每条含候选功能分组并明确标记消费者审查待完成；不代表全部细项已审计。
4. 核对 Koog 源码旧 import、旧库 v6 声明、BookFileManager 文件拥有权及迁移入口。
5. 静态核对 194 个发现条目无重复、来源文件存在、候选功能 ID 有定义、新文档相对链接存在；`git diff --check` 及冲突标记扫描无发现。
6. 将当前源码与归档逐文件摘要再次比较：LG 范围内全部一致；LNT 仅根 README 因本轮重构提示变化，已有产品源码和测试均保留。

该轮结束时剩余：R00 还需逐项追踪生产者/消费者、Schema、测试、页面可见操作与脚本实际 API，形成精确细项映射；当时 R01—R39 未开始。快照是本地源码范围备份，不含 Git 历史，不是用户数据备份，也尚未做完整重建演练。

验证边界：静态审查，未实际构建。未执行 Gradle、测试、Lint、APK、设备或模型调用；未提交、推送、触发 Actions 或发布。LG 仓库只读；未访问或迁移真实用户数据。

## 8. 第二轮：模块装配与运行生命周期原型（2026-10-22）

授权：用户要求“继续持续修改，完成一大轮修改再汇报”。此轮未启动大面积 UI 或正式格式开发；R00 全细项审计尚未完成，R01 先落地可静态审阅的模块声明及原型基础，不把任务前置或门槛标为已通过。

### 实际修改

- 新建 domain/engine/agent/storage/formats/workspace 六模块，与 app 共七模块。domain/engine 为 Kotlin/JVM，其余为 Android Library，Java/Kotlin 目标均为 17；SDK 数值集中至 catalog；不新增模块级 repositories。
- app 声明所有新模块依赖。旧启动导航、用户数据库、文件拥有者与已有 Koog 修改完整保留；尚未把旧业务接到新模块。
- domain 新增稳定工程/会话/运行/命令/条目身份、运行端口/快照、规范相对路径和无损数字参数校验。
- engine 新增 RuntimeCoordinator：身份与占用原子预留、不透明 lease、停止/收尾、新工作拒绝、资源占用延迟释放、迟到 finally/Stop 校验；锁外发布 StateFlow 快照，以修订号 CAS 防止并发迟到快照回退；不在运行锁内恢复订阅协程。
- engine 新增 ManagedRunLauncher / RunOperations：先预留再 launch，ATOMIC 启动、已取消父作用域收尾、结构化子任务等待、操作与写入的 finally 释放。
- 写入 24 个 JVM 测试用例，覆盖整数/越界/精度、路径穿越、竞争、伪造/迟到 lease、Stop/资源收尾、技能与认证占用、取消与结构化执行。**均未执行**。
- CI test 命令增加 domain/engine JVM 任务和静态模块检查，报告增加 core 多模块路径；未触发 Actions，不移除原有测试/Lint。
- 源码索引增加逐条文本引用/测试线索与更准确功能分组；未知路由分类直接报错。194 条发现记录均保留完整消费者审查 pending，避免把字符串命中当调用证明。
- 补充 [静态审查与构建交接](STATIC_REVIEW.md)，核对运行门禁、工程/审批/API 与 21 个公开设置的直接及间接消费者；同步根规则实际目录和重构期说明。

### 实际验证

- 执行 `python tools/rebuild/check_structure.py`：模块声明、依赖方向、旧业务 import 隔离、Manifest XML、catalog 和 CI JVM 任务引用静态核对通过。该脚本不解析依赖或编译 Kotlin。
- Python 源码以 ast.parse 静态解析；TOML/XML/JSON 读取及 194 条索引去重、来源和全部文本引用行核对通过。
- `git diff --check`、冲突标记扫描及新增文件空白检查无发现；计划相对链接存在。
- 再次校验原归档摘要未变，并逐文件比较当前来源：LG 全部一致；LNT 仅 7 个本轮授权的构建/CI/说明文件变化，已有产品源码、旧 Koog 和测试修改均保留。
- 实际构建、测试、Lint、设备及供应商验证仍未执行；新增测试只是待 PC/CI 验证的源码。

### 未完成与下一步

1. R00 的全部页面动作、模型字段、技能脚本实际 API 和完整 Schema/消费者审计继续补齐；已完成代表性链路核对不等于全部通过。
2. R01 必须在 PC/获授权 CI 执行最小模块编译、JVM/Android 测试、Lint 与 APK 装配；依赖解析锁定和安装证据仍缺。新模块尚未接入应用启动业务。
3. R02 真正迁入 Koog 前需外部最小编译证据；继续 typed history、工具/问答/审批、检查点与设备恢复原型。当前协调器和 launcher 不提供持久去重/副作用回执。
4. R03—R06 的脚本/native/SQLite/格式/Service/SAF 设备门槛仍阻止正式全量实现，不因此删减范围。

交付边界：静态审查，未实际构建。未提交、推送、触发 CI、发布或调用真实供应商；未迁移或删除用户资料，LG 仓库保持只读。

## 9. 第三轮：类型化 Agent 边界与回执契约（2026-10-22）

授权：用户继续要求持续修改并完成一大轮后汇报。本轮继续 R02 的可静态审阅准备，不绕过 R01 外部最小编译门槛迁入 Koog，不开始全量 UI/格式开发。

### 实际修改

- domain 新增原生角色消息、内部稳定调用 ID/供应商原始调用 ID、工具结果、图片固化引用与供应商续传字段；冻结集合并校验工具配对、重复身份和 system 位置。未把工具结果展平成 user 文本。
- 建立 AgentModelPort、AgentToolPort、AgentPersistencePort、AgentEventSink。请求绑定 Project/Chat/Run/Branch/Attempt 与模型配置修订，单次用量区分实际/估算/未知；仍没有真实模型协议适配或生产持久化实现。
- 新增有界 JSON 对象解析：保留数字 token、拒绝解码后同名键、非法数字和超预算；默认字符串不输出内容。新增请求内容 SHA-256 指纹，覆盖模型修订、全部历史、工具 Schema、图片摘要和续传数据，处理 Unicode 无损边界。
- AgentRequestBoundary 实现一次请求的资源占用、持久发送 CAS 契约、已完成回执重放、不确定拒绝、存储挂起后再检查 Stop、保存收到的响应/用量后传播取消；接收回执后才判断截断/协议失败，避免丢失收费事实。
- AgentToolRegistry 用同一参数定义生成 Schema 与校验器，支持整数/文本/布尔原型子集；当前只公开/执行只读工具，其他写入/付费/认证工具在真实审批路径未接时拒绝。重复只读查询可读取更新事实，不采用旧同参熔断。
- 新增 43 个测试用例，累计 67 个。覆盖原生历史、JSON/整数边界、Schema 同源、指纹、重复/竞争请求、停止/取消、回执失败、预览竞争和两只读工具组合；**均未执行**。假模型、假存储和合成工具只放 test 源集。
- JSON 依赖通过 catalog 声明，domain 以 java-library 的 api 暴露序列化基础类型；agent 的 engine 依赖只用于测试装配。静态结构检查分别检查生产/测试依赖，并阻止 agent 直接引入 HTTP 客户端。
- 新增 [Agent 边界说明](AGENT_CONTRACT.md)，同步根规则、README 和实际模块说明；原有 app/Koog/测试修改不覆盖。

### 核对及未验证

已执行静态模块/依赖/import 检查、Kotlin 源码括号/字符串/注释扫描和新增文件空白检查。JSON 树 API 以官方 1.9.0 源码核对；没有在本机解析 Gradle 依赖或编译 Kotlin。静态扫描不能发现所有类型/重载/平台错误。

交付复核：`git diff --check` 与冲突标记扫描无发现；计划相对链接、Python AST、TOML/XML/JSON 读取及新增文件空白检查通过。再次核对原归档摘要未变，LG 范围内源文件全部一致，LNT 既有产品源码与旧测试未变；归档范围内只包含既定 7 个授权构建/CI/说明文件差异。当前累计测试声明为 67 个，未执行。

Koog 1.3.0 当前本机缓存未找到；历史 javap 记录不能替代本轮实际 SDK 核对。此轮没有声明 Koog 已迁移、没有实现自制 Agent 循环来代替 Koog，也没有提供假生产存储或假审批。

下一步仍需完成 R00 全细项审计与 R01 外部模块构建验证，再接 Koog typed history、检查点/问答/审批原型和实际应用存储。供应商网关、原生工具全 Schema、真实图像处理、费用、自动压缩和跨进程恢复均未完成。

交付边界：静态审查，未实际构建。未执行 Gradle、测试、Lint、APK、设备或真实模型请求；未提交、推送、触发 CI、发布、迁移或删除用户数据。

## 10. 第四轮：消费者审计与审批契约（2026-10-22）

授权：用户要求“继续实施改进，全自动执行”。本轮没有迁入 Koog、没有启动全量 UI 或格式开发，也没有绕开 R01 的外部构建门槛。

### 实际修改

- 新增 `tools/rebuild/audit_consumers.py`，从 LG `23796556` 工作区为 194 条发现逐条补充静态消费者证据，生成 [CONSUMER_AUDIT.json](CONSUMER_AUDIT.json)：路由处理器 → 被调方法 → 后端定义（按接收者路径提示排序）→ 前端字面量/动态前缀 → 测试文件，并检查记录行是否漂移。脚本只做文本与括号分析，不执行 LG。
- 审计结果：118 路由、14 页面、15 格式、21 设置、5 个 workspace 外壳 API、10 个预装包、11 个内置技能全部有定位证据；唯一需人工确认项为 `project_fixed_path`（仅前端引用，已由第 3 节解释为建工程/SAF 流程，不是缺失）。所有条目 `source_ok` 为 true。
- 新增 `tools/rebuild/coverage_report.py` 与 [FEATURE_COVERAGE.json](FEATURE_COVERAGE.json)：98 个功能 ID 中 75 个已有发现条目，23 个（AG02/AG05/AG08/AG15/AG16、MO02/03/07/08/10、OP02/06/07/08、PR03/05/06/10、QL08、TR08/09、WS04、WS10）尚无条目，原因是发现范围未覆盖工具定义、模型协议、调度/重试、校对评估、运行时服务与迁移；R00 仍需人工绑定这些源码。
- `tools/rebuild/check_structure.py` 扩展为：强制 `:core:domain` 使用 `api`、校验包名与目录一致、主源码必须存在测试源、扫描 Kotlin 2.3/API 24 地雷（`createTempDir`、命名分组取值、`(?U)`、`kotlinx.coroutines.CoroutineContext`、`java.net`）、扫描主/测试 Kotlin 的字符串/注释感知括号平衡、校验计划文档相对链接、校验功能覆盖文件与矩阵 ID 同步。
- 五个模块的 domain 依赖由 `implementation` 改为 `api`：公开签名暴露 domain 类型，原配置会把类型从消费者编译类路径隐藏。
- domain 新增 `ChangeSet.kt`（对象身份、基准指纹、批次 digest、摘要、Unicode 完整性）与 `Approval.kt`（审批记录/状态/授权凭据/CAS 端口），`Identity.kt` 增加 `ChangeSetId`、`ApprovalRequestId`。
- engine 新增 `ApprovalCoordinator`：单 Chat 唯一待决、模式快照、`allow_once` 单次消费、拒绝/超时/取消终态、重复请求幂等与内容冲突、按真实 Run 取消、服务恢复时结算超时。
- 新增 19 个未执行测试：变更批次 7、审批记录 2、协调器 10（含测试内存 CAS 存储）。
- 新增 [审批与变更契约](APPROVAL_CONTRACT.md)，同步 README 阅读表、[STATIC_REVIEW](STATIC_REVIEW.md)、ARCHITECTURE 依赖说明与 IMPLEMENTATION_PLAN 的授权措辞。

### 实际验证

- `python3 tools/rebuild/check_structure.py`：模块/`api` 边/import/包名/manifest/地雷/括号平衡/文档链接/覆盖同步/catalog/CI 引用通过。
- `python3 tools/rebuild/audit_consumers.py …`：194 条全部产出证据；`needs_review` 仅 `project_fixed_path`；写出 JSON 后可解析；重复运行摘要一致（SHA-256 相同）。
- `python3 tools/rebuild/coverage_report.py …`：98 个矩阵 ID 全部映射输出，23 个无发现条目；`check_structure.py` 校验覆盖文件与矩阵 ID 同步。
- `git diff --check`、冲突标记扫描无发现；新增 Kotlin 逐文件人工类型核对（无编译）。

### 未完成与边界

- 19 个测试当时只是源码、未在本机执行（已由第 11 节 CI 证明通过）；审批只有端口契约和测试内存实现，没有 AppStore 适配器、工程写入口、`workspace_apply`/工具接线、U02 界面或恢复流程。`AgentToolRegistry` 继续拒绝所有 `PROJECT_WRITE`。
- R00 仍未完成：消费者证据不等于参数、Schema、可见操作与脚本 API 已逐项核对；`BEHAVIOR_INDEX.json` 的 `consumer_review` 仍为 pending。
- R01 的外部编译/Lint/签名 APK 证据已由第 11 节取得；任务范围内的依赖锁定、安装与模块边界验收仍未完成。
- 本机未执行 Gradle、测试、Lint、设备或真实模型调用；未提交、推送、触发 CI 或发布；LG 仓库只读。

## 11. CI 首次真实构建（2026-10-22）

用户要求提交并触发 Actions。推送到 `refactor/translation-agent-2026-10`（未动 `main`/`beta`），三个提交 `9506fd9`、`4c55d44`、`9da7432` 后以 `workflow_dispatch` 触发 [run 39764281126](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39764281126)。

结果（HEAD `9da7432`）：

| job | 结论 | 证据 |
|---|---|---|
| lint | success | `lintDebug` 通过 |
| release | success | 签名 Release APK 构建、签名校验与上传通过 |
| test | failure | `:core:engine:compileTestKotlin` 失败：`ApprovalCoordinatorTest.kt:4` 从 `nativecore.domain` 导入并不存在于该模块的 `ApprovalGrantRejected` |

这是本轮新代码第一次真正编译：**主源码（domain/engine/agent 与 app）能编译并打出签名 APK，Lint 无阻断**；失败只在一个测试文件的导入。

修复：`ApprovalGrantRejected` 与 `ApprovalUnavailable` 都定义在 engine 包内，删除错误导入即可（测试文件与它们同包）。
同时把该错误类型加入静态检查：`check_structure.py` 现在为每个模块建立声明索引，任何 `nativecore.<module>.<Name>` 导入若在该模块不存在即失败，本机即可拦截，不必等 CI。

修复后重新推送（`e0f3ee9`）并重跑 [run 39765686187](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39765686187)：**test / lint / release 三个 job 全部 success**。
该次 `test` 执行了 `:core:engine:test` 与 `:app:testDebugUnitTest`（含 `:core:agent` 单测），`:core:domain:test` 命中构建缓存（其用例已在上一次运行的 `:core:domain:test` 中通过）。新增 19 个审批/变更用例因此都至少成功执行过一次。
这证明模块骨架、主源码与测试源码可编译、单测通过、签名 Release 可产出；不证明完整产品行为、Koog 恢复、脚本/格式门槛或设备交互。完整产品验收仍取决于后续任务；R01 装配验收的现行边界见第 3 节状态表与第 14 节“R01 验装配”条目，不把后续业务门槛反向作为 R01 前置。

## 12. 审查修复（2026-10-22）

外部静态审查覆盖 `4bd2ae0..5d1fee4`，报出 4 项问题，逐项核实成立并修复：

| 编号 | 问题 | 修复 |
|---|---|---|
| P1 | 切换/新建会话后旧回合继续运行，Stop 指向新会话 | `AgentChatController` 在 `sessionLock` 内原子预留 `activeTurn`，释放锁后才跑回合；`cancel()` 绑定实际预留会话；清理放在 `NonCancellable` 中，`dispose()` 取消 scope 时不会残留预留 |
| P1 | 工具整数参数静默截断（`1.5 → 1`、越界 Double 饱和） | `AgentToolHost` 新增 `integralLongOrNull`：先验证整数性与范围，再转换；`parallel_chapters`、`chapter_index`、`limit`、可选 `project_id` 全部改用它，非法值返回明确错误而不是回落默认目标 |
| P2 | `JsonObjectValue.json` 暴露解析器可变树，指纹计算后可被改写 | `parse` 现在递归重建为不可变集合（`unmodifiableMap`/`unmodifiableList`），并用测试验证 `setValue` 抛 `UnsupportedOperationException` |
| P2 | 变更去重遗漏 `parentId`，不同 PDF 资产同页号被误判重复 | 去重键改为完整身份 `(kind, id, parentId)`，并补“不同父对象同局部 id 合法”的测试 |

新增测试：JSON 深冻结 1 个、变更父对象身份 1 个，并扩展重复判定用例。`check_structure.py` 增加两项本机拦截：agent 工具参数文件禁止 `as? Number` 与 `optInt/optLong`（必须走 `integralLongOrNull`）。

已知缺口：P1 的会话切换竞争没有自动化测试——`AgentSession` 是 Koog 具体类，控制器没有可替换的会话接口；本轮只做静态审查与代码核对，R02 的类型化运行时会替换这套控制器，届时补并发测试。

交付边界：本机静态审查，未构建、未执行测试、未运行 Lint；修复后需重新推送并重跑 CI，全绿前不登记 R01 完成（本轮重跑结果见第 13 节）。

## 13. 参数校验后续审查修复与 CI 失败核对（2026-10-22）

用户要求修复后续两项审查意见，并核对 GitHub 最新 Actions 的 test 失败原因。

- 最新 [Actions #116 / run 39768093299](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39768093299) 对应 `0b62758`；test 失败，lint/release 成功。读取 test job `119788470765` 日志确认：`:app:testDebugUnitTest` 共 645 项、2 项失败，恰为 `updateTranslationConfigNormalizesFields` 与 `getRecentLogOptionalProjectAndDefaultLimit` 的旧回退断言；不是编译失败。
- 更新上述测试：`parallel_chapters:0` 与 `project_id:0` 必须收到非法参数说明且不调用宿主；保留省略/显式 null 的默认查询与合法配置字段归一覆盖，不恢复非法值回退。
- `AgentToolHost.exactLongOrNull` 改为 `BigDecimal(Double).longValueExact()`，按 Double 的精确二进制值检查整数性和范围，避免最短十进制字符串把 `2^60` 转成另一个 ID。
- 新增 2 个测试用例，覆盖必填/可选大整数 ID 的普通小数、科学计数法、Long 上界附近值及整数字符串，并断言小数/超范围 ID 不触达宿主。大整数问题不在 #116 的失败列表中；这些新用例已在下一段的 `39769169729` 中随 `app:testDebugUnitTest` 执行通过。

实际验证：调用链与测试断言静态核对、`git diff --check`、冲突标记扫描；另只读获取 GitHub 的 run/jobs/test 日志。静态审查，未实际构建，未执行本机测试或 Lint；未提交、推送、重跑或触发 Actions。#116 反映修复前版本，不能作为本次修复已通过的证据。

修复后重跑 [run 39769169729](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39769169729)（`e933f13`）：test / lint / release 全部 success；`app:testDebugUnitTest` 实际执行，`core:domain:test` 与 `core:engine:test` 命中缓存（此前运行已通过）。四项审查修复至此有 CI 证据，但仍只覆盖“构建 + 单测”，不代表功能验收；P1 会话切换竞争仍无自动化测试，见第 12 节缺口说明。

## 14. 方案审查后的文档优化（2026-10-22）

授权：用户要求“对文档进行优化改进”。本轮从干净的 `b4491f9` 工作区开始，只修改本计划目录 Markdown；不修改产品源码、测试、构建配置或机器发现索引。没有重新查询或触发 CI，第 13 节已记录的结果保留其提交范围。

### 设计调整

- 保留 98 个功能 ID、40 个任务 ID 和 15 个 UI 条目；任务增加开工子产物/完整验收边界，解除 TXT 对全部复杂格式、只读 Agent 对完整批翻的等待。R01 验装配，G1—G5 仍约束 M1 与完整产品。
- 实施计划增加 S1 真实 TXT 手动闭环、S2 Agent 审批写入、S3 工作区/格式扩展；R30 假数据视觉原型不能代替真实业务证据。
- 审批契约区分现有 CAS 原型与待实现协议，补不可变正文、消费与 commandId 原子绑定、跨库故障恢复、部分冲突和 Stop 后未提交终态。现有生产工具仍拒绝写入。
- 架构补工程谱系/本地实例/恢复代际、重复导入/外部修改/复制/恢复规则、交换与完整备份清单和一致切点，以及 Pi 历史字段级映射要求。
- 工作区先按实际包/API 分类再选引擎；分别验证 JS、异步宿主与 native 取消。运行时增加活动执行/持久等待/中断/收尾的资源与 Service 策略。
- R36 合成旧库字段样本前移到 R07 schema 冻结前；正式用户数据迁移仍由后期验收控制。
- 同步 UI 与功能矩阵；修正测试均未执行、唯一编译硬阻塞等过期摘要，保留历史轮次的验证边界。

### 验证与未完成

实际执行：`python3 tools/rebuild/check_structure.py` 通过（只读静态检查，包含文档相对链接与功能覆盖 ID 同步）；另作一次性文档解析，核对 40 个唯一任务 ID、表列依赖无环、98 个功能 ID 与覆盖报告一致、15 个 UI ID、任务引用和代码围栏。对子产物/完整验收的跨任务关系另作人工核对，图检查不替代语义审查。`git diff --check` 与冲突标记扫描无发现；改动范围为本目录 8 个 Markdown，无源码/机器索引变化。

本节设计尚未产品实现；未取得新增审批恢复、备份、脚本或设备行为证据。静态审查，未实际构建、未运行测试/Lint、未访问用户数据；未提交、推送或触发 Actions。

## 15. 第五轮：R00 源码绑定与 R36 字段样本（2026-10-22）

授权：用户要求“执行下一轮修改”。本轮只做不需要构建的静态工作：补齐 R00 的证据链、产出 R36 早期字段样本。未修改产品源码或测试，未触发 CI。

### 实际修改

- 新增 `tools/rebuild/source_bindings.py`：为发现范围之外的功能登记一条已审阅 `(LG 文件, 标记)` 绑定并验证标记存在；原生项（`OP06`/`OP07`/`OP08`）记录“无 LG 源码可引”的理由，而不是当成缺口。
- 新增 [SOURCE_BINDINGS.json](SOURCE_BINDINGS.json)：20 条绑定覆盖 AG02/AG05/AG08/AG15/AG16、MO02/03/07/08/10、OP02、PR03/05/06/10、QL08、TR08/09、WS04、WS10；每条含文件、标记、行号与说明。
- `tools/rebuild/coverage_report.py` 升级为合并“发现条目 + 源码绑定 + 原生项”，输出 v2 覆盖报告；[FEATURE_COVERAGE.json](FEATURE_COVERAGE.json) 现在 98/98 有可追踪证据（75 发现 + 20 绑定 + 3 原生项），未覆盖为 0。
- `tools/rebuild/check_structure.py` 增加 SOURCE_BINDINGS.json 的 ID 集合与分类校验，防止矩阵变更后绑定表过期。
- 新增 [旧库字段映射样本](LEGACY_FIELD_SAMPLE.md)：只读旧 Room v6 实体、schema 清单、`BookFileManager`、凭据与调度偏好，逐类给出“直接映射/转换/档案保留/需扩展/可再生”归属，并列出 R07 schema 冻结前必须回答的 6 个问题。

### 实际验证

- `python3 tools/rebuild/source_bindings.py …`：98 个 ID 全部归类，20 条绑定标记全部命中，无路径/标记错误。
- `python3 tools/rebuild/coverage_report.py …`：输出 98/98，未覆盖 0，分类 `bound 20 / discovery 75 / native_only 3`。
- `python3 tools/rebuild/check_structure.py` 通过；`py_compile` 通过；`git diff --check` 与冲突标记扫描无发现。

### 边界与未完成

- 绑定只证明审阅过的文件里仍有该标记，不证明参数、Schema、运行时行为或 LG 对齐；R00 的参数/可见操作/脚本 API 细项仍未完成。
- R36 字段样本是设计输入，未实现迁移器、未跑 Room 迁移测试、未读取任何真实用户数据；`app/schemas/` 缺 `2.json`，v2 只能靠合成库跑 1→6 连续链验证。
- 本轮改动（工具、JSON、文档）尚未提交；未构建、未运行测试/Lint、未触发 Actions。

## 16. 第六轮：写入/输入/检查点契约（2026-10-22）

授权：用户要求“继续修改，全自动完成剩下的所有内容”。本轮把不依赖设备与真实供应商的剩余契约实现补齐，并首次把新契约交给 CI 验证。明确说明：整个重构（98 项功能、G1—G5、真机与真实调用、用户数据迁移）不可能在一次会话内完成，本轮只推进可静态/CI 验证的部分。

### 实际修改

- `domain/ProjectWrite.kt`：`ChangePayloadRef`（版本化正文引用）、`ApprovalCommandBinding`（命令 + 实例 + 代际 + 批次 + digest + 冲突策略）、`WriteReceipt`/`WriteOutcome`/`RejectReason`/`IndeterminateReason`、`ProjectWritePort`，以及 `WriteRecovery` 判定表，逐条对应 APPROVAL_CONTRACT §5.3 的故障窗口。
- `domain/Identity.kt`：新增 `ProjectInstanceId`、`RecoveryGeneration`。
- `domain/AgentInputCommand.kt`：输入命令状态机（draft/queued/sending/accepted/paused/failed/cancelled）、编辑与重试规则、命令去重注册表（同 ID 同内容幂等、不同内容冲突）。
- `domain/Checkpoint.kt`：版本化 `CheckpointEnvelope`（策略版本、阶段、分支、typed history、待完成工具、摘要、已受理命令、用量）与校验：待完成调用必须与历史一致、摘要只属于压缩阶段、版本与命令身份合法。
- 新增 3 个测试文件（检查点 4 例、写入恢复判定 5 例、输入命令 6 例），覆盖状态机、判定优先级、回执不变量与版本校验。
- 文档同步：APPROVAL_CONTRACT §5/§6、ARCHITECTURE §3.4 标注“类型已落地、适配器未实现”。

### 实际验证

- `python3 tools/rebuild/check_structure.py` 通过（模块边界、包名、地雷、括号平衡、文档链接、覆盖同步、agent 参数守卫）。
- 新契约的编译与单测由 CI 验证；本机未构建、未运行测试。

### 边界与未完成

- 这些是**端口与纯规则**：没有生产存储适配器、工程事务、回执持久化、恢复查询或 UI；`AgentToolRegistry` 仍拒绝全部写入工具。
- R00 参数/Schema/可见操作/脚本 API 细项、R36 六个开放问题、G1—G5 门槛、S1/S2 切片与全量 UI 均未完成。
- 真实供应商调用、真机验证与用户数据迁移需要用户显式授权，不在本轮范围。


## 17. 原生工程扩展名调整（2026-10-22）

用户在存储实现准备期间要求暂停，并决定原生工程改用 `.lnt`，同时同步文档。本轮仅调整格式约定与文档，未继续产品实现。

- `.lnt`：LNT 原生 SQLite 工程，独立管理格式标识、schema 版本与迁移；默认创建、打开、保存和工程交付均使用它。
- `.lg`：LG 兼容导入/导出，由 CompatibilityStore 做字段级转换；仍保留完整双向事实/历史验收，不靠改后缀实现兼容。
- 完整恢复备份：另行定义封装/扩展名，包含一致 `.lnt` 快照与关联应用记录、正文和材料；普通工程文件不等于完整备份。
- 已同步根 AGENTS、计划入口、架构、实施/验收、UI、功能矩阵与旧库字段样本。源码基线、LG 来源证据与历史轮次保留原义；当前尚无工程文件生产实现，无实际文件需要重命名或迁移。

验证：逐处核对 `.lnt`/`.lg` 用途、文档链接静态检查、`git diff --check` 与冲突标记扫描；本机静态审查，未实际构建，未运行测试/Lint。未提交、推送或触发 Actions。格式及转换器均未实现，不登记功能验收完成。


## 18. 第七轮：原生 .lnt TXT 存储与手工事务（2026-10-22）

授权：用户在确定 `.lnt` 并同步文档后要求“继续修改”。本轮推进 R04/R07/R08/R09 的 TXT 存储子产物，不宣布 S1 完成。

### 实际修改

- domain 增加 `TextProject.kt`：原生格式标识/版本、本地实例引用、TXT 页/条目/人工命令、持久回执、版本化指纹与存储端口。
- storage 增加独立 Room `lnt_native_app.db` v1 的实例登记，以及专用 `.lnt` SQLite v1。原稿采用 64 KiB raw-v1 分块并保存原始摘要；条目稳定 ID 分页，空 TXT 保留资产。
- `NativeTextProjectStore` 实现显式编码创建、登记实例发现/重开、原稿/译文导出和原生逻辑快照；方法在 IO dispatcher 运行，不接旧 AppViewModel 或旧库。
- `ProjectWriteStore` 手工事务检查工程修订/对象指纹；变更、修订事实与回执原子提交，冲突终态同样持久保存。重复命令核对完整摘要，实例/代际不匹配拒绝，取消或注入故障回滚；运行占用使用应用传入的 RuntimePort。
- 原生工程快照采用 SQLite ATTACH/事务复制，目标流写入前释放数据库事务与运行写租约；关闭流失败不报成功。没有将 `.lnt` 直接改名为 `.lg`。
- 新增 20 个未执行用例：storage 集成 14、TXT 编码/行边界/预算 3、domain 指纹 3。集成使用合成文件、Room 与 Robolectric native SQLite，不接真实供应商或用户数据。
- storage 增加既有 catalog 中的 Room/KSP、协程与 Robolectric 依赖；CI 仅增加 Room schema 产物上传，未触发运行。静态边界允许 storage 测试依赖 engine；生产模块依赖仍只跨到 domain。
- R36 样本 §5 的六项问题形成保留决策：原始映射/修订/候选不丢弃、旧阅读偏好只入档案、正文不自动进新日志、指纹重算、无归属会话由 U01 档案入口/U02 显式绑定。它们仍是完整迁移模型的设计输入，不是迁移器。
- 新增 NATIVE_STORAGE.md 说明 SQL/所有权/失败窗口/默认预算及未接线范围；同步根规则、架构、静态审查与入口文档。

### 实际验证

- `python3 tools/rebuild/check_structure.py`：检查模块/包/导入、Kotlin 括号、catalog/CI、文档链接与功能覆盖同步；只提供静态结构证据。
- 人工追踪创建 → Room 登记 → 重开、手工命令 → 同工程事务 → 回执重放，以及快照/流关闭失败路径；`git diff --check` 和冲突标记扫描。
- 本机静态审查，未实际构建，未运行新增/既有测试或 Lint。此前 CI 全绿不能覆盖本轮新增代码。

### 未完成与下一步

1. PC/获授权 CI 需验证 KSP/Room、native SQLite 测试、模块编译与 Lint；首次权威 Room schema JSON 尚未生成或提交，已配置导出目录和报告上传，不伪造生成文件。任何 v2 迁移前先审阅并纳入首份 schema。
2. 当前只重开登记的私有实例；外部 `.lnt` 导入、覆盖恢复/代际推进、`.lg`/Pi 兼容、完整 schema 与备份均未实现。目录发布后 Room 登记失败的材料保留，但孤立目录自动重关联尚无实现；掉电/磁盘满/系统清理与并发故障未验证。
3. 无 UI/SAF 选择、统一网关、假模型翻译、Service、Chat/运行/审批生产存储接线。Agent 写工具仍拒绝，不将人工命令当作模型授权；S1/S2 和全部功能验收仍未完成。
4. 下一切片先取得本轮外部编译/测试及 schema 证据，再补外部原生工程导入/恢复与实际应用服务接线，沿 S1 推进最小界面和假模型流水线；全量格式与审批继续依任务分解推进。

未提交、未推送、未触发 Actions；未读取、迁移或删除用户数据。


## 19. 第八轮：TXT 存储缺陷修复与契约收敛（2026-10-22）

授权：用户要求检查未提交更改并"修复"审查发现的问题。本轮只修 §18 切片自身缺陷，不扩大业务范围，不把任何功能 ID 记为完成。

### 修复的实际问题

1. **journal 检查在真机上必然失败（阻断）**：原实现要求 `PRAGMA journal_mode == "delete"`。AOSP `SQLiteConnection.open()` 对每个可写连接执行 `setJournalFromConfiguration()`，平台默认值为 `TRUNCATE`（`frameworks/base` 的 `db_default_journal_mode`）；而 SQLite 只持久化 WAL，`DELETE/TRUNCATE/PERSIST` 是连接级设置。因此创建后的任何可写打开都会被判成 `INVALID_FORMAT`。现改为在打开前读 SQLite 文件头：magic 必须匹配，格式版本字节 18/19 必须为 1（回滚日志），为 2（WAL）即拒绝。快照的保证收敛为"非 WAL + ATTACH 逻辑复制"。
2. **失效实例拖垮最近列表**：`registeredProjects` 原先任一工程缺失/损坏即整体抛出。新增 `RegisteredProject(ref, summary, failure)`，逐条捕获 `TextProjectException` 并返回分类原因；`open` 对指定实例仍抛错。构造函数保证 summary 与 failure 二选一且归属同一实例。
3. **快照丢失自增高水位**：`INSERT ... SELECT` 写显式 ID 只把 `sqlite_sequence` 压到现存最大 ID（已用 sqlite3 复现：主库 seq=3 → 快照 seq=2）。现单独原样复制 `sqlite_sequence`，避免恢复后复用已删除的 item ID。
4. **错误分类收敛**：新增 `STORAGE_IO`；目录创建、staging 发布重命名、快照目标不新鲜、`createNewFile` 失败改抛它；损坏的 status 枚举与非法实例 UUID 归入 `CORRUPT_PROJECT`。不再用 `check`/`error` 抛 `IllegalStateException` 冒充存储结论。
5. **构建一致性**：KSP 依赖改用与 `app` 相同的 `"ksp"(...)` 写法；storage 显式声明 `kotlinx-serialization-json`；移除未使用的 `kotlinx-coroutines-test`。
6. 读路径仍以可写连接打开：SQLite 只能在可写连接回滚热日志，崩溃恢复优先于省写标志；该取舍写入 `NATIVE_STORAGE`，并声明同一适配器串行、两实例不得共享工程目录。

### 新增/调整的测试

- 新增 3 例：失效实例不隐藏其他工程、WAL 工程被拒绝且文件不变、快照保留自增高水位。
- 调整 1 例：重开用例改为读取 `RegisteredProject.summary`。
- 切片用例总数 20 → 23（storage 集成 17、TXT 3、domain 3）。**均未执行。**

### 实际验证

- `python3 tools/rebuild/check_structure.py` 通过；`git diff --check`、冲突标记扫描无发现。
- 用本地 sqlite3 复现：rollback journal 模式不跨连接持久；`ATTACH` + 显式 ID 复制会把自增高水位压低；`PRAGMA snapshot.application_id/user_version` 与 `DETACH`（提交后脱离）行为符合实现。
- 只读核对 AOSP `SQLiteConnection`、`SQLiteDatabaseConfiguration`、`SQLiteGlobal` 与 `frameworks/base` 资源值，确认平台默认 journal 模式与 open 时强制设置的行为。
- 本机静态审查，未构建、未运行 JVM/Robolectric 测试或 Lint，未触发 Actions；第 11—13 节 CI 结果不覆盖本轮代码。

### CI 证据（第八轮）

- 提交 `224da53` 推送到 `refactor/translation-agent-2026-10`（未动 `main`/`beta`），以 `workflow_dispatch` 触发 [run 39789360824](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39789360824)：test / lint / release 三个 job 全部 success（test 6m27s、lint 7m06s、release 6m53s）。
- test job 实际编译并执行 `:core:storage:kspDebugKotlin`、`compileDebugKotlin`、`compileDebugUnitTestKotlin`、`testDebugUnitTest` 与 `:core:domain:test`：`NativeTextProjectStoreTest` 17/17、`TxtDocumentTest` 3/3、`TextProjectFingerprintTest` 3/3 通过，skipped 0、failures 0。这是 §18 切片首次真实构建与运行。
- CI 生成 Room schema `core/storage/schemas/...NativeAppDatabase/1.json`，已对照 `NativeAppDatabase.kt` 审阅并提交；没有手写或伪造的 schema。
- 该结果只证明 storage 切片可编译、单测通过、签名 Release 可产出；不证明真机 SAF/journal 行为、UI 接线或完整产品能力。

### 仍未完成

1. KSP/Room、Robolectric native SQLite 与 23 例已由 run 39789360824 证明；真机仍需验证 journal 打开/重开、SAF 关闭失败、锁屏、进程死亡与磁盘满，不能以单测替代。
2. 版本迁移、外部 `.lnt` 导入、覆盖恢复/代际推进、`.lg`/Pi 兼容、完整 schema 与备份、SAF 交付、UI/网关/批翻/Service 均未实现。
3. 快照临时产物在取消/失败时仍留在 cacheDir（失败保留是有意设计），清理入口未实现。


## 20. 第九轮：原生工程导入/恢复与 TXT 工作区接线（2026-10-22）

授权：用户要求快速推进、一次完成当前可完成任务，并在中断后要求继续。此次推进真实存储与原生入口，不以占位按钮冒充完整功能。

### 已落地的源码

- **工程交付与恢复**：外部 `.lnt` 有界导入、精确 v1 schema/摘要/修订链/回执校验、命令去重、新本地实例、工程复制；发布前同步私有登记清单，重启恢复已发布但未登记的工程，异常目录保留并逐项报告。新增双语导出与受保护的自有临时输出清理，不改 Room/工程 schema 版本。
- **新启动链**：MainActivity → NativeProjectApp → NativeProjectViewModel → NativeAppGraph → 原生端口/共享运行协调器。旧 AppViewModel、导航、Koog 和翻译引擎不在新启动业务链上；旧源码和用户文件全部保留，旧库只检查文件是否存在并展示保护提示，没有迁移、读取正文或删除。
- **最小原生界面**：工程首页的 TXT 创建/编码选择、原生工程导入副本、登记列表和复制；打开工程默认 Agent；工作台实际保存 `.lnt`、导出译文/双语/原稿及清理临时输出；分页人工校对、单条编辑、状态修改、冲突保留文本并显式重读基准。手机底栏/宽屏侧栏共享路由。
- **状态与文件选择**：列表只保留有界预览，完整正文按单条读取；SavedStateHandle 保存选中身份、页面和单条草稿，恢复时查询写回执而不自动重放。系统选择结果绑定发起时的工程/命令/编码；失败保留已加载内容。输出流写入、flush 和 close 全部成功才提示完成。
- **文案与边界**：新增 Android 五语资源（en/zh/ja/ko/de），不再向旧 AppStrings 写新页面。Agent 与自动翻译明确显示尚未接通，没有模型付费调用、假发送或人工写入绕过 Agent 审批。

### 新增验证材料与实际验证

- 新增 `NativeProjectImportTest` 10 例，覆盖导入/快照修订事实、复制隔离、登记恢复、异常清单、未知 schema、损坏指纹、取消/输入关闭失败、预算、双语和临时清理；调整既有输出关闭失败的错误分类期望。
- 新增 `NativeWorkspaceScreenshotTest` 5 个组件用例，覆盖工程首页、Agent 入口、工作台、校对和编辑器的合成布局/回调；CI 上传配置加入 `native-*.png`。**这 15 个新增用例尚未运行，截图尚未生成或查看，不代表完整 Activity/SAF 生命周期测试或视觉验收。**
- 扩展静态检查：新入口、nativeapp 导入边界和括号、Kotlin 关键字声明、五语资源键/占位符/引用；同时保留模块依赖与文档链接检查。
- 本轮执行 `python3 tools/rebuild/check_structure.py`、`git diff --check` 和冲突标记扫描；结果以本轮交付检查为准。**静态审查，未实际构建，未执行单测或 Lint。**

### 既有 CI 证据的适用范围

此前只读获取的最近两次记录：[run 39789360824 / #119](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39789360824) 对应 `224da53`，[run 39790843834 / #120](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39790843834) 对应 `55ff6ed`，test/lint/release 均成功。两份测试 XML 各计 773 例、零失败/错误/跳过，Lint 各 62 个 app 警告、零错误；Room schema 与已提交版本相符。#119 实际执行存储切片 23 例，#120 的 storage/domain/engine 测试来自缓存，不能说重新执行了这 23 例。

上述产物已只读下载至本机仓库外 `~/.local/share/lnt-ci-review/2026-10-22-latest-two/` 并保存 REVIEW.md。它们不覆盖本轮未提交的导入、恢复或界面代码；没有新的 APK、测试通过或视觉验收结论。

### 剩余真实任务与执行顺序

1. 本轮需在 PC/获授权 CI 验证编译、KSP、单测、Lint、Release 及五张截图，实际查看截图后修复；真机验收系统选择器、IME/返回、旋转/进程死亡、锁屏、磁盘满与取消。禁止本机 Gradle。
2. 沿 S1 接统一模型网关与假模型翻译流水线，再做真实模型调度、计量、暂停/恢复；手工 TXT 工作区不代表 S1 完整通过。
3. 沿 S2 完成持久审批、事务消费和 Koog 生产接入，再开放 Agent 写工具；不得用 ManualTextEdit 接模型旁路。
4. 来源 URI 关联/外部变化策略、覆盖恢复/代际推进、完整备份、完整工程 schema、`.lg`/Pi 兼容、复杂格式、脚本/工作区、后台 Service、旧库迁移及完整管理页面继续按原计划推进。G1—G5 及功能矩阵均未因本轮自动关闭。

本轮未提交、未推送、未触发 Actions；没有修改 LG 参照仓库或用户数据。本文 §18—19 的“未实现/未执行”保留为当轮历史，以 §3 与本节判断当前状态。


## 21. 第十轮：补齐应用名称翻译（2026-10-22）

Actions #121（run 39807670146，提交 f2b839f）test/release 成功，lint 因唯一 Error：默认资源 app_name 缺少 de/ja/ko/zh 翻译失败；app 另有 64 个 Warning。此次在对应四个语言目录新增 strings.xml，提供本地化应用名称，不压制 MissingTranslation。

本机静态审查：资源 XML/语言覆盖核对、check_structure.py、git diff --check 与冲突标记检查；未实际构建、未运行测试或 Lint。用户授权提交、推送当前重构分支并触发一次 workflow_dispatch，由用户监控；不监控重跑结果，提交/推送/触发的实际交付状态以本轮回复为准。


## 22. 第十一轮：TXT 手工闭环用户验收与文档同步（2026-10-23）

### CI 与安装证据

- 修复提交 `2f5b8f7` 已推送至 `refactor/translation-agent-2026-10`，触发 [run 39808942938](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39808942938)。用户报告 Actions 通过，并已下载安装对应 Release APK。
- 按用户“我来监控，你不要监控”的要求，Agent 没有继续监控或重新获取该 run 状态/报告；上述通过结论来自用户确认。没有据此新增逐例执行数、缓存命中、警告数或签名复验结论。

### 实机检查结果

用户在完成会话给出的全部检查后明确回复“已全部确认通过”。本轮 TXT 手工切片登记为用户真机检查通过，范围包括：

- 启动、TXT 创建及编码、默认 Agent 入口、工作台/校对导航和返回。
- 人工译文/状态编辑、有效空译文与排除状态、保存后关闭重开，以及强制停止后的已提交事实保留。
- 未保存返回确认、旋转/后台/锁屏后的草稿保护与编辑器操作。
- `.lnt`、译文、双语和原稿导出；原生工程重新导入与复制独立性。
- 系统选择器取消、非法 `.lnt` 拒绝、分页及临时产物清理后工程仍可使用。

Agent 实际查看 `/sdcard/DCIM/Screenshots` 最新五张截图（2026-10-23 00:06:33—00:08:14）：可见 61,352 条韩文 TXT 工程、宽屏侧栏、Agent/工作台、校对修改与“修改已保存”提示，以及第 6 行编辑器的放弃草稿确认框。五张设备截图不等同于 CI 的五张组件截图；重启持久化、旋转和导出文件内容等动态结论依据用户确认，不由静态图片推断。

### 验收边界与下一步

本轮完成的是 TXT 手工切片的用户检查，不代表完整 S1（仍缺假模型翻译流水线）、S2、98 项功能或 G1—G5 全部通过。内部发布后孤立登记恢复、磁盘满/掉电故障注入、全部语言/设备和完整旧数据迁移没有单独证据，保持待验；宽屏留白与卡片密度仍有优化空间，不登记为阻断缺陷。

下一实施切片为统一模型网关与假模型 TXT 翻译流水线，再推进持久审批和 Koog。当前授权仅同步文档：没有产品代码更改、没有本机 Gradle/测试/Lint、没有 GitHub 监控、提交、推送或新触发 Actions。文档交付执行结构/链接静态检查、diff 空白与冲突标记检查；不改变之前各轮的历史验证事实。


## 23. 第十二轮：本地模拟 TXT 翻译流水线（2026-10-23）

用户授权“下一步推进”。沿 S1 实现首个模型网关/分批写回切片，保留 §22 文档同步和用户确认的历史事实。

- domain 新增类型化翻译请求/响应、配置/attempt/工程实例绑定、批次提交端口与轻量进度；engine 新增 NativeModelGateway、LocalSimulationModel、TextTranslationService。
- 网关集中运行操作租约、配置匹配、响应身份/重复/截断/文本校验和脱敏异常；无真实网络、凭据或隐式重试。模拟 Token 全为 UNKNOWN。
- 存储 TranslationCommitPort 验证真实翻译运行租约，通过唯一 ProjectWriteStore 在外层事务中写入整批；冲突或提交前取消回滚本批，已提交前批保留。schema/Room 版本不变，产物仍按 v1 校验和重新导入。
- 校对页新增五语“本页本地模拟翻译”入口，确认后仅处理当前页非空未处理条目，译文明确带 [SIMULATION] 标记；已处理/已排除条目保留。展示已保存条数与请求尝试数，停止后不发起下一次请求；结束、失败或取消后重读已提交事实。
- 新增网关 5 例、真实存储/引擎集成 6 例、界面确认 1 例，共 12 个用例；涵盖身份异常/截断/停止/迟到结果、分批失败与取消、冲突回滚、伪造租约、显式继续、快照重导入与执行前确认。**均未运行**，截图基线因新入口改变仍待 CI/PC 生成并查看。
- 静态结构/模块依赖、五语键与占位符/引用、Kotlin 分隔符、文档链接、diff 空白和冲突标记核对；本机静态审查，未实际构建、未运行测试/Lint。没有调用付费模型、提交/推送/触发或监控 Actions。

详细源码契约见 [TXT_TRANSLATION](TXT_TRANSLATION.md)。统一网关仍只有翻译切片，AgentModelPort 未生产整合；真实供应商、调度/计量账本、持久运行/断点恢复、后台 Service、持久审批和 Koog 仍待实现。S1 本轮源码待 CI 与新增入口真机验证，不关闭 R11/R13、S1 或全部功能矩阵。


## 24. 第十三轮：原生 Koog 固定操作与持久单条审批（2026-10-23）

用户明确“暂不验证，持续推进 S2，也就是 agent 功能”，执行中再次要求继续。保留前轮 S1 模拟代码与文档，保留无关未跟踪 narou_rust/；不提交、推送或触发/监控 Actions。

- core/agent 新增 NativeKoogWorkspace：实际 AIAgent/functionalStrategy/PromptExecutor 接 AgentRequestBoundary，绑定当前工程查询和准备差异固定操作；本地后端，无真实语言模型/付费请求。nativeProjectTools 查询真实工程，nativeApprovalTools 严格校验条目整数与译文，仅产生持久待审批，通用写/付费工具仍关闭。
- storage 新增 NativeAgentStore/Room DAO：保存本地历史、请求指纹/dispatch CAS/响应 usage、单条不可变审批正文及消费/结果。NativeAppDatabase v1→v2 注册显式新增迁移；旧阅读库和 .lnt v1 不变。已提交 1.json 保留，2.json 尚未由 KSP 生成或验证，不能伪造。
- 允许一次绑定实际行号、原状态、前后译文、实例/代际/Project/Chat/Run/命令、修订/对象指纹与摘要；持久正文与命令在同一 Room 行消费绑定，项目侧仍通过唯一 ProjectWriteStore 事务保存内容和回执。重复同意/拒绝/冲突有固定终态，实例变化或 payload 篡改禁止写入。
- Agent 页接 Koog 查询、当前页条目选择、手工拟文的审批演练、固定差异允许一次/拒绝、轻量历史与最近裁决结果。页面明确没有自由对话或模型生成文本；所有新文案维护五语。
- 停止后不发起新操作；项目提交和 Room 补记之间异常，按完整命令摘要查询回执。重启仅核对，不自动写入；确认消费但未提交则终止，无法核实则保留未决状态，不把 CONSUMED 显示为保存成功。
- 新写 NativeAgentStoreTest 11 例与 1 个 UI 允许一次用例，覆盖持久化/幂等/拒绝/冲突、消费/工程提交故障窗口、篡改、复制隔离、v1迁移、请求不确定、Koog 查询/准备。**均未运行**。按用户要求暂不构建/测试/视觉或 CI 验证，仅做必要结构/引用/分隔符/资源/文档及差异静态审查，未实际构建。

详细边界见 [S2_AGENT_SLICE](S2_AGENT_SLICE.md)。这不是 S2 完整验收：真实模型、模型生成差异、自由对话、多 Chat/分支、完整工具/continuation、全对象审批、完整 Run 与后台恢复仍未实现。原生固定操作适配未构建，Koog 新装配和 Room v2 迁移必须待后续获授权验证，不将本轮写成可用 APK。


## 25. 第十四轮：Agent 对话、结构化工具循环与远程配置（2026-10-23）

用户要求“暂不验证，继续推进修改”。保留 §23—24 尚未验证的改动及无关 narou_rust/；不提交、推送或触发/监控 Actions。

- domain 新增 AgentMessageCodec v1 与 AgentConversationPort，保留原生角色、thinking/continuation、工具 provider ID/参数/回执及冻结图片身份；存储回执改为版本化完整 assistant/stop/usage envelope，补结构化会话历史，不再仅能存纯文本响应。
- core/agent 新增 NativeKoogConversation，经现有请求持久边界和共享 NativeModelGateway 执行最多 8 轮的原生类型工具循环；支持真实工程摘要、按稳定 ID 读条目、准备待审批差异。重复调用熔断，成功准备后停止后续模型请求等待用户裁决，工程写入仍走 §24 允许一次。
- core/engine 新增 CompatibleAgentModel：HTTPS Chat Completions 非流式文本/工具/推理文本适配，原生 role/tool_call_id 回传，响应和正文预算、取消关闭资源、分类脱敏、关闭重试与重定向；所有远程调用经共享网关。没有执行任何网络/真实供应商请求或付费调用。
- storage 新增模型配置修订和独立 Keystore AES-GCM 凭据端口；输入 Key 不进入 SavedStateHandle/历史/日志，旧 ApiKeyCipher/旧库仍仅作迁移来源。尚未发布的应用 Room v2 目标增加模型/会话表与历史 payload，v1→v2 迁移同步扩展，2.json 仍待后续 KSP 生成，未手写。
- Agent 页新增模型设置、明确可能计费的发送入口、按工程/Chat 保存的输入草稿、新建/切换会话与重启选择恢复；离线演练仍明确标注。模型返回的拟文由既有审批卡展示、允许一次/拒绝，已提交事实与历史分离。
- 新增 AgentMessageCodecTest 4 例、NativeAgentStoreTest 2 例（后者累计 13），覆盖字段往返、结构化循环准备而不写、会话选择/重启隔离。均未运行；密钥/真实协议/网络取消、迁移与新界面没有验证证据。

本轮只做必要静态结构/引用/资源/分隔符、文档链接、差异空白和冲突检查；未实际构建、测试、Lint、视觉或设备验证。详细边界见 [AGENT_CONVERSATION](AGENT_CONVERSATION.md)。真实模型接线已有源码，但未联调，不声称所有服务商可用、完整计量账本/RPM/重试、分支/队列、后台 Service 或 S2 完成。

### §25 审查修复（2026-10-23）

- 补齐 NativeProjectTools、NativeKoogWorkspace、NativeKoogConversation、NativeProjectViewModel 及相关既有测试的 JsonObjectValue.parse 必需 limits；按摘要、空参数、错误对象和测试夹具分别设置字符/深度上限。
- 助手消息持久化纳入取消捕获范围；取消后在 NonCancellable 中重读持久历史，仅为尚无结果的工具调用补取消结果，覆盖助手已提交与工具结果已提交但尚未返回的窗口，避免缺失或重复结果。
- 新增两个未运行的存储集成回归用例，在助手/首个工具结果提交后取消，核对全部调用结果唯一、完整历史有效且同一会话可继续；没有真实模型调用。
- 静态审查，未实际构建，未运行测试或 Lint；未提交、推送或触发 Actions。

### §25 第二轮审查修复（2026-10-23）

- 历史数量/正文预算超限改为 CONTEXT_OVERFLOW；无效或未配对历史单独提示新建会话，不把损坏历史当预算问题；保留 160 条发送阈值及 256 条存储硬上限。
- reconcile 验证/回执失败保留未决记录，抛脱敏专用异常；loadAgent 分别加载当前会话、核对、历史预览、待审批、结果、模型设置与会话列表。失败部分不复用旧审批卡，展示持续警告并禁止发送、准备与裁决，其他可读取部分继续发布；取消仍传播，成功刷新后解除限制。工程本身打不开时也尝试加载模型设置。
- 审批结果按 COMMITTED/CONFLICT/CANCELLED/REJECTED/未决分别提示；模型设置表单与存储共用校验，异步保存成功才清空 Key 并关闭，忙时禁止编辑/关闭，失败保留临时输入且不放入可恢复状态。
- 校对翻页清除上一页进度，同页完成刷新仍保留；模拟按钮依据完整原文的非空白标记启用，避免截断预览造成误判。删除五语无引用 native_simulation_failed，新增错误说明同步五语。
- NATIVE_STORAGE 的历史阶段描述明确与后续应用 Room v2 区分；仅在本机 .git/info/exclude 排除无关 narou_rust/，未改动嵌套仓库。reasoning_content 保留既有实现，不把供应商适配风险当成可统一删除的字段。
- 新增未运行的配置校验与审批回执/正文篡改核对回归用例；只做源代码/调用点、资源键、XML、差异空白及冲突标记静态核对。本机静态审查，未实际构建，未运行测试/Lint、CI、视觉或设备验证；未调用真实服务商、提交、推送或触发 Actions。


## 26. 第十五轮：§23—25 源码的 CI 验证与三轮修复（2026-10-23）

用户授权提交、推送当前重构分支并触发 `workflow_dispatch`，随后要求继续监控每个 run，失败即定位修复后重跑。

### 提交与运行记录

- `c2b09dc` 提交 §23—25 的源码与文档（50 文件，+2777/−66）。
- [run 39821843207 / #123](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39821843207) 失败：test/lint/release 均停在 `core:agent` 编译，`Message.Assistant` 无 `content` 属性。`09a9b75` 改用 Koog 1.3.0 的 `textContent()`。
- [run 39822583301 / #124](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39822583301) lint/release 通过，test 失败：storage 测试用 `kotlin.io.use` 关闭 Room（2.7 KMP `RoomDatabase` 不再实现 `Closeable`），且 `AgentToolRegistryTest` 仍把新增的 `APPROVAL_PREPARATION` 当作拒绝项。`f4704be` 拆出写/付费/认证拒绝断言并补审批准备用例。
- [run 39824157859 / #125](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39824157859) test 仅剩 4 例失败：测试助手在 Robolectric 主线程直接调 DAO，触发 Room `assertNotMainThread`。`a7ce577` 改为在 `Dispatchers.IO` 打开、执行、关闭数据库。
- [run 39825427020 / #126](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39825427020) **test/lint/release 全部成功**（提交 `a7ce577`）。

### #126 产物与边界

- 单测产物 86 个 suite、825 例，0 失败/错误/跳过；包含 `NativeAgentStoreTest` 17、`TextTranslationIntegrationTest` 6、`NativeWorkspaceScreenshotTest` 7，均实际执行（此前几轮因编译/前置失败未运行）。
- lint 成功：app 0 error、67 warning；release 生成签名 APK（20,769,281 字节），artifacts 已上传。
- `core/storage/schemas/` 仍只有 1.json：该轮 `:core:storage:kspDebugKotlin` 为 FROM-CACHE，2.json 未出现在 CI 产物中。Room v2 迁移已由运行时测试验证，但导出 schema 仍需非缓存构建/PC 生成并审阅，不能声称 2.json 已验证。
- Roborazzi 仍然没有产出 `native-*.png`（`testDebugUnitTest` 不落盘截图，`finalizeTestRoborazziDebug` SKIPPED），组件截图与视觉基线仍为空；本轮界面结论只来自编译与用例断言。
- 未连接真实模型、未发起付费/网络请求、未验证 Keystore 凭据在真机的创建/解密、未做 Agent 对话真机与多设备视觉验收；S1/S2 仍未关闭。


## 27. 第十六轮：当前总进度与证据分层同步（2026-10-23）

用户要求查看最近提交及 Actions 后总结总进度、同步文档。本轮只读核对 Git 和远端运行状态，未修改产品源码。

- 当前分支 `refactor/translation-agent-2026-10`，开始时工作区干净，本地与已记录 origin 均为 `78c380d`。§23—25 已由 `c2b09dc` 提交，后经 `09a9b75`、`f4704be`、`a7ce577` 修复；`78c380d` 仅追加 CI 记录。此前“未提交/未构建”仅保留为各实施轮历史，不再作为当前状态。
- 本轮在线确认 [#126 / 39825427020](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39825427020) 的 test/lint/release 均 success；§26 记录的产物统计为 825 例零失败/错误/跳过、app 67 warning/0 error、签名 Release APK。本轮没有重新下载或逐例解析产物，不新增缓存/逐例运行结论。
- 最新 [#127 / 39826547258](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39826547258) 对应 `78c380d`，本轮查询时三个 job 均 in_progress；这是状态快照，不代表最终结果，本轮不等待或持续监控。
- TXT 手工切片的用户实机确认仍按 §22；模拟翻译、Koog/远程对话、单条审批、会话和迁移已获 CI 证据，不因此继承真实模型/KeyStore/新增界面真机验收。完整 S1/S2 和 98 项功能未关闭。
- Room v2 运行迁移用例已有 CI 证据，但导出 2.json 仍缺；组件测试有断言，截图文件与视觉基线仍未取得。真实供应商、KeyStore 真机、后台执行/恢复、全格式/工作区/旧数据迁移仍是实际缺口。

已同步根规则/README、计划入口和相关专题中的当前状态，完整进度只在本 HANDOFF 维护，历史轮次原验证结论保留。本机仅执行文档结构/链接静态检查、diff 空白和冲突扫描；静态审查，未实际构建。未提交/推送/触发或重跑 Actions，未调用真实供应商、改动凭据或用户数据。


## 28. 第十七轮：持久执行记录与中断核对（2026-10-23）

用户要求“继续推进，与此同时我会做真机验收”。本轮推进持久 Run 基础切片；用户正在验收既有 APK，新源码不继承该设备验收。保留开始时 12 个规则/文档文件的未提交改动。

- domain 增加 RunJournalPort、执行状态/阶段、检查点和进度端口；engine 增加 DurableRunExecutor，先取得运行租约、再登记、后执行，子协程及持久收尾完成后才释放租约。登记失败禁止业务进入；取消保留取消语义，日志收尾失败不泄漏运行占用。
- storage 独立应用 Room 升到 v3，显式新增 v2→v3 迁移及 native_runs 表，v1→v2 保留并链式升级；旧阅读库及 `.lnt` v1 不变。导出 2.json/3.json 仍待 PC/CI KSP 生成和审阅，未手写。此次迁移没有 CI/设备证据。
- Agent 对话、离线查询/准备、允许一次和本地模拟翻译接入持久执行记录；执行编号独立于逻辑 RunId，允许一次沿用原审批绑定并新增执行记录。Agent 请求绑定 attempt/branch，核对现有 PREPARED/DISPATCHED/COMPLETED 回执；已有 requestKey 的编码提到共享函数，保持持久格式不变。
- 模拟翻译在批次提交前持久保存命令编号；重启只读同实例/代际的工程回执，全部提交才补记该批保存条数/修订，未提交不重放、部分回执/读取异常保留需核对状态。遗留运行标记 INTERRUPTED，不自动补历史、重发模型或应用审批。
- Agent 页增加五语最近五次执行、中断及结果不确定说明；运行记录加载失败显示持续错误并禁止新 Agent 操作及模拟翻译，刷新成功后解除。等待审批的执行按真实审批终态核对；当前活跃执行不会被刷新误判为进程中断。
- 新增 DurableRunExecutorTest 5 例、NativeRunStoreTest 8 例与运行恢复 UI 1 例，覆盖登记失败、停止/取消/子任务收尾、日志失败、进程遗留、活跃排除、批次提交故障窗口、实例/代际隔离、v2→v3 保留数据、已知模型回执和审批多次执行；调整既有翻译集成用例接入真实日志及 v1 迁移夹具。所有新增/修改测试均未运行。
- 本轮静态核对源码接线、取消与收尾窗口、Room SQL/Entity 对应、五语资源键/占位符和 XML、文档链接、diff 空白及冲突标记；静态审查，未实际构建，未运行测试/Lint、截图或设备验证。未连接真实模型，未提交/推送/触发 Actions。

契约见 [RUN_RECOVERY](RUN_RECOVERY.md)。本轮仅持久执行审计及中断核对，没有后台 Service/通知、完整持久策略检查点、原 Run 的继续执行、自动补工具历史或完整用量账本；不能关闭 R02/R06、S1/S2 或后台恢复验收。下一步先取得 v3 迁移与新增用例 CI 证据，再推进应用级运行作用域和前台 Service。


## 29. 第十八轮：Agent 页面、HTTP 端点与模型发现（2026-10-23）

用户要求适配 Agent 页面及功能、本地 HTTP 链接、从端点拉取模型等现代 Agent 功能。保留 §28 和原有文档未提交改动；本轮实现端点/模型发现与常规对话交互，没有调用用户服务或付费推理。

- domain 新增共享 NativeModelEndpoint 规范化：显式 HTTP/HTTPS、loopback/LAN/IPv6、反向代理前缀及粘贴操作 URL；基址不猜测 `/v1`，拒绝 URL 凭据/查询/fragment、非法端口及控制字符。配置与聊天、模型列表共用该边界。
- NativeModelSettingsStore 支持空 Key 无认证及同端点显式复用旧 Key；空 Key 不触发 Keystore，不发送 Authorization。非空 Key 保留原 AES-GCM/修订 AAD，端点变化必须重新输入，不把旧 Key 解密回 UI。没有变更 Room v3 的字段、旧阅读库或 `.lnt` v1。
- 从 CompatibleAgentModel 提取共用 CompatibleHttpTransport；聊天与模型发现沿用取消、响应上限、无自动重试/重定向及脱敏分类。NativeModelGateway 新增元数据端口，模型列表只在用户点击后 GET `/models`，20 秒限制，认证占用阻止活动运行竞争；最多 1000 项，严格 ID 校验、去重排序。
- 设置页新增 Ollama/LM Studio 地址预设、HTTP 与本机地址说明、可选 Key/同端点复用、获取/取消、搜索选择与手动输入回退。端点或凭据选择变化后清空旧列表，获取/保存错误留在表单，不重复全局报错；保存成功后才关闭并清空临时 Key。
- Agent 页改为对话时间线 + 固定多行输入/停止栏，展示模型及会话操作、角色消息、文字选择、按 ID 完整消息/思考/工具详情和复制；完整内容仍单条延迟加载，列表仍为轻量预览。助手/工具消息落盘后刷新可见时间线，不伪装为 token 流式。本地演练默认折叠，审批独立展示；中断记录自动展开，切工程清空上一工程的详情/运行预览。
- 五语资源同步；新增端点规范化/无认证 3 例、模型列表夹具 6 例、无 Key 配置持久化 3 例、消息详情绑定 1 例、原生设置/对话 UI 3 例，共 16 个未运行用例。网络夹具由应用拦截器返回，没有真实连接；既有模型校验断言按新明确授权更新，不再把 HTTP/空 Key 当作非法。
- 本轮执行源码引用与回调/接口核对、请求/取消/凭据绑定人工推演、五语 XML/键/占位符静态检查、文档链接、diff 空白和冲突检查；静态审查，未实际构建、测试、Lint、截图或设备验证。没有提交/推送/触发 Actions、连接真实模型或发起付费请求。

详细契约和地址示例见 [MODEL_ENDPOINTS](MODEL_ENDPOINTS.md)。新增界面需要 CI/PC 录制/查看截图、HTTP/IPv6/局域网与键盘真机检查，以及后续明确授权的供应商联调；原有 APK 的用户验收不覆盖本轮。仍为非流式兼容文本/工具接口，附件、分支、输入队列、能力探测、上下文压缩和后台 Service 尚未实现，不能称为完整现代 Agent 或完整 S2。


## 30. 第十九轮：兼容 SSE 流式传输与实时预览（2026-10-23）

用户明确要求“实现流式传输”。沿 §28—29 统一网关、运行记录、凭据与 Agent 对话接线实现真实 HTTP SSE 增量读取；保留全部原有未提交改动，没有运行付费或真实服务商请求。

- CompatibleAgentModel 设置 stream/include_usage，新增 CompatibleChatStream：UTF-8/BOM/跨字节边界、CR/LF、多行 data、注释心跳与 DONE；按工具 index 拼接名字/参数，严格完成后才使用既有 JSON/类型边界。缺失 finish_reason 的流不会成为完整回复，长度/过滤终态保留已知用量并禁止部分工具执行。
- CompatibleHttpTransport 改为 IO 上持有响应与读取生命周期，取消观察者关闭 Call，响应体关闭和协程收尾后才返回；完整结果在 IO 返回取消窗口仍交给请求边界先落盘，部分流不写助手历史、不自动重发。普通元数据仍有原有 20 秒限制；流式总超时 10 分钟/默认空闲读取 90 秒，网络/语义内容另有有界预算，非设备性能结论。
- AgentModelEvent 扩展思考和工具准备元数据，AgentEventSink 增加开始/已记录/已提交回调；AgentRequestBoundary 最终回执/用量保存顺序不变。Koog 继续通过统一原生 complete 接口消费最终工具循环响应，预览不是第二条请求或写入路径。
- AgentStreamPreviewController 按实例/代际/Chat/Run/attempt 隔离 session，旧执行回调/清理不能覆盖新执行；正文和思考各留最新 8192 字符，约 50ms 合并发布。只有可见预览组件订阅高频 Flow，避免整页逐 token 重组。页面显示正文、可展开思考、准备中工具、接收/记录/中断状态；正式消息刷新前保留预览并以消息 ID 去重。
- 停止/失败后的预览明确未提交，仅保留当前 ViewModel 内存；不进入 Room、SavedState、工程或模型历史。启动新操作/切工程/会话清除。审批、唯一工程写入口、已提交事实及请求不确定状态不变。本轮无数据库字段或版本变更。
- 新增 CompatibleChatStreamTest 8 例、CompatibleAgentModelStreamingTest 6 例、AgentStreamPreviewControllerTest 4 例、AgentRequestBoundaryTest 2 例及流式原生 UI 1 例，共 21 个未运行用例；覆盖 UTF-8/分片/工具/用量/截断、同响应 JSON 兼容（不发第二次请求）、取消资源关闭/已知结果、预览限额/归属及回执前后分离。网络夹具全部来自拦截器或内存流。
- 本机只做源码/接口/调用点、资源与文档、分隔符、diff 空白及冲突静态检查；静态审查，未实际构建、测试、Lint、生成/查看截图或设备验证。未提交/推送/触发 Actions；未联调真实供应商或调用付费模型。

详细协议、预算和验收清单见 [STREAMING](STREAMING.md)。当前不再是“仅消息级刷新/无流式”的源码，但不能把旧 APK 或历史 CI 视为本轮证据；仍需 CI/PC 与真机检查 SSE/思考/工具/取消/断网。附件、分支、队列、自动恢复/重试、其他协议及后台 Service 仍未实现，不关闭完整 S2。

### §28—30 审查修复（2026-10-23）

- 兼容 SSE 的 `function_call: null` 视为未提供，普通文本正常累计；非空旧式函数调用仍以 CONTENT 拒绝，不执行工具。
- 将用户操作 URL 的单次后缀剥离与已保存基址校验分开；聊天、模型发现和凭据比较不再二次剥离代理前缀。设置重新打开时回填完整聊天操作 URL，保证再次保存/模型发现保留基址。
- 审批调用把 apply 的实际结果传给持久执行记录：COMMITTED 为 SUCCEEDED、CONFLICT 为 FAILED、CANCELLED/REJECTED 为 CANCELLED，尚未终结的审批保持 WAITING_APPROVAL；异常、停止与不确定回执仍由原收尾逻辑处理。
- 新增未运行的 SSE 空值/非空拒绝、单次实际传输路径、代理前缀持久化重开/凭据绑定、非异常审批结果及真实存储冲突双执行记录回归用例；扩展既有端点与设置表单用例。网络夹具只用内存流/拦截器，不连接真实服务商。
- 本机仅做静态结构/符号/调用链、diff 空白与冲突标记检查；静态审查，未实际构建，未运行测试/Lint、CI 或设备验证。保留原有未提交改动；未提交、推送或触发 Actions。

### §28—30 第二轮审查修复与当前测试库存（2026-10-23）

- SSE 工具函数名与 ID 使用相同的重复完整值规则：与当前累计名字完全相同的值不再追加，仍支持 `read_` + `item` 的真正名字分片；参数继续逐片累计。新增内存流夹具覆盖重复完整名、分片后重复完整名及参数保真，原有交错工具分片夹具保留。
- Agent 页面按匹配工程实例/代际/Chat、且尚未出现在正式消息中的预览决定是否创建列表项；页面只收集去重后的可见性布尔值，逐 token 渲染仍在预览组件内。新增组件回归比较未接流与空流的后续按钮位置，并覆盖归属不符、已保存、显示后清除，防止空列表项增加 12dp 间距。
- AGENT_CONVERSATION 与 ARCHITECTURE 明确当前应用 Room 为 v3，模型/会话/审批表在历史 v2 引入；v2 历史 CI 证据与未构建的 v3 增量分开。保留 v1→v2→v3 显式迁移及 2.json/3.json 待生成的边界，没有数据库或迁移代码变更。
- 总超时误分类的审查意见未确认：OkHttp 正常网络路径在收尾时把总超时包装成 InterruptedIOException，现有分类已映射 TIMEOUT，本轮不修改传输层。

§28—30 原正文的“本轮新增”数量保留为各开发轮初始快照，后续审查补测纳入下表。以下为相对 `78c380d` 的当前源码新增 `@Test` 方法库存，**不是实际执行数量或通过证据**：

| 原切片及后续修复 | 当前新增方法构成 | 合计 |
|---|---|---|
| §28 持久运行 | DurableRunExecutorTest 6、NativeRunStoreTest 9、运行恢复 UI 1 | 16 |
| §29 模型发现与对话 | 端点校验 3、CompatibleModelCatalogTest 7、NativeModelSettingsStoreTest 4、消息详情绑定 1、设置/对话 UI 3 | 18 |
| §30 流式传输 | CompatibleChatStreamTest 10、CompatibleAgentModelStreamingTest 7、AgentStreamPreviewControllerTest 4、请求边界 2、流式 UI 2 | 25 |
| 总计 | 含两轮审查新增回归，不重复计算已有方法的断言修改 | 59 |

其中端点校验类共 5 例、NativeAgentStoreTest 共 18 例、AgentRequestBoundaryTest 共 16 例、NativeWorkspaceScreenshotTest 共 13 例；表中分别仅计新增的 3、1、2、6 例。第一次审查修复后的 §29/§30 累计为 18/23，本轮新增工具名与布局回归 2 例后 §30 为 25。

本机仅做源码/调用点与方法计数、结构/文档链接、diff 空白和冲突标记静态检查；静态审查，未实际构建，未运行测试/Lint、生成或查看新截图、设备验证或供应商联调。新增布局用例仍需 CI/PC 执行与设备视觉检查。保留其他未提交改动，未提交、推送或触发 Actions。


## 31. 第二十轮：首次全链路 CI 结果与设置表单 idle 修复（2026-10-23）

- 提交 `afc513d` 推送 `refactor/translation-agent-2026-10`，因该分支不在 push 触发列表，用 `workflow_dispatch` 触发 [#39889698588](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39889698588)：`release`、`lint` 成功（签名 APK 与 Lint 均产出，整库可编译），`test` 失败。
- 失败只涉及本轮新增的两个组件用例：`NativeWorkspaceScreenshotTest.savedCredentialCannotFollowAnEditedEndpointImplicitly` 与 `localModelDiscoverySelectsAnIdAndSavesWithoutAKey`，都在 `setContent` 的首个 `waitForIdle` 上 60 秒 `AppNotIdleException`（约 12 万次轮询仍未 idle，提示 measure/layout 不收敛）。
- 第一次假设（`AlertDialog` 文本槽的固有宽度测量与滚动内容互相触发）已由第二次运行否定：`8f91273` 把表单改成有界 `Dialog` + `Surface` 后 [#39891090794](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39891090794) 仍在同一位置失败，`release`/`lint` 仍通过。
- 临时探针 `TempDialogIdleProbeTest`（`5d76b0f`，[#39891957322](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39891957322)，idling 超时收紧到 5 秒）逐项结果：对话框纯文本**通过**；普通文本框、`singleLine`+Uri 键盘、密码变换、`rememberSaveable`、限高滚动三文本框、完整表单全部失败；`DialogProperties(usePlatformDefaultWidth = false)` + 滚动三文本框**通过**。结论：Robolectric 下 wrap-content 对话框窗口只要包含文本框就不收敛，与既有 `Editor`（固定窗口）一致；与 `AlertDialog`、限定宽度、滚动、密钥字段无关。
- 最终处理：设置表单与模型选择统一为 `DialogProperties(usePlatformDefaultWidth = false)` + 全屏 scrim + 居中卡片（宽 280—560dp、表单滚动区限高 420dp、允许一次/取消固定在滚动区外、点击 scrim 关闭）。字段、校验、同端点凭据复用、错误呈现、按钮启用条件与五语文案均未改变。临时探针已删除。
- 证据边界：新壳层尚未构建与测试，结论以再次重跑的 Actions 为准；组件截图与真机视觉（含横屏、点击外部关闭、键盘布局）仍需 CI/PC 与设备检查。无数据库、迁移、请求协议或安全边界变更。

## 32. 第二十一轮：核对第四次 CI 失败并审查测试（2026-10-23）

用户要求修复并彻底检查其他测试；本轮未获提交、推送或触发 Actions 授权，仅修改工作区与静态审查。

### 已有提交的全量报告

- 在线核对 `a9c2530` 的 [#131 / 39892879953](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39892879953)：release/lint 成功，签名校验及 APK 上传成功，test 失败。本轮下载并解析该次上传的全部 93 份 JUnit XML：app 660 项、core:agent 25 项、core:storage 67 项、core:domain 66 项、core:engine 66 项，共 884 项，1 项失败，0 error、0 skipped。这些是 `a9c2530` 的历史执行证据，不覆盖本轮工作区修改。
- 唯一失败为 `NativeWorkspaceScreenshotTest.localModelDiscoverySelectsAnIdAndSavesWithoutAKey`：原第 155 行找不到 `fixture-local-model`。该类其他 12 项通过，包含此前 idle 超时的凭据用例；此次没有 `AppNotIdleException`。原用例缺少发现回调次数、选择器出现及返回表单的中间断言，现有报告不能单独判定是发现按钮触摸未生效还是选择器未呈现，不能把猜测写成已证实根因。

### 工作区修复及测试审查

- 设置表单和选择器改在同一个固定窗口 Dialog 内切换，取消选择只返回原表单，字段及表单滚动状态保留。移除全屏父点击/卡片空点击的结构，遮罩改为卡片后方的兄弟节点；Material Surface 自身隔离背后的触摸。表单/列表滚动区按剩余高度分配，保留底部操作按钮，并处理 IME 留白。以上是本轮实现调整，效果待新 CI/设备验证，不声明已证实解决缺失节点。
- 原失败用例增加发现触发、单窗口、选择器/表单互斥、可见模型、保存次数与关闭完成断言；保存夹具调用真实完成回调。截图改为显式捕获表单节点，避免仍有 Dialog 时默认 `onRoot()` 的多根歧义，也避免保存关闭后捕获空背景。阶段断言失败时将全部未合并语义根写入测试错误，方便下一次 CI 定位。
- 新增 3 个尚未运行的 UI 回归：延迟回调/加载状态、横屏小视口、80 项列表滚动和搜索/无匹配、选择返回保存；取消及外部点击返回/关闭、不重复获取、卡片内容点击不穿透；空列表仍可手填模型。保留触摸点击，不改用语义 OnClick 绕过真实点击链路，不加 sleep 或扩大 idle 超时。
- 逐项静态检查相对 `78c380d` 改动的 12 个测试文件：原生 UI、请求回执边界、端点校验、流式预览、SSE/兼容传输、模型发现、持久运行、消息详情/凭据存储/运行恢复及翻译集成。核对夹具、调用链、断言契约、取消/等待、IO/Room 线程及资源关闭；未更改已通过用例的业务期望。
- 其他补强：凭据 UI 用例增加两次保存的计数和新端点断言；流式取消回执用例将 worker.join 纳入既有 5 秒超时；消息详情用例补不同 recovery generation 的 WRONG_IDENTITY 断言；端点/Key 校验用例补异常堆栈不得含提交值的脱敏断言。

### 验证边界

本机仅做静态审查，未实际构建、测试或 Lint；`tools/rebuild/check_structure.py`（仅文本结构、依赖/导入边界、Kotlin 分隔符、文档/CI 任务引用）及 `git diff --check` 通过，冲突标记扫描未发现匹配。另从 Google Maven 的 Compose ui-test 1.7.0 源码核对新增触摸扩展/查询 API 的导入方式。没有运行 Gradle、单文件 Kotlin/JUnit、真实供应商请求或设备交互，没有生成/查看本轮截图。新增 UI 用例和改动后的既有用例均待 CI/PC 执行；不能将旧提交的 883 项通过当作新代码通过。本轮不关闭 S1/S2 或完整产品验收，未提交、推送或触发 Actions。


## 33. 第二十二轮：最新 CI 全绿与总进度同步（2026-10-23）

用户要求查看 Actions、总结当前进度并同步文档。本轮开始时工作区干净，本地与 GitHub 分支均为 `f766cee457a861a79b138b71e694f97e7686c056`；该提交已经包含 §32 的单窗口设置/模型选择、3 个新增 UI 用例和其他测试补强。本轮不修改产品或测试源码。

### CI 与产物证据

- 最新 [Actions #132 / 39895675399](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39895675399)，workflow_dispatch，目标分支 `refactor/translation-agent-2026-10`，北京时间 2026-10-23 11:33:45 创建、11:39:41 更新结束；test、lint、release 全部 success。此前 #128—131 的失败是历史记录，当前不再有这几次设置表单/模型选择用例的失败。
- 本轮下载并解析该次全部 93 份 JUnit XML，汇总如下。以下数量来自远端已执行报告，不是本机测试或源码方法计数：

| 模块 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| app | 663 | 0 | 0 | 0 |
| core:agent | 25 | 0 | 0 | 0 |
| core:storage | 67 | 0 | 0 | 0 |
| core:domain | 66 | 0 | 0 | 0 |
| core:engine | 66 | 0 | 0 | 0 |
| 合计 | 887 | 0 | 0 | 0 |

- NativeWorkspaceScreenshotTest 全部 16 项通过，包括原失败的 localModelDiscoverySelectsAnIdAndSavesWithoutAKey、savedCredentialCannotFollowAnEditedEndpointImplicitly，以及新增延迟回调/横屏小窗口/80 项滚动搜索、取消及外部点击、空列表手填。证明这些合成组件流程在本次 CI 环境通过；不据此反推前一版缺失节点的唯一根因，也不等同真机视觉。
- NativeAgentStoreTest.migrationFromV1KeepsProjectIndexAndAddsAgentTables 和 NativeRunStoreTest.migrationFromV2PreservesProjectAndAgentRecordsAndAddsRunJournal 均通过；持久运行、SSE/传输/预览、回执取消及审批冲突等用例同样纳入上述报告。Room v3 及 §28—30 增量不再是“未构建/未运行测试”。
- Lint 上传的全部 5 份 XML 均为 0 Error/0 Fatal；app 有 69 Warning，其余上传模块为 0 Warning。不登记为零警告。
- release 的签名校验、构建、APK 签名验证及上传均 success；产物名为 `LightNovelTranslator-refactor-translation-agent-2026-10-signed`（artifact 12477270096）。本轮只核对产物元数据与 job 步骤，没有安装或发布 APK。
- test-reports 仍只包含既有 `core/storage/schemas/...NativeAppDatabase/1.json`，没有 2.json/3.json；也没有 `native-*.png`。该次 test 日志中 `:core:storage:kspDebugKotlin` 为 FROM-CACHE、`:app:finalizeTestRoborazziDebug` 为 SKIPPED，下一轮需专门生成 schema 和录制截图。运行迁移通过不代表 schema 导出完成，组件断言通过不代表截图/视觉验收。

### 当前结论与文档同步

§28—32 已提交并经本次 CI 覆盖；TXT 手工设备证据仍限定于 §22，模拟翻译/远程 Agent/新执行记录和设置/流式界面尚无新增设备验收。真实供应商、KeyStore、schema 导出、组件截图、后台 Service/同 Run 接续、完整调度/用量、工作区/技能/联网、全格式/.lg/Pi、正式旧数据迁移和 G1—G5/全功能验收继续开放，不关闭 S1/S2。

同步 HANDOFF §3—4 及本节，根 README、AGENTS 的源码地图/版本事实、计划入口与相关契约文档改为指向本节，移除“当前增量尚未构建”的过时表述；§28—32 各开发轮原始证据保留为历史。本轮 `tools/rebuild/check_structure.py`（仅文本结构/依赖与导入边界/文档引用静态检查）和 `git diff --check` 通过，冲突标记扫描无匹配。未在本机执行构建、测试或 Lint，未提交、推送、触发新 Actions 或发布。


## 34. 第二十三轮：本页模拟翻译的应用级执行与前台 Service（2026-10-23）

用户要求继续推进。开始时分支/HEAD 仍为 `refactor/translation-agent-2026-10` / `f766cee`，保留上一轮已存在的 16 份未提交文档修改。本轮推进 R06/R13 的本地模拟前台执行切片，不关闭完整 G5/S1，也不迁移 Agent 的付费对话生命周期。

### 实际改动

- engine 新增 ForegroundTranslationRunner：同步预留真实 Runtime 租约，固定工程实例/代际、命令、配置和页范围；应用作用域 ATOMIC 启动并登记持久执行，等待同令牌前台确认后才进入既有翻译业务。10 秒启动等待上限、显式启动失败、同命令重附着/差异拒绝、旧令牌和迟到进度隔离均在执行层处理，不自动重试。
- DurableRunExecutor 增加 engine 内部已预留租约接入；TextTranslationService 提取同一条受控页处理逻辑，前台入口不二次预留或重复登记。原有批次事务、回执、停止后不新增操作及已提交事实保持；没有绕过共享模型网关或 ProjectWriteStore。
- app 新增 non-exported/dataSync/非 sticky NativeTranslationService；成功 startForeground 才打开任务工作门。通知只有模拟状态/计数和停止/返回入口，Intent 只含令牌；页面停止、通知停止、onDestroy 和 Android 15+ onTimeout 均作用于正确执行。停止时持有占用直到实际工作/持久日志收尾；系统超时立即停止 Service，不承诺保活。
- NativeAppGraph 拥有应用级执行作用域。模拟页不再由 ViewModel 的 job 持有业务工作；界面只等待/观察，控制器销毁不等于停止任务。同进程重建可按 handle 恢复对应工程/页，避免目录恢复写锁与活动运行冲突；任务结束后重读已保存条目和运行记录。
- Manifest 增加前台服务/dataSync 和通知权限声明；校对页的模拟确认框增加可滚动的五语后台说明与可选通知授权按钮。权限申请与任务启动分开，授权回调不启动任务，拒绝通知权限也不产生隐式取消/重发。Agent/其他文件操作生命周期保持原链路。
- 新增 11 个尚未运行的用例：执行器 7、实际存储集成 1、平台接线 2、原生组件 1；覆盖启动确认前无副作用、占用/重复/冲突、等待者取消与重附着、启动失败/超时、收尾前不释放、取消父作用域、旧进度/令牌、单持久执行提交、Manifest/无自动恢复与可选通知不启动模拟。原有用例及其业务期望保留。

详细契约、官方平台依据和设备矩阵见 [FOREGROUND_TRANSLATION](FOREGROUND_TRANSLATION.md)。工作区新增/改动源码尚未构建，§33 的 887 项通过仅属于 `f766cee`。本机 `tools/rebuild/check_structure.py`、Manifest/前台类型与权限 XML 核对、五语 7 个新增资源键核对及 `git diff --check` 通过，冲突标记扫描无匹配；均为静态审查，未实际构建、测试、Lint、录制/查看组件截图或设备验证，未调用真实供应商。

剩余：新 Service/权限/通知/Activity 重建和系统限时必须经授权 CI/PC 与真机验证；Agent 前台化、应用级完整调度/队列、暂停/检查点/原 Run 跨进程接续仍未实现。进程死亡仍只核对回执，不自动重放；schema 2/3 导出、截图及真实供应商、全格式/工作区/旧数据迁移缺口不变。未提交、推送、触发 Actions 或发布。

## 35. 第二十四轮：前台启动仲裁与宿主生命周期加固（2026-10-23）

用户要求继续下一轮改进。本轮保留 §33 的文档同步与 §34 全部未提交源码；分支/HEAD 仍为 `refactor/translation-agent-2026-10` / `f766cee`。优先补牢前台执行的启动、连续任务切换与停止收尾，不新增模型付费入口、不改变数据库 schema。

### 根因与实际改动

- ForegroundTranslationRunner 原 startFailed 只完成等待门，没有在锁内关闭 WAITING 状态，失败已接受但工作协程尚未收尾时，迟到的前台确认仍可发布 RUNNING。现在前台确认/失败由同一状态转换仲裁：失败或超时先转 STOPPING，拒绝迟到确认，最终 FAILED；预留一直持续到持久收尾。Deferred 完成与 Flow 发布保持在锁外，避免持锁恢复业务协程；结束处理增加幂等防护。
- Service 原退出判断依据“全局是否有活动任务”，使尚未拥有执行的旧 Intent 可能在新任务待启动时空挂；原订阅只接受同令牌状态，旧终态被 StateFlow 合并后还可能遗留旧通知。新增 NativeTranslationHost 管理 Service 主线程上的平台生命周期，区分实际拥有任务与全局待启动任务。空/旧/未知命令关闭未拥有任务的宿主；旧停止令牌不取消新任务；终态被合并或新 START 先到两种顺序均释放/替换旧订阅，退出使用最新 startId。
- NativeTranslationService 保留 Manifest、通知权限与 dataSync 接线，委托宿主处理命令、销毁和系统超时；平台提升成功后才确认执行，通知更新失败则停止所属执行并退出 Service。正常停止仍等持久收尾后撤通知，系统超时不能等待数据库收尾；宿主退出后应用作用域继续受控清理。
- 持久记录写入失败时继续保留已提交进度、报告失败；实际请求中停止仍保留已提交批次，未记录响应的 REQUESTING 继续按既有契约标 INTERRUPTED/需核对，不能因为执行器显示 CANCELLED 而清除未决事实。

### 待运行用例与验证边界

本轮新增 12 个待运行用例：engine 3（失败后确认拒绝/收尾占用、确认后迟到失败、日志最终写入失败保留进度），app 宿主控制器 8（前台门/重复启动、未拥有任务的无效命令、旧停止与正确停止、终态合并与新 START 两种次序、提升失败、通知失败、超时/销毁），storage 1（第二批请求中停止保留第一批、取消 UI 等待不停止任务、持久未决状态与核对不重发）。§34—35 累计新增 23 个用例，均未运行；原有测试及业务期望保留，未删测试或降低验收要求。宿主夹具是确定性生命周期回归源码，不代替真实 Android Service/通知验证。

静态检查：`tools/rebuild/check_structure.py`、`git diff --check` 通过；冲突标记扫描无匹配；逐项核对新增用例使用的域模型、挂起接口、协程依赖与持久记录字段。进程检查未发现 GradleDaemon。本机仅做静态审查，未实际构建、运行测试/Lint、录制或查看组件截图、验证设备/供应商。§33 的 CI 证据仍只覆盖已提交的 `f766cee`，不覆盖本轮及 §34 源码；未提交、推送、触发 Actions 或发布。

待外部验证：先由 PC/获授权 CI 运行全量 test/lint/release 及这些新增用例，再完成前台切后台、通知拒绝/停止、Activity 重建、连续启动、系统限时、进程死亡的设备验收。Agent 的 ViewModel 执行、无 WakeLock、无队列/暂停/原 Run 跨进程接续等限制继续保留；完整 S1/S2/G1—G5 不关闭。

## 36. 前台重附着竞态与审查问题修复（2026-10-23）

用户要求修复重附着的 STALE_PAGE 竞态，并核查恢复告警、选中 chat 清理、启动失败期间重复取消及 ViewModel 用例缺口。静态追踪确认这五项均存在（选中 chat 残留目前未造成已知生产调用故障）。本轮保留 §33—35 的既有未提交修改；不提交、不推送、不触发 Actions。

### 实际改动

- domain/storage 新增 TextProjectPort.readPage / TextProjectPage，在同一 SQLite 事务和适配器操作锁内读取 summary 与有界 items；NativeProjectViewModel 的 openInternal/loadPage（含 restoreSimulationPage 和终态刷新）改用该端口，去掉两次读取之间的修订竞态。原 items 的预期修订检查及全部写入检查仍拒绝过期值，没有放宽冲突契约或更改 schema。
- 初始化在任何 IO 前确定并持有活动 handle。工程列表/打开/恢复分页失败只显示五语专用刷新提示，仍进入 awaitSimulation 观察任务、保持忙碌；即使工程摘要为空，页面取消也按已附着 token 停止执行，不取消等待者而遗留后台任务。终态独立刷新恢复目录、分页及持久记录，不让刷新失败替换任务启动失败/取消原因。
- recoveryProblems 写入 SavedStateHandle 并用于重建初值；遇活动任务跳过目录恢复时不再清零，标记待恢复。任务完成并释放真实租约后补做恢复及索引刷新；失败保留上次问题数和待恢复标记，下次初始化/刷新仍可重试。界面已经销毁且任务尚未结束时不由旧 ViewModel执行恢复，下次初始化重新核对。
- RuntimePort.finishRun 增加可选的期望 chat 清理参数，由 DurableRunExecutor 随租约传递，在 RuntimeCoordinator 实际释放租约的同一锁内比较并清空。成功、取消、启动失败及日志失败均走该释放路径；资源未释放前保留选择，重复 finish 不覆盖首次策略，旧租约不能清掉新选择。
- ForegroundTranslationRunner 记录已接受的启动失败；此后再次停止只确认已有收尾，不取消失败收尾协程、不改变持久 FAILED 或执行器 FAILED。失败终态优先于后来的 stopRequested，仍拒绝迟到启动确认。
- 提取无 Android 依赖的 simulationCursors 校验：绑定 token、首项 0、末项固定 afterId、严格递增；无效历史仅回退到本次运行页，不重建/重发任务。ViewModel 保留生产双参数构造入口，增加内部端口注入入口供真实存储夹具使用。

### 用例与验证边界

新增 8 个待运行用例：游标纯函数 3、ViewModel/Robolectric 2、engine 执行器 1、Runtime 1、storage 1；补强既有成功/取消用例的选中状态断言。ViewModel 用例使用真实 SQLite/Room、本地模型及可控挂起门，在读取前后实际提交一个批次，再销毁并重建 ViewModel，核对页范围、handle/进度、不重发、告警保留及终态目录恢复；另一用例覆盖全部页面读取失败时仍可停止、恢复失败保留告警。执行器用例分别覆盖失败已接受但异常尚未处理、日志收尾已挂起时的重复停止。Runtime 用例覆盖延迟资源释放、重复 finish 和旧租约隔离；存储用例保留旧修订拒绝并检查最新一致页。

本机完成 `tools/rebuild/check_structure.py`（结构/依赖与导入边界/符号/括号/文档引用静态检查）、五语资源 XML 及键一致性核对、`git diff --check` 和冲突标记扫描。均为静态审查，未实际构建、运行测试或 Lint；上述新增用例尚无运行证据，§33 的 CI 不覆盖本轮源码。未录制/查看新截图，未验证真实 Activity 重建、Service/通知、设备或供应商。仍须由 PC/获授权 CI 运行新增及全量任务，并完成前台运行中的 Activity 重建和停止真机验收；完整后台恢复/队列/Agent 前台化等既有缺口不变。

## 37. TXT 校对的状态/文本查询、统计与固定编辑窗口（2026-10-23）

用户要求继续剩余待实施与补全。本轮基于已取得 CI 的 TXT 存储与人工编辑基础推进 R15/R32 查询切片，保留开始时 §33—36 的全部未提交修改（包括新增重附着修复和对应测试），不扩展尚未验收的 Service 为付费 Agent 执行。分支/HEAD 仍为 `refactor/translation-agent-2026-10` / `f766cee`。

### 实际改动

- domain 新增 TextItemQuery、TextSearchField、TextItemStatistics 和 TextItemWindow；查询文本有长度/Unicode/NUL 边界，诊断不包含文本。扩展 TextProjectPage/端口，支持同快照统计和固定窗口刷新；窗口限定严格递增、去重且有界的稳定 ItemId，不使用行位置授权。
- NativeTextProjectStore 新增 queryPage/readItemWindow；状态和原文/译文子串条件取交集，参数绑定的 instr 按字面搜索，不解释用户正则、SQL 或通配符。摘要、总命中、当前页与全工程状态计数在同一事务内读取，afterId 只影响页面不影响总计；不改 schema、迁移或旧 items 修订门禁。
- NativeProjectViewModel 新增显式查询与身份绑定的条件恢复；应用新条件/清除后从第一页开始，失败保留上一窗口/条件/游标。手工保存后刷新固定成员及统计，编辑后暂不满足条件的条目仍留在本页；重新查询/翻页/显式刷新才重选。提交后读取失败仍保留已保存预览/修订，清空过期统计并提示错误。
- 原生校对页新增可滚动筛选/统计对话框，包含状态、原文/译文/两者、字面搜索、总计与应用/清除；输入和下拉变化不访问工程，取消不应用。新增 11 个资源键并覆盖五语；新增组件小横屏与截图捕获入口，尚无截图产物或视觉结论。
- 筛选状态不在原有本页模拟请求契约内，故 UI 和 ViewModel 均拒绝在筛选窗口启动原始页模拟，并明确提示清除筛选；不隐式扩大操作范围，不新增付费调用。既有活动模拟重附着仍读取固定原始页，不创建/重发任务。

详细契约、搜索依据、窗口/恢复边界和剩余校对能力见 [PROOFREADING_QUERY](PROOFREADING_QUERY.md)。完整 R15/R32、S1/S2 和 G1—G5 仍开放；资产/警告/术语筛选、批量编辑/替换/质量告警、全格式和完整后台等缺口继续保留。

### 用例与交付边界

新增 17 个待运行用例：domain 3、storage 7、ViewModel 3、组件 4，覆盖字面/输入边界、计数/游标/字段/Unicode、固定窗口/编辑/故障保留、跨工程条件恢复与显式表单操作；组件捕获路径为 `app/src/test/screenshots/native-proofreading-query.png`，尚未录制或查看。

静态验证：`tools/rebuild/check_structure.py`、`git diff --check` 通过，冲突标记无匹配；五语新增键和格式占位符一致性核对通过，核对唯一存储实现、委托测试端口、构造参数与已有调用兼容性；未发现 GradleDaemon。本机为静态审查，未实际构建、运行测试或 Lint，未做设备/供应商验证；§33 的 887 项 CI 结果不覆盖 §34—37 源码。未提交、推送、触发 Actions 或发布，未改用户数据与 LG 仓库。

下一步仍需 PC/获授权 CI 运行全量与新增用例、生成并实际查看截图，以及设备上的输入法/小横屏/翻页与保存后窗口行为验收；Room 2/3 schema 导出、Agent 应用级执行、完整调度/队列/检查点、复杂格式/工作区和正式旧数据迁移仍未完成。

## 38. TXT 当前页批选、原子状态/清空与同批次核对（2026-10-23）

用户要求继续推进。本轮在既有 TXT 人工写入及 §37 查询窗口上补 R15/R32 的当前页批量操作，保留 §33—37 全部未提交修改。分支/HEAD 仍为 `refactor/translation-agent-2026-10` / `f766cee`；本机不构建，不提交、推送或触发 Actions。

### 实际改动

- domain 新增 ManualTextBatch、TextBatchTarget、TextBatchChange 和 ManualBatchReceipt，绑定逻辑命令、完整工程实例/代际、修订、操作及有界不可变目标/指纹。四种操作为未处理、已处理、排除、清空译文并重置为未处理；准备只读、剔除空操作，没有实际差异不写回执或递增修订。
- NativeTextProjectStore 增加 prepareBatch/editBatch，ProjectWriteStore 在既有人工 Runtime 写租约下，以同一外层事务先校验全批再逐条写入；任一成员、指纹或修订冲突拒绝整批，取消/异常回滚条目、历史、修订和回执，不放宽原单条写契约，不提供 Agent 审批旁路。
- 校验 .lnt 导入链路时确认成功历史要求每条回执保留标准 ManualTextEdit 摘要，故批次 payload 绑定放入生成的子命令 ID，成功回执仍用标准单条摘要；保持 v1 schema/导入校验不变。逻辑命令的实例/代际前缀、全量 payload 哈希和索引约束用于核对完整回执集合；单条/批量命令复用、差异 payload 或部分回执均拒绝，不补写部分结果。重复确认已提交/拒绝的批次返回原结果，不覆盖后来的修改。
- NativeProjectViewModel 新增当前页显式选择、准备/确认/关闭与冲突预览；未知 ID 不进入选择，“选择本页”不包含隐藏前瞻或其他命中页。批次准备与表单选择不写工程；确认后保持查询窗口成员并刷新事实/计数。已知提交后读取失败保留修订/预览，清空过期计数，并明确提示批次已应用而刷新失败。
- 有版本、边界校验的 SavedStateHandle 仅保存预览元数据；重建匹配完整工程身份后返回校对预览，不自动提交。提交确认丢失时保留原命令/固定 payload，可显式核对同批次；冲突预览禁用确认，关闭、刷新后重新准备。核对旧成功回执不把旧预览覆盖到已读较新修订。
- 原生校对页新增选择模式、复选框、四种操作菜单和独立确认对话框；清空有明确警告，操作菜单不直接执行。确认/关闭绑定固定预览 payload，拒绝旧预览迟到回调对新批次执行或清除。新增 20 个资源键覆盖五语；组件有小横屏和 `native-proofreading-batch.png` 捕获入口，尚未录制或查看。

详细契约见 [MANUAL_BATCH](MANUAL_BATCH.md)。当前范围是本页固定选择，仍不包含跨页/全部筛选结果、查找替换、完整质量告警、宽屏校对表格或 Agent 全对象审批；不关闭 R15/R32/S1/S2。

### 用例与验证边界

新增 20 个待运行用例：domain 2、storage 8、SavedState 纯函数 2、ViewModel 5、组件 3。覆盖 payload 绑定/边界、空操作、保留译文与清空、全批冲突、取消回滚、成功/拒绝终局重放、单条复用/部分回执、运行占用与错误代际、成功/拒绝回执保存重导入、确认丢失后的重建核对、提交后刷新失败、选择不编辑及独立确认/忙碌禁用。

本机静态检查：`tools/rebuild/check_structure.py`、`git diff --check` 通过，冲突标记扫描无匹配；五语新增键与格式占位符核对一致，复核端口实现/委托、既有组件参数兼容、v1 schema/导入摘要和快照链路。均为静态审查，未实际构建、运行测试/Lint、生成或查看截图、验收设备/供应商；§33 CI 不覆盖本轮及此前未提交源码。未改真实用户数据或 LG 仓库，未提交、推送、触发 Actions 或发布。

后续须在 PC/获授权 CI 执行新增及全量任务、录制并查看截图，再验收手机触屏/无障碍、横屏、取消/重建与保存重导入。Room schema 2/3、Agent 应用级执行、完整调度/队列/检查点、复杂格式/工作区及旧数据迁移等缺口保持开放。


## 39. 共享调度、整工程模拟、用量事实与字幕核心（2026-10-23）

用户要求持续推进并尝试一次性补全余项。本轮连续修改多个基础链路，保留 §34—38 及其他既有脏文件；没有把完整目标缩成这些切片，也没有完成全部剩余任务。分支仍为 `refactor/translation-agent-2026-10`，HEAD 仍为 `f766cee`，没有提交、推送或触发 Actions。§33 的 CI 证据不覆盖本轮源码。

### 实际落地

1. **共享请求调度**：domain 增加固定限额，engine 增加进程内 FIFO、固定并发、显式 RPM/默认隐藏 RPS、取消清理和有界资源池；Agent 和翻译推理入口均接入同一网关中的调度器。同配置 ID 的不同修订共享时钟，不借保存配置重新获取突发额度；无隐式重试或 Key 轮换。当前装配为固定并发 4、RPM 0，没有自动探测/可编辑限额页面。
2. **Agent 实际派发边界**：沿生产链追查到 AgentRequestBoundary 原先在进入网关前登记 DISPATCHED。增加 domain 的 DispatchAwareAgentModelPort，在队列资格取得后才调用持久 CAS、创建派发操作和启动预览；准备操作贯穿等待，阻止旧租约被同名字面身份替换。停止在准备/排队阶段不冒充已发送，登记后与 HTTP 之间的取消仍保守标为不确定。网关在持久回调前后都重查 Stop；同 attempt 并发副本只能派发一次。完整响应仍优先持久化，不因调度收尾丢失。
3. **整工程本地模拟**：TextTranslationService 增加 translateProject / translateReservedProject，单一持久 Run 内按修订和递增 ID 逐页取数；页面全已处理、排除或空白时继续扫描下一页。逐批提交沿用原唯一写入口，计数/用量跨页累计，后续失败/停止保留前批。ForegroundTranslationRequest 将 PAGE/PROJECT 固定进重复命令比较；PROJECT 仅允许从零游标开始。仍不接远程付费翻译、不自动跨进程继续。
4. **原生范围 UI**：模拟确认框增加当前页/整工程选择，选择本身不启动任务；明确保留已处理/排除条目、逐批保存、停止语义和回到第一页。当前页没有待处理项而工程仍有未处理项时可进入整工程确认；筛选、批选、编辑草稿和未决人工批次继续阻止启动。共新增 3 个范围资源键，覆盖五语。
5. **用量事实**：增加不可变 RequestUsageTotals，五类分量分别保留 ACTUAL、ESTIMATED 与 UNKNOWN 响应数；BigInteger 保留 Long 之上的精确 Token 数，兼容 RequestUsage 投影溢出变未知而不打断写回。运行快照携带对应 Run，UI 用滚动详情对话框查看，状态绑定完整工程及运行命令；新增 8 个五语资源键。当前仍是本次运行汇总，不是持久 UsageLedger、费用或失败调用账本。
6. **四种字幕纯文本核心**：原先无主源码的 core/formats 增加 SubtitleDocument、SRT/LRC/ASS/SSA 解析及单语/双语渲染。引用绑定整个已解码原稿、源位置和源文指纹；全量校验未知/重复/跨原稿引用。保留非正文切片、源控制字段、BOM/行尾/无末尾换行；SRT 空结果删 cue 并重编号，LRC 多行重复源时间组并阻止时间注入，ASS/SSA 按 Events/Format 边界定位末列 Text。加入严格编码、行数、目标及输出上限和 LRC 展开前预算。此核心尚未接入 .lnt 资产、UI 或翻译，未绕过 file_type=TXT 约束；VTT 仍缺。

契约和已审阅 LG 生产来源见 [MODEL_SCHEDULING](MODEL_SCHEDULING.md)、[FOREGROUND_TRANSLATION](FOREGROUND_TRANSLATION.md)、[TXT_TRANSLATION](TXT_TRANSLATION.md) 和 [SUBTITLE_CODECS](SUBTITLE_CODECS.md)。根说明、源码地图、架构、UI 和 Agent/流式边界同步，未改 LG 仓库或真实用户数据。

### 回归源码与静态审查

本轮新增 **46 个待运行用例**：domain 4、engine 10、agent 6、storage 6、app 5、formats 15，并补既有前台命令冲突用例。覆盖排队取消/速率/同修订资源、防停止后派发、已知响应与取消并发、Agent PREPARED/CAS/预览和并发去重、整工程跨空操作页/失败/停止/显式新运行、前台真实存储接线、精确/缺失用量、范围确认/取消/新运行弹窗隔离，以及格式结构/绑定/空输出/预算边界。它们是源代码数量，不是成功执行结果。

本机仅做静态审查，未实际构建。已执行 `tools/rebuild/check_structure.py`、`git diff --check` 和冲突标记扫描；结构/依赖/包名/已知 Kotlin 与 API 地雷/词法分隔符、文档链接、资源及调用点核对通过。五语新增键和格式占位符另行静态核对一致。没有运行 Gradle、测试或 Lint，没有生成/查看新原生截图，没有供应商或设备验收；没有提交、推送、触发 Actions 或发布。

### 仍未完成与接续约束

- **验证**：§34—39 的累计未提交源码必须由 PC/获授权 CI 先运行对应及全量 test/lint/release；补 Room 2.json/3.json 真实 KSP 导出、原生截图录制/查看和设备取消/重附着。不能沿用 §33 的全绿结论关闭新增切片。
- **模型/翻译**：完整五协议、OAuth/Key 池、自动并发探测、429/冷却恢复、持久用量与费用、代理/网络设置、远程批翻、规划/前文/去重/重试、任务队列、暂停及同 Run 的跨进程接续仍未齐全。
- **规则/校对**：四类规则、术语/提示词/预设的工程持久化和管理 UI、完整六类警告、跨页/全查询批量、查找替换与宽屏表格仍待落地。
- **Agent/工作区**：Agent 应用级执行、输入队列/steer、历史编辑/分支、压缩/检查点/完整审批，脚本隔离/受控 IO、技能/人格、联网和 MCP 备用链仍未完成。
- **格式与迁移**：字幕先取得对应构建/回归证据，再正式设计并验证新的工程格式身份/版本迁移、原始字节与槽位持久化及导入/导出 UI；不得把纯文本核心当作格式已支持。VTT、完整 Markdown、EPUB/PDF/图片、JSON/表格/TRANS/RenPy/RPG、.lg/Pi、完整备份及旧 LNT 数据迁移/退役/升级验收仍开放。

完整 R00—R39、98 个功能和 G1—G5 目标不变。本轮没有新增外部证据，不能把独立核心、源代码或待运行用例视为整个产品完成。


## 40. 审查确认缺陷修复（2026-10-23）

用户要求修复本次审查确定的问题。本轮修复三项，不覆盖既有脏文件，不提交、推送或触发 Actions。

- **整工程空操作资格**：TextItemStatistics 新增必填 translatableUnprocessed（NONE 且非空源文），保持三种状态总数及空白行 NONE 不变。存储在同一分页事务聚合该子集，SQL trim 字符集从 Kotlin isWhitespace 派生，包含制表符及 Unicode 空白；UI 和 ViewModel 的整工程启动均据此拒绝无可翻译内容的运行。ViewModel 启动前重新读取统计，缺失统计时不启动。没有 .lnt/Room schema 变更。
- **LRC**：行首时间标签组允许空格/制表符分隔，修改及多行/双语渲染保留整个原字面 prefix；移除正文时间标签时给相邻非空白文字补边界，已有空白不重复添加。译文仍不恢复逐字时间，未修改/相同正文仍保留原切片；没有行首时间标签的行仍不提供槽位。专题契约同步。
- **预留失败状态**：RuntimePort 增加原子选中/运行预留，RuntimeCoordinator 在同一锁内先完成占用与身份验证，再同时发布选择和租约；ForegroundTranslationRunner 不再先 selectChat。认证写/已有运行/错误身份拒绝不改变原快照，也不产生执行器会话或持久日志；避免失败回滚覆盖其他调用方的新选择。

新增 9 个待运行回归方法：domain 1、storage 1、engine 2、app 3、formats 2；另补整工程集成用例完成后可翻译数为零、空白仍为 NONE 的断言，并更新已有统计夹具的必填字段。覆盖 Unicode 空白/零宽非空字符、查询/固定窗口同统计、直接 ViewModel 空工程启动、模拟按钮禁用、认证写失败的空/Agent 原选择及显式重试、原子身份/竞争拒绝、LRC 分隔时间组/双语展开/词边界/未改行。全部仅为源代码，未运行。

导航在忙碌期间禁用的既有行为保持，后续需单独设计运行状态与页面导航解耦；分页全工程统计仍未缓存，新增源文资格统计会读取 NONE 源文字段，目标设备大工程耗时仍待测量，不声称已解决性能问题。完整产品与 §39 未完成范围保持。

本机仅静态审查，未实际构建、测试或 Lint。已执行 tools/rebuild/check_structure.py（结构、依赖、包名、API 地雷、Kotlin 词法分隔符及文档链接检查通过）、git diff --check（无问题）、冲突标记扫描（无匹配）及 git status --short --branch；已核对统计全部构造点和 RuntimePort 实现/调用点。不沿用已有 CI 证明本轮变更。未进行设备/视觉或供应商验收。


## 41. CI 验证：§34—40 首次全量构建（2026-10-23）

用户要求把当前分支推送 GitHub、触发 Actions 并监控。分支 `refactor/translation-agent-2026-10`，未改动 `main`/`beta`。本轮先把 §34—40 的未提交源码与文档合成 `b236344`（81 文件，+5125/−189），推送后用 `workflow_dispatch` 触发；随后按失败日志修复三处编译/测试问题，最终 `8e74fff` 取得全绿。

### 失败、根因与修复

- [#133 / 39947763078](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39947763078)：test/lint/release 均停在 `:core:agent:compile*Kotlin`，`AgentRequestBoundary.kt` 未解析 `OperationLease`、`AgentModelEvent`、`DispatchAwareAgentModelPort`。§39 新增调度接线时漏了三个 domain 导入；`edca775` 补入导入，并用局部变量承载调度感知端口，避免依赖跨模块智能转换。
- [#134 / 39948120434](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39948120434)：越过 agent 后停在 `:app:compile*Kotlin`，`NativeProjectViewModel.saveProofreadingQuery` 对 domain 的 `query.status` 做智能转换被拒绝。`d5c4c68` 改为先取局部变量再访问 `name`。
- [#135 / 39948559626](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39948559626)：lint/release 成功，test 仅 4 项 `NativeProofreadingQueryDialogTest` 失败；均在 `performScrollTo` 上 60 秒 `AppNotIdleException`（Compose 未 idle，12 万次以上重试）。根因与 §31 设置表单相同：`AlertDialog` 的 wrap-content 窗口包含文本框时持续重复测量。`8e74fff` 按既有模型设置对话框改为 `DialogProperties(usePlatformDefaultWidth = false)` 全屏窗口 + 居中限高卡片，滚动区用 `weight` 取得有界高度，按钮固定在滚动区外；testTag、启用条件和五语文案未变。
- [#136 / 39949770529](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39949770529)：提交 `8e74fff`，test/lint/release 全部 success，耗时约 6 分钟。

### 已取得的远端证据

| 模块 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| app | 704 | 0 | 0 | 0 |
| core:agent | 31 | 0 | 0 | 0 |
| core:storage | 92 | 0 | 0 | 0 |
| core:engine | 90 | 0 | 0 | 0 |
| core:domain | 76 | 0 | 0 | 0 |
| core:formats | 17 | 0 | 0 | 0 |
| 合计 | 1010 | 0 | 0 | 0 |

- 数字来自本次下载的 111 份 JUnit XML，不是本机测试或方法计数。`core:formats` 首次进入 CI，17 项字幕纯文本用例全部通过；此前“新增用例均未运行”的边界对 §34—40 已结束，但设备/供应商边界不变。
- Lint 上传 5 份 XML：app 75 issue、0 Error/Fatal，其余 4 个模块 0 issue。不登记为零警告。
- release 的签名校验、`assembleRelease`、`apksigner verify` 和上传均成功；产物为 `LightNovelTranslator-refactor-translation-agent-2026-10-signed`，APK 21,928,174 字节。本轮未安装或发布。
- 仍缺：test 产物只有 `core/storage/schemas/.../1.json`，没有 2.json/3.json；日志中 `:core:storage:kspDebugKotlin` 为 FROM-CACHE，`:app:finalizeTestRoborazziDebug` 为 SKIPPED。也没有 `native-*.png`（CI 未开启 Roborazzi 录制）。因此校对/批选截图尚未生成或查看，Room v2/v3 权威 schema 导出仍未完成。
- 本机仍只做静态审查（`tools/rebuild/check_structure.py`、`git diff --check`、冲突标记扫描），未运行 Gradle、测试或 Lint；以上结论来自远端运行与产物。没有设备、通知权限、Activity 重建、真实供应商或 KeyStore 验收。

### 结论与剩余

§34—40 源码现已具备可编译、可运行测试和可签名打包的 CI 证据，S1/S2 仍不能关闭。下一步优先补 schema 2/3 导出与 Roborazzi 录制，再按 §34—40 文档做真机前台/通知/重建、校对批选与字幕接入验收；完整调度/用量账本、后台接续、复杂格式/工作区和旧数据迁移缺口保持不变。本轮最终为 `8e74fff` 代码提交 + 本交接文档提交，文档提交不再触发额外构建。


## 42. 文档进度修正与剩余实施清单（2026-10-23）

用户要求先修正文档，再汇报待实施工作。本轮从 `b1f61c4` 的干净工作区开始，只修改文档；代码仍为 §41 的 `8e74fff` CI 基线。同步根 README、AGENTS、计划入口及架构/存储/翻译/运行恢复/前台/调度/校对/批选/字幕/Agent 相关专题：移除当前状态中“§34—40 尚未构建/测试未运行”等过时说法，补充当前页/整工程模拟和共享网关的实际接线，校对筛选固定窗口的 CI 修复，并将最新证据指向 §41。保留各历史开发轮当时的验证状态，不重写为当轮已通过。

### 仍待实施的业务

下表汇总原 R00—R39 的开放范围，不增加或削减 98 项功能目标，不把已有切片判为整项完成；具体行为与退出条件继续以 FEATURE_MATRIX / IMPLEMENTATION_PLAN 为准。

| 范围 / 任务 | 仍需实现的内容 |
|---|---|
| 工程与存储（R04、R07—R09、R35） | 从单 TXT 资产扩展工程 schema 与迁移；多资产追加/删除/重排、重置与设置对齐、来源 URI/外部变更冲突、覆盖恢复/代际推进、完整备份及系统文件/文件夹交付。已有创建/复制/快照与 TXT 导出不代替这些能力。 |
| 模型与网络（R10—R11、R34） | 完整五协议、模型能力目录、OAuth、Key 池、自动并发探测/动态限额、429 降档/冷却/恢复、分类重试、持久 Token/费用账本及代理/完整网络设置。现有固定 FIFO/并发/RPM 不重复列为未实现。 |
| 翻译与恢复（R06、R12—R13、R18） | 远程批量翻译、LG 对齐的规划/前文/去重/输出协议/失败策略与全部启动模式；任务队列、暂停、持久策略检查点及同 Run 跨进程接续。目前远程传输用于 Agent，批翻仍为本地模拟；重启只核对回执。 |
| 规则与校对（R14—R15、R32—R33） | 四类规则、术语/提示词/预设的工程持久化和管理/交换；资产/警告/术语筛选、姓名/PDF 页级事实、上一条/下一条、跨页/全查询批量、查找替换预览提交、六类警告及宽屏表格。 |
| Agent 与审批（R02、R16—R19、R31） | Agent 应用级执行、输入队列/steer、历史编辑/分支、压缩与版本化检查点、完整业务工具、用户问题及多对象/部分接受审批和故障恢复。已有单条 TXT 允许一次与回执核对不能代替全对象审批。 |
| 工作区与联网（R03、R20—R22） | 脚本引擎/隔离与资源限制、受控 IO/异步能力桥、工程快照和 changes 审批提交、材料/附件/产物保存；动态技能、人格及管理 UI；搜索/Fetch/MCP 备用链。core/workspace 当前只有模块骨架。 |
| 格式与兼容（R04—R05、R23—R29） | 字幕原始字节/格式身份/槽位持久化、工程导入导出与翻译接线，补 VTT；完整 Markdown、EPUB、PDF/图片、JSON/表格/TRANS/RenPy、MV/MZ、RGSS；.lg 双向兼容及 Pi 历史转换。四种字幕核心通过 CI 不等于工程已支持这些格式。 |
| 原生 UI 与最终交付（R30—R39） | 完整管理页、手机/平板等价功能与五语/可访问性；运行状态与页面导航解耦；正式旧 LNT 数据迁移、旧主链路/资源/依赖退役、升级/性能/全功能回归及发布交付。现有迁移字段样本不等于迁移器。 |

### 已实现但仍待补证据

1. Room v2/v3 的 2.json/3.json 权威 KSP 导出与审阅；保留现有 1.json，不手写 schema。
2. 原生组件 PNG 的录制和实际查看；模型设置、校对筛选/批选、小屏/横屏/键盘、五语与无障碍的视觉交互检查。
3. 模拟前台执行的设备启动/停止、通知拒绝、切后台/锁屏、任务移除、Activity 重建、连续启动、系统超时和进程死亡；含校对操作、保存重导入与实际大工程耗时/内存。
4. Agent 对话/审批/模型发现/SSE/思考/工具提示/停止/断网/会话隔离和 KeyStore 的真机流程；真实供应商联调需另有明确授权，当前未调用。
5. G1—G5 原型与正式集成证据、完整 S1/S2/S3、98 项功能逐项证据及升级矩阵。部分属于尚待实现的能力，不能只补测试便关闭。

下一轮先补 schema/截图的 CI 产物与已有切片设备验收；源码推进按直接依赖安排：工程存储扩展/规则与翻译核心 → 远程批翻与持久恢复/完整 Agent → 工作区及各格式接线 → 迁移和全功能交付。各技术门槛按原计划逐项验证，不把该顺序当作绕过 G1—G5 的授权。

本轮本机验证：静态结构/依赖与文档引用检查、git diff --check、冲突标记扫描和工作区范围核对通过。静态审查，未实际构建、测试或 Lint；未进行设备/视觉/供应商验收，未修改产品源码或用户数据，未提交、推送、触发 Actions 或发布。已有 CI 证据仍只来自 §41。


## 43. 远程 TXT 批翻、持久请求回执与运行中切页（2026-10-23）

用户要求继续全自动实施剩余范围，稍后统一由用户测试；本轮不运行本机构建/测试/Lint、不触发 CI 或调用真实供应商。保留 §42 的 19 份文档修改；基线仍为 `b1f61c4`，新增源码均未提交、未构建，不能继承 §41 的 1010 项全绿。完整剩余目标未全部实现。

### 实际修改

1. **远程批翻接线**：新增 RemoteTranslationOptions、ConfiguredTranslationBackend 和 CompatibleTranslationModel；当前页/整工程可显式确认后使用已有模型配置修订，经共享 NativeModelGateway/FIFO 和应用级前台执行器运行，不另建 HTTP 或写入旁路。本地模拟保留。语言/自定义要求/每批条数和字符目标固定在请求中，改变选项不能复用同一前台命令。
2. **严格输出**：请求与输出绑定独立 request_id、字符串条目 ID 与对象指纹，超安全整数身份无损。完整集合、个数、重复键、Unicode、长度、空白/NUL、截断、过滤和工具调用在写回前校验；失败整批拒绝，之前已提交批次保留。不会自动修复/重试或静默放宽协议。
3. **派发与停止边界**：增加 DispatchAwareTranslationGateway，共享队列资格取得后才登记请求尝试/REQUESTING；持久回调后、审计预留后和 HTTP 前重查停止。页数/修订/计数仍使用同一持久 Run。真实请求没有自动重发或进程接续。
4. **持久请求/Token**：应用 Room 源码 v4，新增 native_translation_requests、DAO/轻量行、MIGRATION_3_4 并注册完整升级链。远程 attempt 在 HTTP 前唯一预留，完整响应的计量/分类在解析失败或取消时仍优先持久化，源文/提示词/Key/响应正文不落该表。运行恢复核对带身份的元数据，不执行请求/工具/译文写回；既有无 attempt 的记录保持原语义。
5. **原生 UI**：五语远程确认表单展示模型/端点、费用及外发提示，目标语言必填，选择范围和编辑草稿不会启动调用。运行历史和工作台可查看已收响应的持久 Token 汇总。前台翻译时允许同工程页面切换，写入/导出/换工程/新请求继续受 busy/运行租约约束，页面和通知均能停止实际任务。

详细源码边界见 [REMOTE_TRANSLATION](REMOTE_TRANSLATION.md)，它不等于工程级规则/提示词/预设管理、完整五协议、完整 LG 翻译规划或后台恢复已完成。`.lnt` v1、旧阅读库 v6 和用户文件均未变更。

### 回归源码与验证边界

新增 21 个待运行回归方法：engine 协议 7、HTTP/停止/取消保存 4、storage 审计/迁移 4、真实存储翻译集成 2、app 确认表单 4；补既有 ViewModel 重附着用例的运行中切页/任务寿命断言。夹具只使用假端口或 HTTP 拦截器，没有真实供应商调用；未运行用例不登记为成功覆盖。

本机仅静态审查：结构/依赖与导入边界/符号/文档链接、五语 XML/资源键和引用、git diff --check、冲突标记与工作区范围核对；未实际构建、测试或 Lint，未录制/查看新原生截图、安装或验收设备。v4 的编译/KSP/迁移和用户交互必须由用户后续统一验证；2/3/4.json 权威导出仍缺，禁止手写。

### 仍待实施

§42 的完整目标继续开放；其中“远程批量翻译”和“运行中同工程导航”现有本轮源码，尚无验证证据；“持久用量”仅新增远程翻译响应 Token 元数据，不包含费用定价或完整跨 Agent 账本。工程存储/多资产、规则/术语/文本流水线、全部模式/规划/去重/重试、完整协议/密钥池、暂停/队列/检查点/跨进程接续、Agent 全流程和多对象审批、脚本/技能/联网、复杂格式/.lg/Pi、管理 UI、旧数据迁移/退役/最终交付均未完成。当前仍不能关闭 S1/S2、R00—R39 或 98 项验收。

未提交、推送、触发 Actions 或发布；当前源码不属于 §41 已验证 APK。


## 44. 工程规则持久化、原生管理页与文本流水线（2026-10-23）

用户要求继续下一轮，沿用“由用户稍后统一测试”的约束。本轮保留 §42—43 的全部未提交修改，不运行本机构建/测试/Lint、不触发 CI 或真实供应商。代码基线仍为 `b1f61c4`，本轮新增源码不属于 §41 的 CI/APK。

### 实际修改

- `.lnt` 新建格式源码升至 v2，新增 text_policy/policy_revisions/policy_receipts；v1 读取/人工编辑/导出不强制升级，首次规则写入在同一事务内升级并保存终局回执，取消或异常回滚格式及业务数据。独立 Room 保持 §43 的源码 v4，旧阅读库和用户源文件不变。
- 四类规则和工程提示词有稳定身份/顺序/启用状态、原生严格 JSON、指纹及全局修订；NativeTextProjectStore 实现 ProjectTextPolicyPort，所有保存经唯一 ProjectWriteStore 和写租约。规则/条目/批量命令不能碰撞，成功和拒绝均可核对旧回执。快照与导入扩展为 v1/v2，并核对规则和条目混合修订、对象链、摘要与回执，不丢掉新增表。
- 新增 RE2/J 1.8 catalog 依赖、语法验证与有界规则流水线。远程运行固定规则快照，按原稿匹配术语，保护控制文本，在非保护区域执行译前/译后替换，按转换后长度分批，核对/恢复 token 后走既有原子写回。工程提示词/规则指纹及命中术语进入同一共享网关请求；模拟保持原行为。空白转换、预算或保护失败不会静默放宽规则。
- 同工程导航增加五语“规则与提示词”页，支持四类规则增删改、启停、排序、提示词、显式保存/重载、原生 JSON 预览到草稿及复制 JSON。草稿绑定 Project/Instance/Generation、原修订/指纹与命令并恢复；工程变更保留冲突草稿，丢失保存响应时按原命令只读核对并显示最新事实，切页保留，回首页须确认放弃，未保存或读取失败阻止翻译启动。已提交后刷新失败不冒充回滚；新工程打开先清理其他工程的规则状态。

契约、依赖官方依据、实际语法/预算与尚未实现部分见 [RULES_AND_PROMPTS](RULES_AND_PROMPTS.md)。当前不是 LG 预设/CSV 交换、智能保护/全正则兼容、完整质量告警或 Agent 多对象规则审批，R12/R14/R33 保持开放。

### 回归源码与验证边界

新增 24 个待运行方法：domain 4、engine 8、storage 7、app 规则页/草稿 3、既有 ViewModel 类新增规则草稿重建/保存/阻止未保存规则翻译及丢失响应核对 2。覆盖规则不可变/类型/摘要、命名捕获与字面替换、保护失败/重叠/大小写、预算/取消，v1 保持与升级回滚、混合修订/快照/导入/复制、终局去重/冲突/占用、远程真实存储消费和原生显式确认。它们只是源代码，未运行；RE2/J 新依赖未解析或验证 Android 打包。

本机静态结构/依赖与导入边界、版本与 SQL/端口/调用点、五语资源 XML/键/引用、文档链接、git diff --check 和冲突标记检查通过；本机未实际构建、测试、Lint、录制/查看新 UI 截图、验证设备或供应商。新增 v2 文件格式与首次规则写入升级、Room v4、RE2/J 和新 UI 都须用户后续统一验证；既有 CI 不能覆盖这些增量。

仍待：§42—43 的完整剩余范围，以及本轮规则的格式智能保护/正则兼容、姓名/六类警告、LG 交换和应用预设、Agent 规则工具。工程仍是单 TXT 资产，完整多资产/格式/工作区/后台接续/旧数据迁移等尚未完成。不关闭完整 S1/S2、G1—G5 或 98 项验收。未提交、推送、触发 Actions 或发布。


## 45. TXT 全文草稿的本地质量检查（2026-10-23）

用户要求继续实施，仍由用户稍后统一测试。本轮保留 §42—44 所有未提交修改；基线仍为 `b1f61c4`，未提交/未构建源码不能继承 §41 的 CI/APK 证据。

### 实际修改

- 新增 domain 的六类警告词表、语言选项和不可变检查结果，engine 的 TextProofreadingInspector。仅评估非空白的已处理译文；未处理/排除/空译文明确显示未评估。结果带规则指纹和语言，不携带正文或坐标。
- 共享 TextPolicyProcessor 的保护选段/译前准备/正则预算，补完整文本上的术语缺失、保护内容/顺序/行号、原始行数、结构标点、包含关系/字符集合相似度，以及有限目标文字系统残留检查。远程翻译依然拒绝准备后空输入；只读检查允许空原稿/空准备结果，未放宽付费调用。
- 完整条目编辑器新增五语显式检查与源/目标语言选择。读取已保存工程规则且必须匹配编辑全局修订，未保存规则/冲突禁止检查；计算在 Default 执行，可取消，不改数据库、不推进修订、不发模型请求。使用完整草稿，未使用截断列表预览。检查结果绑定同一草稿对象/工程，编辑、状态变更、规则变更/重载和编辑器关闭/保存均清除旧结果，换语言后隐藏旧结果。错误不显示成无警告。

细节、局部算法和真实缺口见 [TEXT_QUALITY](TEXT_QUALITY.md)。当前不是完整 LG 质量兼容；外文残留只覆盖所列文字范围，未实现 Intl 字素级等价；姓名/PDF、智能格式保护、全工程扫描/缓存、警告/术语筛选统计、批量修复、Agent 工具与宽屏表格尚缺。§42 的其他完整产品目标继续开放。

### 回归源码与验证边界

新增 13 个待运行方法：engine 10、app 面板 2、既有 ViewModel 新增 1。覆盖正文超过列表预览、检查不写回/不调用模型、规则消费与语言、保护差异/顺序/行号、译前/译后区别、术语/标点/行数、未评估、错误/取消、显式调用和旧结果失效。仅新增源码，未运行；不登记编译或用例通过。

本机只做静态结构/导入边界、调用点与符号、五语 XML/资源键/引用、文档链接、diff 空白及冲突检查；静态审查，未实际构建、测试、Lint、录制/查看新界面截图或验证设备/供应商。未提交、推送、触发 Actions 或发布；完整 S1/S2 和 98 项验收未关闭。


## 46. 已保存 TXT 质量扫描、结果筛选统计与编辑定位（2026-10-23）

用户要求持续推进并自动衔接阶段，不在阶段间停止征询。本轮保留 §42—45 未提交修改，仍按用户稍后统一测试的约定只做源码和静态审查；代码基线仍为 `b1f61c4`，不继承 §41 的 CI 证据。

### 实际修改

- 新增 TextQualityIndex/Filter/Selection 和 ProjectQualityScanner。固定工程身份/全局修订/查询/语言/规则快照，每 20 条读取全文，复用单次编译的检查器，只保留轻量元数据；单条预算保留。末尾修订、每页 ID/数量、字符累计与结果预算均校验，失败/取消/工程变化不发布半份索引。
- 列表新增五语显式扫描、无提示/未评估/任意或指定警告、缺失术语规则筛选与完整查询范围的去重统计。分页只读固定最多 41 个成员并核对修订/指纹，空选择保持为空。结果不持久化、不调用模型、不写工程；修改条目/批量或规则使旧索引失效，保留固定页已提交事实，禁用旧筛选/翻页。刷新/关闭/改查询回普通列表；扫描失败保留先前完整结果。存在质量结果时不允许启动按页/工程翻译。顶部控件改为有界滚动，避免增加控件后占尽小视口，但没有视觉验收。
- 新增 TextItemNavigationPort 和存储相邻查询，复用状态/字面搜索 SQL 谓词，正/倒序定位上一条/下一条匹配项；质量模式使用完整筛选 ID 选择。跳转核对修订/指纹，跨当前页可用，未保存草稿须显式放弃确认，不自动保存；边界提示在编辑器可见。进入新对象清除旧预览。
- 同步规则保存、普通刷新与索引失效的衔接；规则提交后的正常索引过期不再走“规则读取失败”，已提交修订先反映为事实。所有读写仍走原端口/ProjectWriteStore，不新增数据库 schema。

详细契约和预算见 [QUALITY_SCAN](QUALITY_SCAN.md) / [EDITOR_TOOLS](EDITOR_TOOLS.md)。保留完整 LG 算法、姓名/PDF、多格式、持久增量缓存、全查询批量写入等缺口。

### 回归源码与边界

新增 16 个待运行方法：domain 3、storage 扫描/相邻查询 7、app 扫描/筛选面板 3、编辑跳转确认 1、既有 ViewModel 2。覆盖完整正文/统计、字面查询、固定分页/空选择、并发写入/规则变更、取消/预算、不写回、草稿放弃确认与结果失效/刷新。只是新增源码，未运行或登记成功覆盖。

## 47. 当前译文草稿的字面查找替换（2026-10-23）

连续衔接 §46，未暂停等待确认，未执行构建或测试。

- 新增有界 TextLiteralReplacer 和五语编辑器表单，显式预览完整草稿的非重叠字面替换；支持大小写策略、空白/换行和空替换，不解释正则或捕获语法。显示完整匹配数与明确标注的开头摘要，输出超限拒绝，不截断后应用。
- 预览绑定草稿对象对应的工程身份与不可变命令，修改文本/状态或切条目即清除；改查找参数后不能应用旧预览。应用只更改当前草稿，数据库保存仍需原编辑器保存动作/修订指纹检查；不会隐式保存或发模型请求。预览正文不进入 SavedStateHandle，普通已应用草稿沿用既有恢复。
- 新增 6 个待运行方法：engine 4、app 表单 1、既有 ViewModel 1。覆盖字面捕获符/非重叠/空白/大小写、无变化、取消/膨胀、显式预览/应用、旧命令失效和最终单条保存。完整“当前选择/全部筛选结果”替换、跨条目原子变更与批量修复仍未实现。

§46—47 合计新增 22 个未运行方法。完成后仅做静态结构/模块导入边界、符号/调用链、五语 XML/键/资源引用、文档链接、git diff --check、冲突标记及脏文件范围核对；静态审查，未实际构建、测试或 Lint。没有新增原生 PNG、设备/供应商、KSP/schema 或 CI 证据；未提交、推送、触发 Actions 或发布。完整工程、多资产/复杂格式、后台接续、Agent/工作区、旧数据迁移及 §42 其余目标持续开放，不关闭 S1/S2 或 98 项验收。

## 48. 条目提交后的规则草稿修订同步（2026-10-23）

修复静态审查确认的正常操作冲突：单条译文或人工批次提交推进工程全局修订后，NativeProjectViewModel 先重读尚未修改的规则草稿，再刷新条目窗口，使随后编辑规则使用最新修订/指纹。已有脏草稿及其 SavedStateHandle 内容保留原基线和命令，继续受存储层 STALE_REVISION 检查；未放宽 ProjectWriteStore 的修订检查。规则读取失败沿用 policyLoadIncomplete 阻止编辑，不将已提交条目误报为回滚。

新增两项待运行 ViewModel 回归方法，各覆盖单条/人工批次：干净规则同步后保存成功；脏规则保留且保存被拒绝。既有质量分页用例补充单条保存后的规则基线断言，保留后续规则提交修订为 2 的期望。

静态审查，未实际构建、测试或 Lint；完成 diff 空白、冲突标记和调用链核对。保留既有未提交修改，未提交、推送或触发 Actions；新增用例仍待 CI/PC 执行。

## 49. 多资产工程、格式适配、模型协议与翻译规划源码（2026-10-24）

用户要求继续全自动推进剩余业务，完成后再审查、提交到 `refactor/translation-agent-2026-10`、手动触发 Actions 并持续修复。本轮仍不在本机运行 Gradle/测试/Lint；以下均为新增源码，编译与测试结果以随后 CI 为准。

### 实际修改

- **`.lnt` v3 多资产基础**：`NativeProjectFormat.SCHEMA_VERSION=3`。`assets` 支持多行、顺序、启用与元数据，`items` 增加 asset/slot/tag/name/extra_field/skip_internal_filter 与 ERROR/RULE_SKIPPED/LANGUAGE_SKIPPED/DUPLICATED 状态；新增 `project_settings`、`asset_receipts`、`asset_revisions`。v1/v2 在可写打开时事务迁移，快照、严格导入校验、全局修订时间线同步扩展。新增 `ProjectAssetPort`（导入/删除/重排/重置/设置/读回/导出），所有写入仍走唯一写租约与命令回执。
- **格式适配核心**：`core/formats` 新增 Markdown MD_V2、VTT、SRT/LRC/ASS/SSA 桥接、KVJSON、MESSAGEJSON、TRANS、Ren'Py、EPUB、XLSX、RPG Maker MV/MZ 适配器，以及严格文本编码、显式适配器目录和 `ProjectFormatService`。二进制适配器使用 JDK ZIP/XML，不新增依赖；未移植的 LG 分支已在各文件注释和本轮交接中列明。
- **工程 UI**：工作台显示固化资产列表，可向已打开工程追加格式资产并按资产导出；系统选择器结果绑定工程修订与命令，导入/导出仍受 busy、写租约和格式可用性约束。
- **模型与用量**：新增五类协议纯编解码、协议传输（复用现有 HTTP 所有权）、密钥池/冷却/Retry-After、ACTUAL/ESTIMATED/UNKNOWN 用量账本、三态能力目录；尚未接入现有 Agent/翻译网关与 Room 持久化。
- **翻译规划**：新增 NEW/CONTINUE/RESET 模式、目标规划/预算/前文、同文件去重、失败分类与对半重试、持久检查点编解码；尚未替换 `TextTranslationService` 的内联批循环。
- **规则交换**：新增原生规则交换 JSON、预设、提示模板、RE2/J 预览和匹配解释；明确不声称 LG JSON/XLSX/CSV 交换兼容，尚未接存储/UI。

### 新增回归源码

新增 JUnit4 用例覆盖：格式往返/结构保留/恶意容器拒绝、协议请求响应与错误、密钥池/用量/能力、规划预算/去重/重试/检查点、规则交换/预设/模板、多资产 schema/迁移/回执/设置/重置。全部尚未在本机运行，数量与结果以 CI 报告为准。

### 验证边界

本机仅执行 `git diff --check`、冲突标记扫描、`tools/rebuild/check_structure.py` 与静态导入/符号核对，未运行 Gradle、JUnit、Lint、KSP、APK、Roborazzi 或设备/供应商。v3 schema、KSP/Room、全部新适配器与 UI 交互必须由 CI/PC 和真机验证；未取得证据前不关闭 S1/S2、G1—G5 或 98 项矩阵。未提交前本节不代表远端状态。
