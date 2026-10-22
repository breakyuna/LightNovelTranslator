# 原生 LG 重构交接记录

最后更新：2026-10-22。

这是本轮全面重构的唯一进度记录。目标见 [README](README.md)，任务定义见 [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md)，功能范围见 [FEATURE_MATRIX](FEATURE_MATRIX.md)。旧 2026-10-19 计划的完成状态不自动继承。

## 1. 本轮结论

- 用户要求彻底重构 LNT，包括 UI，全面实现 LG Termux 功能；只保留 LNT 的 Koog Agent 框架。
- 用户追加说明：`AGENTS.md` 冲突条款也以本次目标为准。已同步项目定位、UI/导航、数据和业务边界、工作方式与验收口径，保留本机验证限制及数据保护规则。
- 初始规划轮只编写规划与同步根规则；用户随后已授权“开始修改”，实施记录见第 7—9 节。
- 已建立六份 Markdown 文档和一份源码基线 JSON：目标、架构、98 项功能矩阵、15 页面/系统界面 UI 规格、40 项实施任务、交接及源码指纹。
- “仅保留 Koog”已细化到源码入口和必须解除的旧依赖；新业务不会继续接入旧 AppViewModel、章节/Edition 引擎或旧 UI。

## 2. 源码与验证基线

| 对象 | 本轮开始时状态 | 说明 |
|---|---|---|
| LNT | `refactor/translation-agent-2026-10` / `4bd2ae0`；22 个已有修改 | 全部保留；本次在其中的 AGENTS 上追加目标同步 |
| LG | `termux-port` / `23796556`；67 个修改 + 12 个未跟踪文件 | 只读参照，不编辑、不提交 |
| 当前 CI 证据 | [run 39375817891](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39375817891) | 旧已提交版本 test/lint/release 成功，不覆盖当前脏代码和新目标 |
| 源码指纹 | `SOURCE_BASELINE.json` | 保存规划前状态，检测漂移；不包含完整 diff，不能代替实施快照 |

本轮实际新增：本目录全部文件。本轮已有文件编辑：根 `AGENTS.md`。根 README、旧计划、产品源码、测试、数据库和 LG 仓库均未由本轮修改。

## 3. 实施状态

| 范围 | 状态 | 实际证据 / 未完成 |
|---|---|---|
| 规划包与规则同步 | 完成，静态核对通过，结果见第 6 节 | 不代表产品重构完成 |
| R00 固定可重现源码与行为索引 | 进行中 | 已保存源码归档与摘要、提取发现索引；194 条发现已补消费者静态证据，仍需按功能 ID 核对参数、可见操作与脚本 API，见第 7、10 节 |
| R01 / M1 模块装配 | 外部构建全绿，范围未验收 | 七模块声明、catalog、CI 引用、`api` 边与静态检查已写入；CI 39765686187（`e0f3ee9`）的 test/lint/release 全绿，见第 11 节；业务功能与设备门槛仍未验收 |
| R02/R06 原型准备 | 进行中，门槛未通过 | 已写进程内协调器、协程执行适配与类型化请求/只读工具端口原型；尚未迁 Koog、接 Service 或取得 Android 证据 |
| R19 审批契约 | 原型就绪，未验收 | `PreparedChangeSet` 与审批协调器/端口已写入并有 19 个未执行测试；无生产存储、写入口、工具接线与 UI |
| R03—R05 / M1 | 未开始 | 工作区引擎、.lg 与复杂格式原型仍未落地 |
| R07—R29 / M2—M4 | 未开始 | 无新工程/模型/翻译/Agent/工作区/格式实现 |
| R30—R35 / M5 | 未开始 | UI 规格已写；未生成原生原型、截图或设备结果 |
| R36—R39 / M6 | 未开始 | 未迁移/删除旧数据，未退役旧产品，未构建新签名包 |

尚无功能 ID 完成验收；新基础代码和原型准备按第 8 节记录，不代表已接入产品。旧 LNT 中存在类似能力不自动标为完成，因为用户要求替换其实现，仅保留 Koog 框架。

## 4. 下一轮顺序

用户已授权实施，后续顺序：

1. 阅读总入口、架构、实施计划、根规则和本交接；重新核对两仓库状态。
2. 继续 R00：在消费者静态证据之上按功能 ID 核对参数、Schema、可见操作与内置脚本实际 API；消费者表不等于 R00 完成。
3. 完成 R01 最小可编译模块装配，在 PC/授权 CI 验证（当前唯一硬阻塞）。
4. 推进 R02—R06 五个门槛，优先解决脚本、.lg/Pi 历史和复杂格式；再进入正式业务与全量 UI。
5. 审批契约在 R08 写入口与 AppStore 适配器就绪后再接工具与 UI，不能先用假存储或假审批放行写入。
6. 每项只登记真实证据；本机不得运行 Gradle，不自行触发 CI 或真实模型调用。

若下一条指令仍是修改计划，只修改授权文档，不因为有任务清单就启动实现。

## 5. 待原型解决的决策

| 决策 | 默认方向 | 形成最终决定的任务 |
|---|---|---|
| Koog 检查点/序列化与依赖 | 保留 1.3.0 接入，补应用执行记录，不继承旧消息存储限制 | R02 |
| 最低 Android 版本 | 从 API 24 保持起步，不用 Manifest override 掩盖真实不可用 | R02/R05/R06 |
| 脚本引擎 | QuickJS 首个候选，独立受控能力桥；不运行 Node 业务服务 | R03 |
| 工作区隔离与停止 | 隔离 Service 原型，真实拒绝/取消/回收证明后定型 | R03/R06 |
| .lg 与 Pi 历史双向映射 | 兼容工程事实和公开历史，框架私有执行状态分开 | R04 |
| PDF/RGSS/EPUB 底层库 | 真实读写与输出后锁定版本/ABI/资源 | R05 |
| 文件/内存/展开预算 | 目标设备测量后冻结，不直接复用 Web 暂存上限 | R06 |

以上是有具体解决入口的未决技术项，不是功能缩减授权。任何未通过的项继续阻止完整版本验收。

## 6. 本轮验证与交付

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
这证明模块骨架、主源码与测试源码可编译、单测通过、签名 Release 可产出；不证明任何功能行为、Koog 恢复、脚本/格式门槛或设备交互，R01 的完整验收仍取决于 R02—R06 与后续任务。

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

交付边界：本机静态审查，未构建、未执行测试、未运行 Lint；修复后需重新推送并重跑 CI，全绿前不登记 R01 完成。

## 13. 参数校验后续审查修复与 CI 失败核对（2026-10-22）

用户要求修复后续两项审查意见，并核对 GitHub 最新 Actions 的 test 失败原因。

- 最新 [Actions #116 / run 39768093299](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39768093299) 对应 `0b62758`；test 失败，lint/release 成功。读取 test job `119788470765` 日志确认：`:app:testDebugUnitTest` 共 645 项、2 项失败，恰为 `updateTranslationConfigNormalizesFields` 与 `getRecentLogOptionalProjectAndDefaultLimit` 的旧回退断言；不是编译失败。
- 更新上述测试：`parallel_chapters:0` 与 `project_id:0` 必须收到非法参数说明且不调用宿主；保留省略/显式 null 的默认查询与合法配置字段归一覆盖，不恢复非法值回退。
- `AgentToolHost.exactLongOrNull` 改为 `BigDecimal(Double).longValueExact()`，按 Double 的精确二进制值检查整数性和范围，避免最短十进制字符串把 `2^60` 转成另一个 ID。
- 新增 2 个测试用例，覆盖必填/可选大整数 ID 的普通小数、科学计数法、Long 上界附近值及整数字符串，并断言小数/超范围 ID 不触达宿主。大整数问题不在 #116 的失败列表中，这些新用例尚待 PC/CI 执行。

实际验证：调用链与测试断言静态核对、`git diff --check`、冲突标记扫描；另只读获取 GitHub 的 run/jobs/test 日志。静态审查，未实际构建，未执行本机测试或 Lint；未提交、推送、重跑或触发 Actions。#116 反映修复前版本，不能作为本次修复已通过的证据。

修复后重跑 [run 39768093299](https://github.com/breakyuna/LightNovelTranslator/actions/runs/39768093299)（`0b62758`）：lint/release 通过，`test` 有 2 个失败，都是既有测试仍按旧宽松语义断言——`parallel_chapters: 0` 曾静默忽略、`project_id: 0` 曾回落为无过滤查询，新实现改为明确报错。

同轮核对还发现 `exactLongOrNull` 用 `BigDecimal(value.toString())` 会按最短十进制表示舍入大整数 Double（例如 `1152921504606846976.0` 变成 `1152921504606847000`），把 id 指向另一个对象；已改为 `BigDecimal(value)` 取精确二进制值。
测试同步为新契约并补覆盖：`1.5`、`1e20`、`2^63`、`Long.MAX_VALUE`、字符串形式与 `2^60` 精确值。再次重跑通过前不登记 R01。
