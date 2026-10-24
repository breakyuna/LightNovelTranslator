# 原生 LG 重构交接记录

最后更新：2026-10-24。

这是本轮全面重构的唯一进度记录。用户最新范围决定：外部 `.lg` 工程交换与 Pi 会话兼容全部取消，PR10 仅保留取消编号；R04/G3 改为原生 `.lnt` 存储/升级/恢复，原 98 个功能 ID 中 PR10、OP06 已取消，当前有效 96 项。§7 起的开发轮记录保留当时的源码与验证事实，其中旧兼容要求不再适用；其中“未提交/未构建/待运行”等历史状态不得覆盖 §3 和最新 CI 结论。目标见 [README](README.md)，任务定义见 [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md)，功能范围见 [FEATURE_MATRIX](FEATURE_MATRIX.md)。旧 2026-10-19 计划的完成状态不自动继承。

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

当前已提交验证基线：分支 `refactor/translation-agent-2026-10`，HEAD `b47178a`；提交 `b47178a` 的 [Actions run 40132451221](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40132451221) 已通过 test/lint/release 和签名校验，覆盖 §102—§122 的规则/模板/替换、姓名写回（`.lnt` v5）、Agent 队列/steer/自动压缩、翻译任务裁决与队列、模型费用、RGSS/PDF、持久用量账本、诊断日志/应用备份、技能库与 More 页。当前源码 `.lnt` 为 v5，应用 Room 为 v13，二者独立；`2.json`—`13.json` schema 导出、真机升级/视觉/真实供应商验收仍缺。旧 LNT 数据迁移（R36）已按用户决定移出当前范围，迁移代码已删除。各开发轮中的“未提交/未构建/未运行”保留为当轮历史，当前状态以本节为准。

| 范围 | 已实现与接线 | 尚缺内容与验证 |
|---|---|---|
| R00/R01 基线与模块 | 194 条消费者索引、98 个功能来源、新模块装配及签名 APK 已有证据 | 来源覆盖不等于实现完成；完整 G1—G5/依赖与设备门槛未关闭 |
| 工程与存储 | `.lnt` v5 多资产/槽位/格式元数据、归档式资产移除与历史保留、逐修订姓名快照、修订/回执、快照及版本校验；ProjectAssetPort 提供导入/删除/重排/重置/启停/设置/导出；工作台接追加、按资产导出与工程设置；原生编辑器接单条姓名命令 | §70 已有工程 ZIP 备份/恢复源码；来源冲突、姓名单条以外的批量/替换目标及应用级设置/凭据/会话完整备份仍缺；旧 TXT 手工设备确认仅限 §22 |
| S1 翻译 | 本地模拟、远程 TXT 批翻确认/严格输出、应用级前台执行、当前页/整工程、分批提交/停止、持久请求与 Token 元数据、同工程切页已有 CI；NEW/CONTINUE/RESET、资产分组、有界前文、同文件去重、显式预算重试/拆批与失败落库已接 TextTranslationService；§110 新增任务队列/人工裁决纯核心；§122 已把冻结 spec 接入私有 AtomicFile 队列和当前工程顺序执行 | §81—97 源码已由 #158 覆盖：固定目标/配置、有效响应/最终译文暂存、显式同 Run 接续、暂停屏障/通知、任务历史分页、持久原始批次预算/拆分队列、派发前/已失败/部分提交与去重派生回执恢复、FAILED/NEEDS_REVIEW 分类；§122 队列仍缺跨工程自动续排、多批成员跳过/裁决 UI、暂停后可靠续跑与设备验证；完整人工裁决、被动重复状态维护及全崩溃点验证仍缺 |
| 规则与校对 | 四类规则/提示词持久化、RE2/J 文本流水线及五语页；本页批选、六类本地检查、保存译文扫描/警告与缺失术语筛选、相邻定位、单条草稿替换和规则基线同步已有 CI；原生规则交换、预设库管理 UI、提示模板渲染与模板库管理已接存储/UI；§62/§63 普通查询与固定质量筛选集合的全量批写已接存储/UI，预览固定目标、显式确认与事务回执均有源码（§62/§63 已由 #158 覆盖） | §102—107 工作区未提交源码新增：§102—103 LG JSON/XLSX 与原生五列 CSV 规则交换及系统文件入口，§104 模板库文件导入/逐项差异/合并更新替换与导出，§105 模板正文编辑/触屏排序/草稿保护，§106 全查询/质量筛选字面译文替换，§107 校对正则模式与 JavaScript 替换字符串；均未提交、未构建。§110 已接单条姓名编辑与逐修订快照，姓名批量、姓名/译文双目标替换、PDF 及宽屏校对台未完成 |
| S2 原生 Agent | Koog 结构化对话、工程查询/单条差异审批、HTTP/模型发现、SSE 正文/思考预览、持久记录与重启只读核对已有 CI；§74 源码新增项目摘要/列表/字面搜索/统计只读工具；§110 新增暂存输入队列/steer 核心与 Room v9 持久化，Agent 页可暂存/使用/发送下一条；§122 接自动上下文摘要压缩（摘要请求走同一请求边界/回执/用量） | §78 远程对话已接应用级执行器和独立前台 Service、共享预览及界面重附着（#158 覆盖）；短时本地演练/审批仍由页面发起；队列自动续跑的竞态/设备验证、steer 停止竞态/恢复界面验证、跨进程接续、分支/检查点、全业务写工具、多对象/部分接受审批未完成；自动压缩尚缺用户开关/提示和真实供应商验证 |
| 模型与调度 | 既有统一网关/FIFO/固定并发/RPM 已接线；五协议编解码/非流式传输、密钥池/冷却/Retry-After、三态能力目录、事实分离用量账本已有独立核心与 CI；§69 已由 #158 覆盖，把协议/多密钥存入 Room v5，翻译与 Agent 非 Chat 协议走协议传输，KeyPool 选择/失败分类与进程内 UsageLedger 已接网关；§78 Agent/批翻共用同一进程密钥池，认证耗尽不再归为限流，模型发现已接 Anthropic/Gemini 路径与认证；§80 用量账本与派发/响应在应用 Room v6 同事务持久化，提供全局/工程/会话读取入口；§79 密钥轮换/禁用/冷却已接原子文件持久化；§110 新增费用定价/成本核心；§112 接用户价格表持久化、单价编辑与账本逐请求费用（未构建） | OAuth、动态限额/探测、完整模型目录/能力探测、费用历史定价快照/汇总、完整网络设置未完成；真实供应商未验 |
| 应用 Room | NativeAppDatabase 源码为 v13，保留 v1→v13 升级链；v4 请求元数据、v5 模型协议/多密钥、v6 用量账本、v7 固定翻译任务、v8 响应/提交暂存、v9 Agent 输入队列/steer 持久化的源码及迁移在工作区 | 2/3/4/5/6/7/8/9/10/11/12/13.json 权威 KSP 导出仍缺；v9—v13 及设备升级待验 |
| 格式 | Markdown MD_V2、VTT、四类字幕桥接、KVJSON/MESSAGEJSON、TRANS、RenPy、EPUB、XLSX、MV/MZ 适配器及 ProjectFormatService 已接工程资产服务并有 CI；§72 已由 #158 覆盖，加入 RGSS v1/v3、Marshal、Scripts 与 Ruby 字面量写回并注册格式；§110 新增 RGSS Data/* Marshal 字符串扫描/写回；§122 新增 PDFBox-Android 页级 PDF 读取/替换写回与运行时初始化 | 各适配器仍有未移植分支，不能宣称完整格式兼容；PDF 仍缺页决策 UI、原版式/图形/扫描件策略与字体资源，RGSS 非脚本 UI/目录选择、WOLF 专有分支等未完成；各格式设备/完整往返验收待补 |
| 工作区/技能/联网 | core/workspace 已有路径/预算/材料/changes/审批/脚本端口/技能/联网契约与编排核心、文件系统材料/产物/指纹适配器及测试源码（已由 #158 覆盖）；§122 新增 QuickJS 引擎适配器、脚本目录/宿主桥、文件技能库与 More 页技能管理，Web Fetch 公网/重定向策略适配器 | 真实脚本引擎尚未接入 Agent 工具/隔离 Service；生产审批/apply 适配器、动态内置技能资源、搜索/MCP 仍待接线；PDF/图片宿主与设备性能未验 |
| UI/系统/交付 | 原生工程、Agent、工作台、校对、规则/模型等切片与新增组件已有 #158 CI；§70 工程备份/恢复 ZIP 与工作台入口、§69 协议/多密钥设置已接源码；§110 姓名编辑与 Agent 暂存队列对话框已接；§122 新增 More 页、主题、诊断日志、应用数据备份、技能库、翻译队列与模型/设置入口；`NativePage` 为 AGENT/WORKBENCH/PROOFREADING/MORE（RULES 从 More 进入） | 新增原生 PNG/视觉、手机平板/IME/可访问性、SAF/通知/锁屏/进程死亡、真实供应商及 KeyStore 真机未验；应用备份不含工程实例/凭据/会话；完整 U05—U15、旧主链路退役、性能/升级/全功能验收未完成 |

整体处于基础业务可运行、多资产与格式扩展已有 CI、部分新核心待生产接线的阶段。S1/S2/S3、当前有效功能和 G1—G5 均不按局部用例数或代码量计算完成百分比。历史 TXT 手工设备确认不覆盖本批新增能力；CI 全绿不等于真实供应商、全部格式或完整产品验收。

当前验证更新（2026-10-24）：本次已只读核对 Actions #158 / run 40085555783，提交 `77daf74` 的 test/lint/release 全部成功，Release APK 构建、签名校验及上传成功。该提交已包含前述 §51—101 的源码与后续编译/测试修复，覆盖基线已不止 #146；各节“未构建”保留当轮历史，不再表示这些源码从未经过 CI。#158 不替代真机、真实供应商、完整格式/功能验收或缺失的 schema 导出。§102 起的新工作区增量不继承 #158。

## 4. 下一轮顺序

1. 先让 §102—107 的工作区源码/测试通过 PC/授权 CI（编译、test、lint、release），修复后再提交；这批增量不继承 #158。随后生成并审阅 Room 2/3/4/5/6/7/8 schema、录制并实际查看原生组件 PNG；补现有远程/规则/质量/多资产切片的设备流程、SAF、通知停止、重建/锁屏及大工程预算。真实供应商调用按明确授权执行。
2. §79/§80 已由 #158 覆盖持久密钥状态和事务账本，§81—97 已接批翻固定任务、暂停、分页及有证据边界的显式恢复；下一阶段补设备验证，并继续实现费用定价、能力目录生产授权、动态限额、完整任务排队/人工裁决和多批错误标记接续。原生规则/预设/模板库已接，§102—107 工作区继续补 LG JSON/XLSX/CSV 交换、模板库编辑排序与系统预置，不重复登记已接线部分为零进度。
3. Agent 应用级前台执行/同进程重附着已由 #158 覆盖；继续输入队列/引导、分支/压缩/检查点、跨进程接续、完整工具与多对象审批。已接线的翻译显式接续不等于 Agent 接续，更不等于自动重启付费运行。
4. 按对应技术门槛推进脚本/技能/联网工作区、现有格式缺失分支、PDF、RGSS 非脚本数据/目录 UI和完整系统交付/UI。
5. 当前不要求旧 LNT 数据迁移，R36 不作为交付门槛；旧数据不主动删除。按新主链路完成情况退役旧代码，补全当前 96 项有效功能范围、性能/升级/设备/视觉证据。

## 5. 待原型解决的决策

| 决策 | 默认方向 | 形成最终决定的任务 |
|---|---|---|
| Koog 检查点/序列化与依赖 | 保留 1.3.0 接入，补应用执行记录，不继承旧消息存储限制 | R02 |
| 最低 Android 版本 | 从 API 24 保持起步，不用 Manifest override 掩盖真实不可用 | R02/R05/R06 |
| 脚本引擎 | QuickJS 首个候选，独立受控能力桥；不运行 Node 业务服务 | R03 |
| 工作区隔离与停止 | 隔离 Service 原型，真实拒绝/取消/回收证明后定型 | R03/R06 |
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

### §49 CI 结果与修复（2026-10-24）

- 分支 `refactor/translation-agent-2026-10`，提交 `2643b2c`，手动触发 [Actions 40007619940](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40007619940)：test / lint / release 全部 success。
- test 共 1278 项，0 failure/error/skip（app 726；core 552，含新格式、协议、规划、规则与多资产用例）。lint 与签名 release 成功，APK 22,469,614 字节。
- 连续修复：资源字符串中的 `Ren'Py` 撇号；格式注册表公共类型；资产回执 `Pair` 访问；v1/v2 懒升级与版本感知 item 列；导入校验的修订链；RenPy raw→普通引号配对；工作台测试调用与质量筛选滚动。
- 仍未取得：真机/视觉、真实供应商、Robolectric 截图、Room 2/3/4 schema 导出（当前 Room 为 v4，无 v5）、PDF/RGSS/工作区/旧数据迁移和完整 UI。CI 全绿只证明本批量源码可编译、可运行现有与新增 JVM/Robolectric 用例及可签名打包，不关闭 S1/S2、G1—G5 或 98 项矩阵。


## 50. 按实际源码同步当前文档（2026-10-24）

用户要求根据代码修正文档。本轮从 `5e35a72` 干净工作区开始，只修改说明：重写 §3 总览与 §4 顺序，同步根 README/AGENTS、计划入口及存储/模型/翻译/规则/校对/字幕/架构/UI 专题。§7—49 的开发轮原始验证描述保留历史语义；当前结论指向 §49 的 Actions #146。

静态核对 NativeAppDatabase 实际为 Room v4、原生工程 schema v3、适配器目录和应用注入，明确已接线与独立核心的区别；纠正 §49 CI 尾注的 5.json 缺项误记。新增五协议/密钥池/账本、翻译规划/模式/去重/重试/检查点及规则交换/预设仍需业务接线。完整格式、工作区、恢复、旧数据迁移和全量验收不因 CI 通过关闭。

本轮只做静态文档/符号/链接核对、diff 空白、冲突标记与工作区检查，未实际构建、测试或 Lint；实时查询 #146 为 success。本轮未新增设备、视觉、供应商或 schema 产物，未修改产品源码、用户数据，未提交、推送、触发 Actions 或发布。


## 51. 实际批翻接入资产分组规划（2026-10-24，未提交源码）

用户设置持续目标：完成所有可执行实现，直到只剩验收。本轮保留 §50 文档修改，继续产品源码，不提交/推送/触发 CI；§49 全绿不覆盖本轮增量。

TextTranslationService 已把手写分批替换为 TranslationPlanner：每页准备规则处理后的快照，按真实 assetId 分组，使用既有条数/字符配置，禁止同一请求混合资产；准备后超限在派发前拒绝。分页仍有界，不收集整工程正文。启动时在运行租约下固定启用资产集合，停用资产不进入请求且保留原状态。共享网关、派发检查、请求/Token 记录和原子提交仍使用原链路。页内规则准备现先于该页请求，准备失败不发送该页请求，之前页已提交事实保留。

此轮只接 NEW 模式下的资产分组与预算规划，不代表模式/前文/去重/重试/持久检查点或恢复全部接通。新增真实存储集成回归源码覆盖启用/停用资产、单请求单资产、请求身份集合及停用条目不写回，尚未运行。完整五协议/密钥池/账本、工作区/技能/联网、复杂格式兼容、完整 UI、任务恢复及迁移退役仍需持续实施，目标保持 active，不能宣称只剩验收。

静态结构/符号/导入/文档链接检查与 git diff --check 通过；冲突标记无匹配。本机未实际构建、测试或 Lint，无新增设备/供应商证据。


## 52. 远程批翻三种启动模式的实际接线（2026-10-24，未提交源码）

持续实施目标保持 active。新增 domain TranslationStartMode 与 RemoteTranslationOptions.startMode，参数参与前台请求完整 equality/命令重绑定检查。五语远程确认表单可选 NEW（未处理）、CONTINUE（未处理与 ERROR）、RESET（含 PROCESSED）；重新翻译另示再次外发/费用提示，原译文在新译文成功提交前保留。排除、规则/语言跳过、重复和停用资产不因模式被隐式重新启用。

TextTranslationService 根据冻结模式筛选，并传给 TranslationModeCommand/TranslationPlanner；ViewModel 的整工程资格与工作台远程入口同步支持失败和已译工程，避免底层支持但 UI 不可达。RESET 不先清空整工程，失败/停止仍保留此前已提交事实和未替换的旧译文。

新增真实存储回归源码覆盖 NEW 不重试 ERROR、CONTINUE 只补失败、RESET 请求失败保留旧译文、显式重新翻译成功替换。该用例及 §51 用例均未运行；本轮新增五语 XML 解析/重复键检查、静态结构/导入/符号/链接、git diff --check、冲突标记和状态核对通过。本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，未新增设备/供应商证据；#146 不覆盖增量。

剩余前文/去重/失败策略/检查点接续、协议与密钥池/账本接线、工作区/技能/联网、格式完整分支与兼容、完整 UI/系统交付及旧数据迁移/退役继续实施；尚未达到只剩验收。


## 53. 有界同资产前文的存储/协议/UI 接线（2026-10-24，未提交源码）

延续持续实施目标。新增 TranslationContextPort 与只读 TranslationContextLine：存储在同修订事务内根据锚点资产/slot 读取此前最多 64 个条目，兼容旧版本 row_index 查询，拒绝过期修订和不存在的锚点。批翻在每个请求前复用 TranslationPlanner 筛选同资产前文，使用冻结 precedingLines（0—16，默认 2）、累计 2048 字符/估算 Token 上限，遵循已有空行/句尾/跳过状态规则；前文读取基于当前已提交修订，可跨分页，不使用列表截断预览。

TranslationModelRequest 将前文与 inputs 分开，校验数量/字符预算、唯一身份及与输入集合互斥。CompatibleTranslationProtocol 将其序列化为 precedings 并明确仅作不可信上下文，响应身份仍严格绑定 inputs，模型返回上下文条目也整批拒绝。前文不进入 TranslationBatch，不独立推进修订；确认表单五语显示前文参数，参数参加冻结请求 equality，改变配置不能重绑已有命令。

新增未运行回归源码：真实存储按每页 1 条翻译时前文数量为 0/1/2，且写回仍仅三条；协议序列化与额外上下文输出拒绝。XML 解析/重复键、结构/依赖/符号/链接、git diff --check、冲突扫描与状态核对通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备或供应商证据。既有 CI 不覆盖 §51—53。

前文目前传源文上下文，不代表完整 LG 全部提示组合/上下文缓存已实现；去重/重试/检查点恢复、完整模型接线、工作区/格式/迁移等目标仍开放，持续推进，未达到只剩验收。


## 54. 显式预算的批翻重试与拆批（2026-10-24，未提交源码）

持续实现目标保持 active。RemoteTranslationOptions 新增 additionalRequestsPerBatch（0—5，默认 0），五语确认表单显示额外费用与分类行为，参加冻结请求完整比较。TextTranslationService 接入 TranslationRetryPlanner 的瞬时退避、协议/容量拆分和终止分类；每个原计划单元共用额外请求总额度，拆分的子请求也计入，不按子块重新无限扩额。默认 0 保持既有失败立即停止行为，认证/过滤等终止类不消耗重试额度。

每次替代请求创建独立 attempt、使用原输入指纹/冻结配置并重新经共享网关/派发/持久审计，延迟可取消，失败后入队前检查运行身份与 phase，派发前仍由统一网关再次检查。仅捕获模型失败做策略决定，存储/提交失败不转为付费重试；成功子批立即提交并移出队列。运行用量对失败/不确定响应保留 UNKNOWN，已取得的实际 HTTP 计量仍由持久审计保存，不伪造为零。

新增未运行真实存储集成用例：协议失败后 2→1+1 拆批、三次独立 attempt/仅成功子批写回；认证失败即使有 5 次额度也只调用一次；失败后 Stop 禁止替代请求。XML 解析/重复键、静态结构/导入/符号/文档链接、git diff --check 和冲突扫描通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无设备/供应商新增证据，#146 不覆盖增量。

仍缺持久化重试队列/策略检查点、失败条目终局状态与继续策略的完整产品对齐；当前终止失败保留已提交事实并结束 Run。去重、模型全协议/密钥池/账本接线、工作区/格式/迁移等仍待实施，尚未达到只剩验收。


## 55. 同文件本次运行的重复译文复用（2026-10-24，未提交源码）

持续目标保持 active。RemoteTranslationOptions 新增 deduplicateSameFile（默认 false），五语确认页提供独立开关并明确“同文件/本次运行/普通条目”；参数随前台请求冻结。页内使用 TranslationDeduplication 对 eligible 普通条目按原文字面与 assetId 分组，仅代表条目进入模型请求；模型结果完成规则后处理后写回组成员。不同文件、不同原文、排除/停用/不符合模式的条目不混入；带命名或格式私有元数据的条目保守独立请求。

跨页新增 TranslationReusePort：在当前修订事务内读取目标，按同资产、精确原文、默认元数据查找 PROCESSED 且存在本次运行起始修订之后提交记录的来源。运行前旧译文不复用，防止冻结规则/语言变化后错用旧结果；不在内存建立整工程正文/译文缓存。复用只减少模型调用，目标结果仍通过 ProjectWriteStore/TranslationBatch 写入，按最多 20 条原子子批记录修订、命令回执与执行 checkpoint；失败/停止保留已提交事实，不额外计模型请求/Token。

此轮交付为显式的译文复用，目标状态为 PROCESSED；未完成完整 LG 被动 DUPLICATED 状态维护/代表重选/全工程反向同步，不能冒称该功能整项关闭。新增未运行存储集成回归覆盖跨页三条同文只请求一次、另一资产仍独立请求，以及旧译文不复用/同页新代表派生写回。静态结构/导入/符号/链接、五语 XML/重复键、git diff --check、冲突及状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/供应商证据。

持久恢复、完整失败状态/去重协调、全协议/密钥池/账本接线、工作区/技能/联网、格式兼容、完整 UI 和旧数据迁移/退役等继续实施。目标未完成，尚未达到只剩验收。


## 56. 失败条目持久化与重试日志状态修复（2026-10-24，未提交源码）

持续目标保持 active。静态追踪发现 §54 重试在模型失败后仍保留 REQUESTING，下一次 attempt 会被 NativeRunStore 的未决请求保护拒绝。本轮新增 REQUEST_FAILED 元数据阶段，必须绑定同一翻译 attempt/请求计数；如果持久审计仍为 DISPATCHED，保留 needsReview，即使后续替代请求成功也不冒充所有付费回执均已确定。此阶段不授权重放，重试仍只能来自用户冻结额度。未改变 Room 表结构或版本。

新增 TranslationFailurePort/TranslationFailureBatch，在真实运行写租约下经 ProjectWriteStore 事务把本轮未处理条目标为 ERROR，保留已有文本/对象指纹检查/命令回执；存储拒绝对 PROCESSED、排除等状态执行该失败写入。仅终止的协议/截断/内容/过滤/容量/空响应类落条目失败，认证/网络/服务端等整体故障不把普通条目误标为内容错误；停止后不写失败状态。RESET 原先已译条目维持其旧译文和 PROCESSED，不因新请求失败丢失可导出结果。

新增 MARKING_FAILURE checkpoint，以同一终局命令回执机制恢复失败写入，核对成功时推进工程修订但不增加 committedItems（成功翻译数），避免进程死亡窗口把错误状态写入误计为翻译成果。失败条目可经 §52 CONTINUE 重新发起显式运行；当前终止失败仍结束 Run，不自动跳过后继续其他批次。

新增三项待运行回归源码：协议失败落 ERROR 后显式 CONTINUE 成功；失败状态写入后模拟进程恢复计数仍为 0；未知旧派发经失败阶段允许新 attempt，但最终保持需核对/INTERRUPTED。静态结构/依赖/符号/链接、git diff --check、冲突与状态核对通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，没有新增设备/供应商证据，既有 CI 不覆盖增量。完整任务检查点/恢复、去重状态协调、模型/工作区/格式/迁移等剩余范围继续实施，未达到只剩验收。


## 57. 资产管理 UI 与绑定命令（2026-10-24，未提交源码）

持续目标保持 active。本轮新增 NativeAssetMutation，绑定实例/代际、工程修订、资产 ID、操作与 commandId；Workbench 接入上移/下移、重置和移除入口，五语确认显示文件名及条目数量，最后资产不允许移除。界面与 ViewModel 阻止运行占用、编辑草稿、人工批次及未保存规则下的资产写入；状态错误或修订变化不重定向操作。成功后先发布已提交修订/保存提示，再刷新规则基线与首个分页，清理旧质量结果。

新增未运行 ViewModel 集成回归源码，覆盖重置后刷新、旧修订拒绝、顺序变化、移除与最后资产保护。静态结构/资源/链接、五语 XML/重复键、diff 空白和冲突检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions。

**静态审查确认的待修缺陷，下一步先修**：ProjectAssetWriteStore.remove 直接删除 items，但 item_revisions 外键引用 items 且没有级联；曾编辑/重置/翻译的资产会因历史外键而不能物理移除。直接删历史/回执会破坏全局修订和旧命令核对，因此本轮不采用清历史绕过。上面的新增回归包含该情形，尚未运行，不能登记完整资产移除已可用；需实现可校验的归档/移除历史后再验收。完整功能目标不缩减，当前不是只剩验收。


## 58. 原生工程 v4：保留历史的资产移除（2026-10-24，未提交源码）

修复 §57 确认的外键缺陷；持续目标保持 active。NativeProjectFormat.SCHEMA_VERSION 升至 4，新建工程使用 v4。新增 archived_items 存放被移除条目的源文、译文、状态、格式元数据与当前指纹；item_revisions 保留完整原记录及 write_receipts，v4 用活动/归档联合身份校验替代只能指向活动 items 的外键。v1/v2 经既有迁移到 v3，再于资产移除路径执行事务 v3→v4；读取旧版本不强制升级。应用 Room 继续 v4，未改 Room schema。

ProjectAssetWriteStore.remove 在同一事务归档条目、删除活动条目/固化原始字节/资产、规范排序并记移除修订与回执；旧命令回执仍可查询。归档条目不参与活动列表/统计、批翻、校对或格式导出；保存的 `.lnt` 快照包含归档与完整历史。外部源文件不触碰。原始二进制资产按显式移除语义不继续保留，但历史条目源文/元数据与修订保留，本轮不提供归档恢复 UI。

严格导入增加 archived_items 类型/正文/元数据预算、活动/归档 ID 互斥、被移除资产关系、联合指纹修订链与孤立历史拒绝；全局修订和既有回执对应关系仍逐项核对。活动加归档累计条目受工程上限约束。追加资产使用 sqlite_sequence 高水位分配 ItemId，不因最高 ID 被移除而重用身份；导入校验高水位包含归档条目和已移除资产。

新增未运行存储集成回归源码：重建旧 v3 外键形状后移除已编辑资产、旧回执保留、v4 保存/导入及归档正文、后续追加不复用 ID，以及篡改归档译文被严格拒绝；旧 v1 规则迁移夹具同步重建真实旧结构。§57 的 UI 回归现可覆盖有编辑/重置历史的移除路径，仍未运行。

静态结构/依赖/符号/链接、git diff --check、冲突及工作区核对通过；本机未实际构建、测试或 Lint，无新增 CI/设备/供应商证据，未提交/推送/触发 Actions。本批 v4 迁移/归档必须由后续 PC/CI 和设备验收；当前不宣称已经验证成功。任务恢复、完整去重/模型/工作区/格式/迁移等总体剩余范围继续实施，未达到只剩验收。


## 59. 工程设置管理与导出消费者接线（2026-10-24，未提交源码）

持续目标保持 active。Workbench 新增五语工程设置对话框，经 NativeProjectSettingsChange 绑定工程身份/代际、修订及稳定 commandId，通过既有 ProjectAssetPort.editProjectSettings 保存。运行占用、编辑/人工批次/未保存规则和加载失败阻止保存，旧修订拒绝；提交后先更新已保存事实/提示，再重读规则基线和分页。对话框只有观察到匹配的已保存设置及推进的修订才自动关闭，失败保留表单；取消是显式放弃。未展示无消费者的独立 promptEnabled 字段，保存时保持原值，提示词仍由工程规则页拥有。

实际消费者：工程语言预填后续远程确认（仍须用户确认）；双语同文去重用于传统 TXT 与资产双语导出；writeTranslatedNames 传给已有 RenPy/Message JSON 写回器，目标语言传给支持它的格式写回。工作台新增按资产双语导出。保存设置不触发模型调用或重翻已保存结果。

资产导出选择器参数改为结构化保存身份三元组、资产 ID、修订及冻结 FormatWriteOptions，不再用分隔符拼身份；旧/损坏形状拒绝。传统导出同样保存修订/双语去重选项。选择器迟到时检查当前工程与存储修订，不把导出悄然重定向到新设置/新工程，检查在打开输出流之前。若数据已变化须用户重新发起导出。

新增未运行 ViewModel 回归源码覆盖设置实际持久/刷新、旧修订拒绝、冻结资产与传统双语选项及设置修改后迟到导出拒绝。五语 XML/重复键、静态结构/导入/符号/链接、git diff --check、冲突及状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。完整应用设置对齐/配置管理、后台恢复、工作区/格式/迁移等目标继续实施，未达到只剩验收。


## 60. 资产启停命令、工作台开关与批翻资格（2026-10-24，未提交源码）

持续目标保持 active。ProjectAssetPort 新增 setAssetEnabled，AssetMutationKind 增加 ENABLED；命令摘要绑定工程身份/代际/修订、资产和明确目标布尔值，重放相同命令返回原回执，参数重绑拒绝，过期修订/未知资产有终局拒绝回执。更新 enabled、全局修订、asset_revisions 与 asset_receipts 在同一写租约/事务内，取消回滚；没有新增 schema 或 Room 版本。

Workbench 五语“参与批量翻译”开关通过明确 ENABLE/DISABLE 命令接入。运行占用、编辑/人工批次或未保存规则时禁用。停用保留固化原稿、条目、译文和既有状态，仍可查看/导出；既有执行器冻结 enabled 集合阻止模型派发。ItemPreview 补实际 assetId 并在编辑/批操作刷新时保留，页面启动资格按成员身份排除停用资产；translatableUnprocessed 统计排除停用资产，普通 NONE/各状态总计仍保留它们。

新增两项未运行回归源码：真实存储命令重放/重绑/过期拒绝、保存重导入、停用零模型请求与重新启用可翻译；ViewModel 开关刷新且源文保留。补停用统计仍保留未处理总数而可翻译数为零的断言。五语 XML/重复键、静态结构/导入/符号/链接、git diff --check、冲突和状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。

远程 CONTINUE/RESET 的整工程入口资格仍可按总 ERROR/PROCESSED 计数保守开放，执行器会过滤停用资产且无目标不付费；完整资产分区统计/筛选 UI 尚需继续。任务恢复、模型/工作区/复杂格式/迁移等目标保持开放，尚未达到只剩验收。


## 61. 校对资产筛选、同范围统计与质量扫描（2026-10-24，未提交源码）

持续目标保持 active。TextItemQuery 增加可选正 Long assetId，参数参与 active/equality；存储 queryPredicate 同时消费资产、状态与字面文本，列表分页、固定窗口计数和相邻导航共用该谓词。TextProjectPage 新增 matchingStatistics，和 matchingItems 同一事务按完整查询聚合；全工程 statistics 保持原范围，不把筛选条数冒充总数。ViewModel 刷新/恢复/固定窗口均同步新统计。

五语校对筛选对话框新增全部/指定资产选择和完整查询的七类状态统计。筛选草稿不写库，应用后资产 ID 按 Project/Instance/Generation 保存与恢复，清除筛选时也清除资产身份。资产移除后保留该身份，列表与计数为空、选择器提示资产不可用，不能静默回退为全部资产。ItemPreview 使用上一轮补入的实际资产 ID。

ProjectQualityScanner 按所选资产有界分页读取，先绑定同修订的资产范围总数、逐页核对范围计数与成员，正文预算与进度只覆盖该资产，再应用状态/字面条件。无资产条件保持既有整工程扫描口径；质量索引继续绑定完整 TextItemQuery，旧筛选不能复用另一资产索引。不新增 schema，不调用模型或写入条目。

新增未运行回归源码：真实多资产存储的查询/分页/全局与筛选统计/双向导航/质量范围及未知资产空结果；ViewModel 筛选恢复、移除后不扩大范围。五语 XML/重复键、静态结构/导入/符号/链接、git diff --check、冲突和状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。完整全查询批写/替换、宽屏校对、任务恢复/模型/工作区/复杂格式/迁移等目标继续实施，尚未达到只剩验收。


## 62. 全查询人工批量操作与大预览恢复（2026-10-24，未提交源码）

持续目标保持 active。新增 QueryManualBatchPort.prepareQueryBatch，在同修订只读事务按资产/状态/字面查询选定所有实际需要变化的条目，仅返回 ID/行号/指纹元数据；没有选择时不生成命令。ManualTextBatch 上限扩至工程 100,000 条，原本页入口仍受窗口 100 条上限。editBatch 在单写租约/事务对固定集合预检修订和指纹后全批写入，拒绝结果/成功回执仍可按原命令核对重放，确认时不重新执行查询或扩大集合。

ProjectWriteStore 的批量预检不再一次装载全部正文，仅检查行号/指纹；提交时逐条读译文，原稿只在单条指纹/修订写入期间存在。大集合相关元数据可持有，正文不会进入大选择集或 UI。旧 manual-batch-v1 摘要与子命令编码保持兼容。预检、回执重读与写入有取消检查，整事务回滚；大规模设备耗时/内存尚未验收。

校对页五语新增“批量操作全部筛选结果”，支持四个既有动作，并在确认中明确固定范围。全文质量结果二级筛选目前不开放此入口，避免把质量选择误当作普通 SQL 查询。全部查询预览存入应用私有 native_manual_batches（只含身份/指纹，无正文），SavedState 仅存 64 字符摘要，避免 Binder 大状态；32 MiB 单文件、最多 4 份/64 MiB，总预算拒绝超额。写入临时文件后 fsync/rename，加载有结构/大小/摘要校验，恢复后仍要求明确确认。损坏/缺失时提示核对已保存事实，不自动执行；取消/成功清理文件，用户清理临时产物时保留当前引用预览并清理其余文件。

新增三项未运行回归源码：145 条跨页固定批次/原命令重放/保存重导入；并发修订变化全批拒绝且原译文保留；ViewModel 大预览私有文件恢复、SavedState 不存大集合且未确认不写库。五语 XML/重复键、静态结构/导入/符号/文档链接、git diff --check、冲突与工作区检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。

仍缺全查询查找替换/质量筛选批写、完整校对算法与宽屏交互；任务持久恢复/模型接线/工作区/复杂格式/迁移等总体剩余目标继续实施，未达到只剩验收。


## 63. 固定质量筛选集合的全量批写（2026-10-24，未提交源码）

持续目标保持 active。QueryManualBatchPort 新增 prepareQualityBatch，接收不可变 TextQualitySelection；准备阶段同事务核对工程/实例/代际/修订、普通查询范围、筛选事实和每个当前条目指纹，仅收集实际需变化的轻量目标，不把完整质量扫描重跑成另一份范围。空选择返回无操作，不回退到普通查询或整工程。

校对页在当前有效质量索引下显示五语“批量操作全部质量筛选结果”；调用既有四种人工动作。准备结果继续使用 §62 的私有预览文件、摘要 SavedState、显式确认、固定目标事务及终局回执；恢复时只恢复固定预览，不自动重建质量索引或自动写入。确认不要求原内存索引继续存在，但仍须工程修订与条目指纹匹配。批写后保留当前窗口的已提交事实并将旧质量索引标为失效，禁止再按旧结果准备/翻页，需显式重扫或返回普通列表。

新增两项未运行回归源码：术语缺失警告筛选仅修改匹配条目、空选择无操作、旧索引拒绝；ViewModel 跨页 145 个未评估结果的固定批写，另一个已译条目保留，旧索引失效且不能再次准备。五语 XML/重复键、静态结构/导入/符号/链接、git diff --check、冲突及状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。

全查询查找替换、完整校对算法/宽屏、任务恢复、全协议/密钥池/账本、工作区/复杂格式/迁移等剩余目标继续实施，未达到只剩验收。


## 64. 原生规则交换预览与预设存储基础（2026-10-24，未提交源码）

持续目标保持 active。规则页导入对话框新增显式三格式：工程 policy JSON 替换、原生 RuleExchange JSON 合并、原生 RulePreset JSON 合并/整体替换。支持是否采用提示词、交换规则匹配 ID 覆盖策略及预设替换策略，预览在 Default 执行共享 RulePreviewService/RE2/J 预算验证，显示新增/更新/不变/冲突或移除计数；存在冲突不生成可应用结果。参数或文本改变清预览，弹窗绑定工程规则草稿命令，应用结果仍只是草稿，需既有显式保存/修订检查。另新增原生交换 JSON 复制入口，编码超限显示错误，不声称 LG JSON/XLSX/CSV 兼容。

新增 domain RulePresetCodec（lnt-rule-preset 严格 envelope）及 RulePresetSummary；NativeRulePresetStore 已由 NativeAppGraph 装配，提供轻量列表、按 ID 延迟读取、保存和删除。应用私有路径按 ID SHA256 命名，AtomicFile 原子写入/备份恢复，UTF-8 严格解码，限制 128 份/16 MiB；覆盖/删除须匹配先前内容指纹。该库不写工程或授权 Agent。**库的管理 UI 尚未接线**，本轮只完成存储基础和原生预设 JSON 导入，不把完整预设管理登记为完成。

新增未运行回归源码：预设 envelope 往返/未知字段或版本拒绝；真实应用私有存储的哈希路径、持久读取、旧指纹覆盖/删除拒绝及正常 CRUD。五语 XML/重复键、静态结构/依赖/符号/链接、git diff --check、冲突和工作区检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。下一步接预设库管理及预览应用；全查询替换、任务恢复/模型/工作区/格式/迁移等剩余范围继续实施，未达到只剩验收。


## 65. 原生预设库管理界面与草稿应用（2026-10-24，未提交源码）

持续目标保持 active。规则页已通过 ViewModel 的 NativeRulePresetActions 接入 §64 真实 NativeRulePresetStore。新增五语库管理对话框：轻量 LazyColumn 列表、按选择延迟载入正文，当前工程草稿可另存新预设；支持名称/说明修改后用当前草稿覆盖所选预设、删除、复制原生预设 JSON、重新载入。覆盖/删除有明确确认，操作绑定已读取内容指纹，存储拒绝旧版本覆盖或删除。库写入不保存工程、推进工程修订或发模型请求。

选中预设后可选择合并/整体替换及是否带提示词，显式预览新增/更新/不变/移除，再应用到规则草稿；仍须原有 Save 提交。弹窗绑定工程/规则草稿命令，原基线变化不应用旧预览；切换预设或应用策略清理旧预览。RulePreset 校验/预览/编码在 Default，存储在 IO；提示词和完整规则不进入列表摘要。保存/删除失败显示错误并保留当前表单，库操作期间阻止重复操作。

新增两项未运行 Compose 回归源码：选中与预览不应用，显式确认只更新预期草稿；将当前规则/提示词另存预设不会应用或提交工程。与 §64 的真实存储/指纹回归共同覆盖不同边界，但均尚未执行。五语 XML/重复键、静态结构/依赖/符号/文档链接、git diff --check、冲突及状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。

§64 的“库管理 UI 尚未接线”对本轮源码已更新；当前仍只是原生 LNT 预设，不声明 LG 预设/XLSX/CSV 兼容、系统文件交付或提示模板管理全部完成。全查询替换、完整校对/任务恢复/模型/工作区/复杂格式/迁移等剩余目标继续实施，未达到只剩验收。


## 66. 提示模板解析、变量填写与草稿渲染（2026-10-24，未提交源码）

持续目标保持 active。规则页新增五语提示模板入口，复用 domain PromptTemplate。模板正文绑定当前工程/规则草稿命令，显式解析占位符后展示最多 32 个变量输入；project_name/source_language/target_language 按当前工程预填。变量单项/总量受 4096/32768 字符预算限制，正文和变量值随工程身份保存恢复，解析对象和预览不落 SavedState，恢复后须再次显式解析/预览。

新增 ProjectPromptTemplateService，Default 线程执行单次替换、取消检查和完整工程 policy 编码预算校验；插入值不递归解析模板语法，不执行代码、网络或模型请求。输出超 16384 字符或规则+提示 envelope 超限直接拒绝，不截断、不覆盖旧草稿。预览后 Apply 只替换当前 policy 草稿的 prompt，保留 rules 和 promptEnabled；最终保存与冲突仍通过既有工程规则链路。

新增四项未运行回归源码：单次渲染/规则与启用状态保留、渲染字段超限/缺变量/取消、联合 policy envelope 预算，以及 Compose 预填变量/显式应用前不改变草稿。五语 XML/重复键、静态结构/导入/符号/链接、git diff --check、冲突与状态检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。

本轮是当前工程的模板渲染与应用，不是持久模板库/语言和启停排序/模板交换的完整实现；这些元数据消费者仍待接线。全查询替换、任务恢复/模型/工作区/复杂格式/迁移等剩余目标继续实施，未达到只剩验收。


## 67. 提示模板库编解码与原子持久化基础（2026-10-24，未提交源码）

持续目标保持 active。新增 PromptTemplateCodec（lnt-prompt-templates），严格保存模板 ID/名称/说明、enabled、语言标签、order、body；未知字段/版本、非整数排序、非法占位符和超量集合拒绝。模板按 order/ID 规范排序并计算内容指纹，禁用与语言范围沿用 PromptTemplateSet.enabledFor 的真实筛选语义。编解码上限 2,000,000 字符，模板数量继续最多 64，正文和变量上限沿用核心。

新增 NativePromptTemplateStore 并由 NativeAppGraph 装配：应用私有原子 JSON 文件，严格 UTF-8、有界 8 MiB 读取/写入、AtomicFile 备份恢复；整库替换须匹配读取的指纹。模板库与已有预设库改用各自进程共享 Mutex，避免同进程多个存储实例各持一把锁而绕过比较/替换。保存库不改变任何工程、授权或模型运行。管理 UI/模板库选择与渲染连接仍待下一轮实施，本轮不把完整模板管理登记为已完成。

新增三项未运行回归源码：模板 metadata/排序/语言筛选往返及指纹；未知字段/版本/小数排序/坏占位符拒绝；真实库保存/跨实例重开/旧指纹拒绝和清空。静态结构/依赖/符号/文档链接、git diff --check、冲突及工作区检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无新增设备/视觉/供应商证据。完整目标保持开放，尚未达到只剩验收。


## 68. 提示模板库管理界面与规则页接线（2026-10-24，未提交源码）

持续目标保持 active。§67 的 `NativePromptTemplateStore` 此前只被 `NativeAppGraph` 构造、没有消费者；本轮新增 `NativePromptTemplateActions` 与五语模板库对话框，并接到规则页的模板入口。库列表只展示名称/排序/启用状态，正文不进入列表摘要；选择模板只把正文交回渲染编辑器，不直接改工程草稿。支持将当前正文另存为新模板、按读取指纹覆盖/删除所选模板、复制整库原生 JSON、重载；名称/说明/启用/语言标签/排序均有边界校验，库写入不保存工程、不推进修订、不调用模型。

模板渲染对话框新增“模板库”入口；选中后仍须显式解析变量、预览并 Apply，最终保存继续走既有规则草稿与 `ProjectWriteStore` 修订检查。新增未运行 Compose 回归：选择模板只交给编辑器且不写库；保存当前正文创建模板但不应用。静态结构/依赖/符号/链接、五语 XML 重复键、Kotlin 分隔符、git diff --check 与冲突检查通过；本机未实际构建、测试或 Lint，未提交/推送/触发 Actions，无设备/视觉/供应商证据。

当前仍只有原生 LNT 模板库，不是 LG 模板交换/语言策略/完整管理页；工作区、PDF/RGSS、`.lg`/Pi、Agent 应用级执行与模型协议接线继续实施；旧 LNT 数据迁移已移出范围。整体尚未达到只剩验收。


## 73. 工作区文件系统适配器（2026-10-24，未提交源码）

新增 `WorkspaceFileStore`，实现工作区核心的 `WorkspaceMaterialReadPort`、`WorkspaceArtifactSink` 与 `WorkspaceSnapshotPort`。它以一个私有根目录管理 `sources/datasets/reference/uploads/work/artifacts`，每次路径解析都做 canonical 检查防止 symlink 越界；材料摘要不含正文，按 ID 延迟读取并校验大小/SHA-256；产物先写临时文件、fsync 后 rename，拒绝同一 run 的重复路径；指纹端口按文件计算 SHA-256。新增纯 JVM 测试覆盖列表/读取/超限/篡改、指纹、产物原子发布与重复拒绝。

该适配器仍不运行脚本、不审批、不写工程；`WorkspaceApplyPort`、真实脚本引擎、隔离 Service、技能资源和 UI 仍未接。未构建、未测试、未提交。


## 74. Agent 只读查询工具扩展（2026-10-24，未提交源码）

`nativeProjectTools` 在原有 `project_summary`/`read_item` 之外新增 `list_items`、`search_items`、`project_statistics`：只返回条目 ID/行号/状态/资产/长度/指纹与统计，正文仍只由 `read_item` 按 ID 读取；列表分页上限 50、搜索上限 20，字面搜索走既有 `TextItemQuery`，不解释正则。`project_summary` 补充资产列表、源/目标语言、双语去重、译名写回和目标语言。新增未运行测试用合成 `TextProjectPort` 覆盖摘要、分页、字面搜索与统计。完整业务写工具、多对象审批、应用级执行仍未实现；本增量未构建、未测试、未提交。


## 75. 旧 Room 适配器（已取消，2026-10-24）

随 R36 移出当前范围，旧 Room v6 只读适配器及测试已删除。当前不要求读取、迁移或归档旧 LNT 数据库；`.lg`/Pi 兼容仍按独立任务处理。

## 69. 模型协议、多密钥与用量账本接线（2026-10-24，未提交源码）

持续目标保持 active。`NativeModelProtocol` 进入 domain，`NativeModelSettings` 增加 protocol/keyCount 且保持旧构造调用源兼容；应用 Room 源码升至 v5，`MIGRATION_4_5` 增加 protocol/encryptedKeys/keyCount，保留 v4 单密钥列及原 AAD 读取。`NativeModelSettingsStore` 新增 `saveProfile`/`credentials`，多密钥逐条 Keystore AES-GCM（AAD 绑定 revision 与序号），拒绝空 key 列表中的空串与重复；旧 `save` 继续按端点复用/替换语义工作。

引擎新增 `NativeModelProtocolMapping`、`ModelKeyPool`、`UsageLedgerRecorder`。`CompatibleTranslationModel` 在非 OpenAI Chat 协议时走 `ModelProtocolTransport`，严格 request_id/条目身份/指纹/Token 审计保持；`CompatibleAgentModel` 对非 Chat 协议做非流式协议传输和历史/工具映射，完成后一次性发出预览事件（不冒充 SSE）。非 Chat Agent 的工具请求必须由精确 `CapabilityCatalog` 授权；当前装配仍为 EMPTY，工具型非 Chat Agent 会明确拒绝而不是伪造支持。两者每次显式请求只选择一个可用 Key；认证/限流/服务端/网络失败按分类记录禁用或冷却，不在单次请求内隐式重试。成功回执写入进程内 UsageLedger。模型对话框新增协议选择与多行 Key 输入，经 `saveProfile` 保存；旧单 Key 入口保持兼容。模型发现新增协议重载：Anthropic 走 `<base>/v1/models` + `x-api-key`/`anthropic-version`，Gemini 走 `<base>/v1beta/models` + `x-goog-api-key`，其余保持 `<base>/models`。

新增未运行测试：Anthropic 协议翻译与用量审计、KeyPool 认证失败轮换/限流冷却/无认证空池、协议与无认证档案重开。Room v5 迁移、KSP 2/3/4/5 schema、真实供应商、SSE 协议流式、OAuth、动态限额与跨进程 Key/账本持久化均未验证；模型发现已有 OpenAI 形状与 Gemini `models[].name` 解析、Anthropic/Gemini 路径和认证源码；完整目录/能力探测及真实供应商验收仍缺。本机只做静态结构/符号/资源/文档与 diff 检查，未构建、测试、Lint、触发 Actions 或提交。


## 70. 工程备份与恢复核心及工作台入口（2026-10-24，未提交源码）

持续目标保持 active。新增 `NativeProjectBackupService`：导出时先通过既有 `TextProjectPort.saveProject` 生成并校验 `.lnt` 快照，再写入含格式/版本/名称/来源身份/修订/字节数/SHA-256/时间的严格 manifest 和 `project.lnt` 的 ZIP；恢复只接受精确两个条目、总量与摘要校验，并始终通过 `importProject` 创建新身份，不覆盖现有工程。凭据、Agent 会话、模型设置、预设/模板库不进入工程备份。工作台新增五语导出/恢复按钮，经 SAF 选择器绑定当前工程修订；运行中、编辑草稿、人工批次、未保存规则或加载不完整时禁止。

新增未运行测试：真实 `.lnt` 编辑后备份/恢复为新工程并保留译文；非 ZIP 拒绝且不创建工程；重复或未知 ZIP 条目拒绝。工作区独立核心已由子任务落地（契约与测试源码，未构建、未接生产脚本/审批/存储适配器）；RGSS 核心/适配器已落盘并注册。完整 `.lg`/Pi、PDF、工作区生产接线和设备/视觉验收仍未完成；旧 LNT 数据迁移已移出当前范围。本机未构建、测试或 Lint，未提交/推送/触发 Actions。


## 71. 旧 LNT 数据迁移（已移出当前范围，2026-10-24）

用户明确决定：当前仍在开发阶段、尚未自用，不需要旧 LNT 数据迁移。R36 移出当前路线图；本轮新增的 `core/storage/.../storage/legacy/` 迁移核心与 Room v6 适配器、测试均已删除，不再作为交付项。旧数据/旧代码仍不主动删除；`.lg`/Pi 的 LG 兼容不在本次取消范围内，继续按 G3/R04 处理。

## 72. RGSS 核心与格式装配（2026-10-24，未提交源码）

RGSS 核心已在 `core/formats/.../formats/rgss/` 落地 6 个源码文件和 53 个未运行 JUnit 用例：RGSSAD v1（XP `.rgssad`/VX `.rgss2a`）与 v3（VX Ace `.rgss3a`）读写、v3 `78 9C` zlib 条目、Ruby Marshal 4.8（含对象 link、encoding ivar）、Scripts 编解码、Ruby 普通字符串字面量定位/写回、`BinaryFormatAdapter` 与目录/归档服务。严格拒绝损坏头/边界/目录穿越/重复路径/超预算，不确定 Ruby 结构整脚本跳过而非局部改错。

本轮已把 `RgssFormatAdapter` 注册进 `NativeFormatAdapters`，并把 `.rgssad/.rgss2a/.rgss3a` 与 `Scripts.rxdata/.rvdata/.rvdata2` 识别为 `RPGMAKER_RGSS`；其他 `.rxdata/.rvdata2` 仍为 `BINARY`。新增注册/识别测试源码。仍未抽取/写回 `Data/*.rxdata`、`Map*.rvdata2` 等非脚本数据，Adapter 只发警告；未接 SAF 目录选择器、UI 格式标签、真实 RPG Maker 运行时/设备验收。完整契约见 [RGSS_CODECS](RGSS_CODECS.md)。本机未构建、测试或 Lint，未提交/推送/触发 Actions。


## 76. 文档一致性修正（2026-10-24）

本轮仅修正文档：移除入口摘要中已取消的旧 LNT 迁移核心/待办及 R07/R08 的旧库样本前提；更新架构当前调用链与 Agent 契约消费者，将首轮限制明确标为历史记录；总表补记 §62/§63 全量批写和 §69 多协议模型发现的未构建源码，修正只读工具 §74 引用及 Room 2/3/4/5 schema 待导出清单。计划入口区分 `.lnt` v3 CI 基线与当前 v4 未构建源码。未修改产品代码、测试或历史开发轮的原始范围决定。

仅做静态文档/源码符号核对、本地 Markdown 文件链接、git diff --check 和冲突标记检查；静态审查，未实际构建，未运行测试或 Lint，未新增设备/视觉/供应商证据，未提交、推送或触发 Actions。已有 #146 CI 不覆盖当前未提交实现。


## 77. 取消外部工程与会话兼容（2026-10-24）

用户明确要求“不用考虑兼容，全部移除”。取消 `.lg` 导入/导出、LG 双向回灌、Zstd/schema 兼容夹具、Pi/Koog 历史映射与跨框架继续对话；PR10 标记取消，R04/G3 仅保留原生 `.lnt` 存储/版本升级、身份/修订、一致快照与恢复。同步根规则、入口、架构、任务依赖/风险/验收、功能矩阵、UI 和存储专题；历史轮次只保留当时事实，不再形成兼容待办。98 个历史 ID 保留追溯，PR10 与此前取消的 OP06 不计入当前 96 项有效范围。

静态搜索未发现生产 `.lg` 转换器、Pi 历史适配器或对应 UI 入口，无相应实现需要删除。LG 参照源码索引和模型协议实现保留；通用文件格式、模型协议、脚本能力的功能对齐继续，Koog 自身历史/检查点/恢复、原生 `.lnt` 升级和备份恢复不取消。

本轮仅修改文档与源码索引的范围标记及生成逻辑；静态审查，未实际构建，未运行测试/Lint、供应商或设备验证，未提交、推送或触发 Actions。


## 78. Agent 应用级前台执行与密钥池共享（2026-10-24，新增源码未构建）

目标保持 active：继续完成当前有效范围，直到只剩验证；本节是阶段进展，不关闭整体目标。远程 Agent 发送新增 `ForegroundAgentRunner`、`NativeAgentHost`、`NativeAgentService`：应用作用域持有执行，先同步预留真实 Agent 租约并持久登记，再等精确 token 的 Service 前台确认后调用既有 Koog/共享网关。Intent 只传 token；输入仅驻留有界进程请求对象，诊断字符串脱敏。启动失败/超时不发付费请求，迟到确认/停止不影响后续运行；等待审批先持久收尾并释放前台宿主，不靠长驻 Service 保持等待。

`NativeAppGraph` 持有共享流式预览和 Agent 执行器；ViewModel 只发起/观察/停止远程对话，销毁仅取消 waiter，不取消执行。初始化先获取运行 handle 再做可失败工程 IO；同进程新控制器附着原 token，已接受输入按工程/Chat/原文核对后清草稿，快速完成也核对接受状态，避免重建后重复发送。运行结束重新读取持久消息、审批、条目和执行记录；界面刷新失败保留停止关联。同工程导航使用统一前台执行状态，新增 Agent 运行通知提供停止入口，五语文案齐备。

Agent 与翻译后端改为共用一个 `ModelKeyPool`，同一配置修订的认证禁用和冷却不再因用途分裂。全部密钥禁用返回 AUTHENTICATION，仍有启用但冷却时返回 RATE_LIMIT；不在单次请求内重试。密钥状态与账本跨进程持久化仍未实现。

新增 5 项执行器与 8 项宿主回归源码，覆盖 UI waiter 取消后继续/同句柄重附着、命令载荷冲突、停止/启动确认、失败终态、审批等待、输入诊断脱敏、重复/迟到 Intent、通知失败、超时和清理；另补全部认证失败分类回归。均未运行。静态审查，未实际构建；未执行 Gradle/测试/Lint、设备、视觉或供应商调用，未提交、推送或触发 Actions。本轮期间外部更新了 HEAD，当前工作区/提交应以 Git 实查为准，不能从旧“未提交”历史描述推断新增 CI 覆盖。

仍需实现：Agent 输入队列/steer、分支/压缩/检查点和跨进程接续、完整工具与多对象审批、批翻接续/暂停队列、模型/账本持久化、工作区生产执行、复杂格式及完整 UI/退役等当前范围。短时本地查询/手工审批尚未迁成应用级长任务；进程死亡只做持久回执核对，不自动重发或冒充执行恢复。


## 79. 密钥轮换、禁用与冷却的持久化接线（2026-10-24，未构建）

新增 domain `ModelKeyStatePort` 和不含凭据的不可变状态，记录配置修订、轮换位置、enabled、连续失败、冷却截止和失败类别。`KeyPool` 可导出/恢复这些事实，恢复按不可变配置的有序凭据逐项匹配数量；`ModelKeyPool` 从存储加载状态，选定后先持久保存轮换位置再返回派发凭据，认证/冷却事实随成功/失败更新。保存失败时当前进程阻止该配置后续派发；已收到响应或原始供应商错误不会被存储错误覆盖，也不重试模型。坏文件、配置绑定错误或数量不符均拒绝，不回退到全启用池。

`NativeModelKeyStateStore` 由应用图注入共享密钥池，使用私有哈希文件名、严格 JSON/UTF-8/字段/整数与版本核对、128 KiB 单文件限制、AtomicFile 写入/备份恢复及进程共享 Mutex。最多 4096 个文件，不通过删除旧认证禁用事实腾空间；没有明文 Key/端点/模型正文，密钥仍由既有 Keystore 端口解密。该存储随应用进程重建读取，不承诺多进程并发写入或自动重新启用坏 Key；用户显式保存新配置修订使用独立状态。

新增 3 项引擎回归源码（重建恢复、坏状态拒绝、写失败前后行为）与 2 项真实文件存储回归源码（重开/修订隔离/哈希文件名、损坏/未知字段/非整数/越界拒绝），均未运行。静态审查，未实际构建；未执行测试/Lint、设备或供应商调用，未提交、推送或触发 Actions。用量账本持久化、动态限额、认证管理完整 UI 与任务/工作区/格式等目标继续开放，未达到只剩验证。


## 80. 用量账本的事务持久化与原生读取界面（2026-10-24，未构建）

新增 domain `UsageLedgerReadPort`、显式作用域 `UsageLedgerQuery` 与不可变 `DurableUsageSummary`。应用 Room 源码升至 v6，`MIGRATION_5_6` 新增无正文/端点/凭据的 `native_usage_receipts`，保留完整 v1→v6 升级链。Agent reserve/dispatch/complete 与账本准备/派发/响应在同一应用库事务完成；翻译 audit.dispatched/received 也在同事务登记。通道+attempt 身份隔离两类请求，原终局回执重放不重复计数，冲突使整事务回滚。已派发但缺回执独立统计为等待/未知，不记为零 Token 或零费用；已响应的五类用量继续按 ACTUAL/ESTIMATED/UNKNOWN 与 BigInteger 累计。解析失败但已有供应商用量的翻译响应仍入账。

`NativeUsageLedgerStore` 按全局、完整工程实例/代际、Chat、Run 或模型修订读取一致快照，每次最多加载 100 条轻量记录并检查取消；不加载消息正文，不默认选择第一个工程。Agent 缺本地 Chat/实例关联时只可全局查询，不猜测工程归属；生产会话按现有真实 Chat→实例关系绑定。旧版本缺失账本元数据的请求不推造历史，界面明确统计边界。

应用图改用该持久读取端口，不再给模型后端重复挂接进程内 `UsageLedgerRecorder`；独立核心仍保留供测试/其他明确消费者使用。顶部新增五语用量账本对话框，支持全部/本工程/当前会话和显式刷新，只在打开期间读取；错误显示读取失败，不伪造空账本，关闭取消读取，不触发模型。单次翻译运行的既有用量弹窗继续保留原语义。

新增 5 项真实 Room 回归源码：派发/缺回执/终局重放/重开/完整身份隔离，Agent 与翻译同 attempt 通道隔离及解析失败用量，多页 205 响应/Long 以上精确累计，v5→v6 保留旧请求不伪造账本，以及账本冲突回滚 canonical 响应。新增 2 项 Compose 用例覆盖作用域选择和读取失败重试。修正既有 v3 迁移夹具，移除较新模型列与账本表后才降版，避免旧夹具保留 v5 列导致迁移重复添加。以上均未运行；Room 2/3/4/5/6.json 仍须 PC/CI KSP 权威导出，不手写。

静态审查，未实际构建、测试或 Lint，未设备/视觉/供应商验证，未提交、推送或触发 Actions。费用定价、完整账本分页明细/导出、任务恢复/Agent队列与全工具/工作区/复杂格式/完整 UI 等当前范围继续实施，整体尚未达到只剩验证。


## 81. 固定翻译任务与提交边界的显式恢复（2026-10-24，未构建）

新增 domain TranslationTaskPort/Spec/Progress/Summary；应用 Room 源码升至 v7，新增 native_translation_tasks 与 native_translation_targets，保留 v1→v7 升级链。远程批翻在真实运行租约下固化原范围（页或整工程、启用资产、启动模式）及目标 ID/指纹，最多 100,000 条轻量元数据；原配置修订、提示指令、预算/去重/前文设置固定在私有任务记录，列表只查不含提示词的投影，正文不进入目标表。

TextTranslationService 在派发前记录 attempt/目标，在逐批写入前保存固定 commandId/item 对应关系；项目事务提交后在不可取消收尾中通过工程回执标记目标完成，并更新累计修订/计数。崩溃发生于提交与任务确认之间时，恢复按原回执核对，不重写已提交条目；RESET 也只处理原集合中未完成的条目。恢复保留逻辑 Run/command/configuration 身份，前台执行 token 则为新执行生成；只允许原载荷和修订推进，不允许借 continuation 更换模型/范围/指令。初始去重基线修订保留，避免恢复时把它误改成最新修订。

顶部新增五语任务列表（当前最近 20 条）与显式付费恢复确认，准备恢复不发请求，确认时再次核对固定规格、工程修订和真实模型配置，再通过既有前台 Service/共享网关执行。无审计行的未派发 attempt 可安全解除等待；已派发缺回执、有响应但提交正文已丢失、部分付费响应未提交、工程已变化等情况拒绝自动恢复/重发并提示核对。中途付费译文暂存与恢复、请求不确定状态的人工裁决、暂停/队列及完整任务分页管理仍待实施，本节不宣称已完成所有任务恢复。

新增 6 项存储/核心集成回归源码，覆盖提交后崩溃回执核对、RESET 保留已提交、未派发/已派发分流、工程/配置冲突、原 Run 端到端续跑、v6→v7 迁移及部分付费响应拒绝；另新增执行器 continuation token/载荷约束和 2 项 Compose 显式确认/不确定拒绝回归。修正旧版本迁移夹具以移除新增 v7 表；均未运行。

静态审查，未实际构建；模块/导入/分隔符/资源/文档链接、schema/entity 字段映射和 git diff --check/冲突检查已核对，未运行 Gradle/测试/Lint、设备/视觉/供应商验证，未提交、推送或触发 Actions。Room 2—7.json 均待授权 PC/CI KSP 导出；整体目标保持 active，账本费用、Agent 队列/全工具/多对象审批、工作区、格式、完整 UI 与退役等仍需继续实现。


## 82. 有效模型译文与最终提交暂存恢复（2026-10-24，未构建）

应用 Room 源码升至 v8，MIGRATION_7_8 新增 native_translation_request_inputs（原输入 ID/指纹）、native_translation_responses（协议已校验的条目译文）及 native_translation_staged（规则处理后的最终译文和稳定 commandId），保留 v1→v8 升级链。正文按条目分行、有界并按 attempt/task 延迟读取，轻量请求/任务列表不查这些正文；记录 toString 脱敏，表不含凭据或原稿。派发审计事务同时固化输入身份；模型成功响应通过 receivedResponse 与用量、账本在同一事务保存，两个模型协议路径共用这一入口。重复回执要求原内容一致；落盘失败抛 TranslationResponseNotStored，停止而非改成可重试内容失败。五语界面修正提示，收到响应后的存储错误不再声称“未发送请求”。

TextTranslationService 在任何项目提交前保存最终译文和稳定命令，逐批读取最多 20 条暂存、核对原指纹和修订，再经原 ProjectWriteStore 事务提交。项目回执确认与任务 done 标记、对应暂存移除同一应用库事务处理；进程死亡后，若原命令尚无回执但译文暂存完整，可用原命令执行；已有回执则核对并跳过。若只保留了模型归一化译文，恢复在当前未变化的工程策略下重新做本地规则处理和同资产纯文本去重派生映射，再暂存提交，不调用模型。已提交主条目与尚未提交派生条目的情况可使用已保存结果，不重翻 RESET 行。

旧版本或失败/截断请求没有完整有效输出时，不推造译文。分裂重试的未完成单元仍缺预算/队列完整检查点；当前对不能证明完整可恢复结果的状态保守拒绝。未派发 attempt 的解除还检查同一未完成目标是否已有先前请求及旧版缺身份元数据，避免恢复时默默重置已消耗重试预算。暂停/队列、请求不确定状态的人工裁决、完整任务分页和产物清理/导出仍待实施。

新增 6 项未运行回归源码：有效响应与用量原子重放/冲突拒绝，原始有效响应恢复只请求剩余新目标，提交前崩溃沿用暂存命令，30 条输出提交 20 条后恢复且无模型调用，v7→v8 保留原任务，以及输出保存失败只执行一次模型调用。旧版迁移夹具同步移除新增 v8 表。静态结构/导入/分隔符/资源/文档与 schema 字段核对、git diff --check 和冲突扫描已检查；静态审查，未实际构建，未运行测试/Lint、设备/视觉或供应商验证，未提交、推送或触发 Actions。Room 2—8.json 仍须授权 PC/CI KSP 导出。整体目标保持 active，未达到只剩验证。


## 83. 翻译暂停屏障、界面与通知接线（2026-10-24，未构建）

运行协调器增加仅供翻译使用的 PAUSING 屏障，拒绝新模型/工具操作，允许已开始请求的持久回执及项目提交收尾；快照单独统计模型操作。前台执行器只有在远程任务已经固化并发布进度后才接受精确 token 的暂停；等待调度且尚无模型操作时取消等待，已有模型操作则等待响应保存后退出。暂停产生独立 PAUSED 运行终态，界面与通知提供五语暂停入口/等待提示，保留强制停止入口。续跑继续使用任务列表的显式确认，不自动重发。应用 Room 无新表或版本变化，仍为 v8。

修复 NativeModelGateway 把 TranslationResponseNotStored 包装成通用模型失败的问题：保留原存储失败类型，避免上层误判为可重试内容错误。暂停不掩盖真实供应商或存储失败；进程死亡仍按中断核对，不能保证已派发但无回执请求可恢复。

新增 4 项未运行回归源码，覆盖模型操作计数/暂停屏障/允许提交/强制停止、Agent 不可暂停、等待进行中响应后持久 PAUSED，以及无模型操作时取消等待。静态结构检查与 git diff --check 通过，未实际构建、测试或 Lint；未提交、推送、触发 Actions 或调用供应商。完整队列、重试预算持久化、暂停与停止竞争的设备验证及其余有效范围继续开放，整体目标未完成。


## 84. 重复暂停续跑与暂停收尾失败修正（2026-10-24，未构建）

修复同一固定任务首次续跑后再次暂停、工程修订未前进时，显式 continuation 因请求对象相等而返回已结束句柄的问题。已结束的暂停/取消/失败执行允许在原固定载荷核对通过后生成新 token；运行中的重复准备仍附着当前句柄，已成功请求仍不重放。旧 token 不能暂停新执行。

暂停日志终态写入失败不再被原取消异常遮蔽，执行器返回失败并释放租约；持久记录仍由既有中断核对处理，不声称已可靠暂停。新增 4 项未运行回归源码：同修订重复续跑、暂停终态存储失败、暂停中强制停止、暂停中供应商失败。静态结构和差异检查通过，未实际构建、测试或 Lint；未提交、推送或触发 Actions。整体目标继续进行，队列与重试预算完整恢复等实施项仍开放。


## 85. 翻译任务历史分页与加载重试（2026-10-24，未构建）

解除任务列表只能访问最近 20 条的限制。新增不可变 TranslationTaskPage 与按工程完整身份绑定的分页端口；存储用 SQLite rowid 降序游标，每页查询 21 条轻量投影、返回 20 条及下一游标，计数在同一读取事务完成。翻页期间新插入的任务不会把已读取任务推到下一页形成重复或遗漏；重新打开列表可看到新任务。未新增表或 schema 版本，应用 Room 仍为 v8。

原生任务列表接入加载更早任务、失败重试及五语文案，保留已加载任务和游标、禁止并发加载、按 Run/command 去重，不读提示词或译文正文，不触发模型请求。旧 recent 端口仍返回首屏，生产界面改走分页端口。

新增 2 项未运行回归源码：45 条任务分页期间插入新任务仍覆盖原 45 条、实例/代际隔离/游标校验；界面第二页失败后重试且保留第一页。静态结构、导入/资源/文档与 git diff --check 检查通过，未实际构建、运行测试或 Lint；未提交、推送、触发 Actions。重试恢复需持久保存原始规划单元、剩余预算及拆分队列，目前仍未实现，不能按请求总数重新初始化预算；完整任务队列及其余当前范围继续开放。


## 86. 批翻失败分支保留暂停期间的供应商失败（2026-10-24，未构建）

继续追踪执行器→TextTranslationService→模型网关发现：执行器本身已保留暂停期间的供应商失败，但服务在预算耗尽/永久失败处理及允许重试的运行屏障检查处，会把原失败替换成 RuntimeUnavailable(STOPPING)，使执行器误记 PAUSED。两处现在在同一 Run 的 PAUSING 阶段保留原 AgentModelFailure；停止状态仍走停止边界，不继续请求或标记条目失败。

新增服务/真实工程存储/持久运行日志的回归源码，覆盖追加预算 0 和 2 两种分支：供应商先请求暂停再返回网络失败，原错误传回、日志 FAILED、仅一次请求、修订未变化、租约释放。该用例补足此前仅对 ForegroundTranslationRunner 假执行回调的覆盖，未运行。静态结构与 git diff --check 检查通过，未实际构建、测试或 Lint，未提交、推送或触发 Actions。完整重试队列/预算检查点与其他有效范围仍需实现，整体目标继续进行。


## 87. 原始批次重试状态机与恢复快照（2026-10-24，未构建）

TextTranslationService 的运行期重试队列改用 TranslationRetrySession，原始规划单元拥有唯一追加预算，拆分子批次共用该预算；替换批次先扣预算后进入等待/派发。不可变 TranslationRetryCheckpoint 保留原目标身份/指纹、初始与剩余额度、拆分队列/重试次数/深度、当前 attempt、终止失败及退避截止时间，不包含原稿/译文/凭据。restore 不重新初始化预算，不允许未核对的 active attempt 经 next/begin 再派发；当前仍需存储适配器和真实回执恢复接线，不能宣称跨进程预算/队列持久化已完成，也未修改 Room v8。

同时修复派发 hook 之前的调度/配置拒绝：立即传播原失败，不虚记 REQUEST_FAILED、不增加请求计数、不尝试预算重试，避免持久运行日志要求先有 REQUESTING 而覆盖原错误。运行期成功提交后才移除活动块，失败后的队列和额度在同一快照中更新。

新增 5 项状态机测试源码覆盖拆分共享预算/恢复、退避与已消耗额度、未核对 active attempt 禁止重派、快照成员/指纹/重复校验、成功清空；新增真实服务/存储集成源码覆盖 hook 前认证拒绝仅一次调用、无请求计数/错误检查点。均未运行。静态结构/边界/资源与 git diff --check 检查通过；未实际构建、测试或 Lint，未提交、推送或触发 Actions。下一步接入该快照的持久保存、成功/失败/未派发回执核对以及恢复时原规划单元重建；完整任务队列和其他当前范围继续开放。


## 88. 重试快照私有存储适配器（2026-10-24，未构建，尚未生产装配）

将 TranslationRetryCheckpoint/Target/Pending 移入 domain，新增 TranslationRetryCheckpointPort；engine 状态机继续消费同一契约，storage 无新增 engine 依赖。新增 NativeTranslationRetryCheckpointStore，使用私有 native_translation_retries 目录、完整 Project/instance/generation/Run/command 哈希文件名、模型配置绑定、真实翻译租约核对、进程共享 Mutex 与 AtomicFile；限制 128 KiB/文件及 4096 文件。严格 UTF-8、重复键/深度/字段/整数/枚举/范围校验，损坏或绑定不符不视为缺失。文件不含正文或凭据。相同原始单元禁止补回额度，未完成单元禁止被新单元覆盖，已完成单元不能重新填充队列；无静默淘汰或清理历史事实。

新增 3 项未运行真实文件存储测试源码：重开保留拆分额度/队列/活动请求且不存正文，拒绝补回预算/替换未完成单元，错误配置/小数/坏 UTF-8/失效租约拒绝。存储尚未注入生产翻译链路：必须先实现原任务/请求回执与队列同步核对，才允许恢复活动块或继续新规划；当前不可宣称跨进程重试恢复可用。Room 仍为 v8，无新 schema。

静态结构/模块边界、符号、git diff --check 与冲突扫描通过；静态审查，未实际构建，未运行测试或 Lint，未提交、推送或触发 Actions。整体目标保持进行中，下一步是生产保存与恢复核对接线，而后继续完整队列及其余当前范围。


## 89. 重试快照生产保存与可证明边界恢复（2026-10-24，未构建）

NativeAppGraph 为真实 TextTranslationService 注入 NativeTranslationRetryCheckpointStore。原始单元准备、派发 hook 前记录 active attempt、失败决策扣额度后和提交成功后均保存快照；成功/失败收尾保存置于 NonCancellable，保存失败阻止后续派发。任务服务恢复先提交已有译文，再读取快照；仅当原任务回执证明全部活动主条目已完成时移除 active，不能把无回执 active 当成未发送请求重派。

非活动 pending 快照按原队列/指纹/次数/预算重建，在当前页中从新规划输入排除原单元目标，保证不为同一单元重新初始化额度；若原队列成员跨页/缺失或指纹变化则拒绝。恢复保留退避截止时间并分段等待，系统时钟回拨不会通过固定一次延迟绕过截止时间。当前仍受任务预检的保守约束：失败/未派发活动 attempt 的核对、拆分后尚无完整译文的兄弟队列与任务状态同步还未接通，不宣称全部预算/队列接续完成。

新增未运行服务/真实文件存储回归源码：按预先保存的原 pending 单元和已耗尽额度续跑，后续网络失败仅调用一次、不补回预算且终止失败事实落盘。此用例检查服务如何消费已保存状态，不证明所有崩溃点的任务预检已覆盖。静态模块/符号/分隔符/资源/文档与 git diff --check、冲突检查通过，未实际构建、运行测试或 Lint；未提交、推送、触发 Actions。应用 Room 仍为 v8；完整目标继续进行。


## 90. 已确认失败后的拆批队列任务预检接线（2026-10-24，未构建）

NativeTranslationTaskStore 在独占工程写预留期间读取私有重试快照，将原本一律拒绝的 REQUESTING/RESPONSE_RECORDED 状态细分核对：快照无 active/终止失败、有 pending，原追加预算匹配、队列身份等于全部尚待响应目标、原指纹和未完成标记一致，且最新请求属于同一工程/Run/模型修订并已 RECEIVED。最新请求为成功响应时，还须证明全部输入均已由任务回执完成；失败回执则保留原拆分队列。满足后只清理任务等待元数据，不重置快照或额度。全 Run 的 DISPATCHED 未回执检查继续阻止恢复。

新增 2 项未运行真实工程/请求审计/文件快照/服务集成源码：已持久 PARSING 失败的三条父批次按原 2+1 子队列恢复、仅请求两个孩子且预算保持零；伪造可继续队列但父请求仍 DISPATCHED 时拒绝。原去重派生目标尚不进入本次放行条件，保持保守拒绝，完整去重/活动未派发 attempt/失败决策前崩溃核对仍开放；不冒充全崩溃点恢复已完成。

静态结构/模块依赖/资源/符号与 git diff --check、冲突扫描通过；静态审查，未实际构建，未运行测试或 Lint，未提交、推送或触发 Actions。应用 Room 仍为 v8，整体目标继续进行。


## 91. 派发审计前活动块的原队列恢复（2026-10-24，未构建）

任务预检在独占工程写预留下核对 READY/REQUESTING 的重试快照：活动 attempt 没有派发审计、队列身份/原指纹/未完成标记与任务一致、REQUESTING 的当前 attempt/目标匹配且无去重派生时，允许将活动块放回原队列头。初始与剩余额度、块的 attempts/splitDepth 和等待截止时间保留，不重置预算；原审计存在时不按未派发处理。完整 Run 的未回执审计门槛继续保留。

新增受独占预留约束的快照写入口，复用原子写入和单调预算校验。先保存回队事实再清理任务等待元数据；如果两步之间进程死亡，下一次可识别原队列头与未派发任务身份并幂等清理，不重复插入活动块。新增 2 项未运行回归源码：READY/REQUESTING 两个派发前中断点保留已消耗额度/块次数，连续预检不重复入队；已有 DISPATCHED 审计的活动块保持原 attempt 并拒绝续跑。

静态结构/依赖边界/符号与 git diff --check、冲突扫描通过；静态审查，未实际构建，未运行测试或 Lint，未提交、推送、触发 Actions。Room 仍为 v8。去重派生同步、已回执失败但决策尚未落盘等恢复与全功能范围继续开放，整体目标保持进行中。


## 92. 失败回执已保存而重试决策未保存的恢复（2026-10-24，未构建）

新增 domain TranslationRetryRecoveryPolicy 与 engine DefaultTranslationRetryRecoveryPolicy，复用运行期 TranslationRetrySession/Planner 的身份、顺序、计数和额度决策，不加载正文或调用模型。生产任务存储注入该纯策略；在独占预检中只处理同一 active attempt 已 RECEIVED 且有明确失败类别、输入身份/指纹/配置和全部未完成目标均匹配的情况，重建并原子保存失败决策，然后走既有 pending 队列核对。决策保存后再次中断不会再次扣额度，已耗尽或永久失败保留终止事实并拒绝自动续跑。

新增 2 项未运行回归源码：所有失败类别和 0—5 追加额度下，恢复与运行期的队列/额度/终止/退避事实一致；真实请求审计先收到 PARSING 失败、决策未写时，预算 2 恢复原 2+1 拆分且连续预检不重复扣费额度，预算 0 保留原失败并拒绝重试。此处只重建本地重试决策，不发生付费派发。

静态结构/模块边界/符号及 git diff --check、冲突扫描通过；静态审查，未实际构建、测试或 Lint；未提交、推送、触发 Actions。Room 仍为 v8。去重派生条目的队列同步、失败标记的完整恢复及其余有效范围继续开放，整体目标未完成。


## 93. 子批次已提交但重试快照未确认的兄弟队列恢复（2026-10-24，未构建）

任务预检增加提交回执核对后的重试活动块结算：仅当同一 Run/模型的成功响应已 RECEIVED、输入身份/原指纹对应活动块、原任务目标全部 done 时，移除快照 active，保留原 pending 兄弟队列和剩余额度。先执行工程命令回执 settle，再执行该结算与 pending 核对，避免把已提交子批次的响应强行用于尚无译文的兄弟目标。跨文件/数据库的两阶段确认可重复预检，不重放已经提交的条目。

新增 2 项未运行真实工程/审计/快照/服务集成源码：父批失败后 2+1 拆分，第一个孩子已提交，分别在 task.committed 前和 retry.succeeded 保存前中断；恢复核对原提交，只派发最后一个孩子，保留前两条译文、累计三次请求和零剩余追加额度。去重派生目标仍沿原保守边界，不纳入本节放行条件。

静态结构/符号/依赖边界及 git diff --check、冲突扫描通过；静态审查，未实际构建、测试或 Lint；未提交、推送、触发 Actions。Room 仍为 v8，去重派生同步、失败标记恢复与其他当前范围继续实施，整体目标未完成。


## 94. 去重主条目与派生条目的重试预检同步（2026-10-24，未构建）

用有界原条目映射核对替代四类重试预检对 deduplicateSameFile 的整类拒绝：原 pending 主条目全部包含于任务等待集合，全部目标未完成且保持原指纹，额外派生条目必须与恰好一个默认元数据主条目具有相同资产和原文。跨资产、非默认格式、缺失或歧义映射不自动派发。重建失败决策、未派发回队及 pending 恢复共用该核对。已提交活动块的清理额外检查是否仍有其派生条目未提交，有则保留原活动事实，不提前处理兄弟队列。

新增 3 项未运行真实工程/审计/文件快照/服务回归源码：六条原文按三个主条目去重，父批失败后原 2+1 主队列完成六条；第一个子批及其四条目标提交后中断，恢复只请求最后一个主条目并提交其派生条目；任务等待集合混入无法映射的第三种原文时拒绝。零剩余追加额度保持，未重翻已提交主条目或派生条目。

静态结构/依赖边界/符号与 git diff --check、冲突扫描通过；静态审查，未实际构建、测试或 Lint，未提交、推送或触发 Actions。Room 仍为 v8。一个子批内部部分暂存/派生条目提交而兄弟批次尚未响应的全崩溃点恢复、失败标记恢复及其他当前范围仍开放，整体目标继续进行。


## 95. 子批内部部分派生提交与兄弟队列的分离恢复（2026-10-24，未构建）

恢复核对新增活动子批/未派发兄弟的完整互斥分区：原主条目身份/指纹与快照一致，全部剩余条目保持原指纹；派生条目必须唯一映射至同资产、同原文、默认元数据主条目，队列主条目不得缺失。只有完整说明所有剩余目标时，允许活动子批暂存集合小于整体等待集合；暂存集合必须精确覆盖该子批尚未提交的全部目标，不能借此跳过缺失译文。

原始有效响应恢复也只返回活动子批对应的尚未提交目标，保留兄弟等待集合；服务先把已有有效译文本地处理/暂存/逐批提交，确认原活动块完成后再沿原预算派发兄弟。readCoordinated 为真实运行或独占预检提供只读快照访问，继续保持生产写入口的租约/独占预留校验。

新增 3 项未运行真实工程/审计/文件快照/服务回归源码：三个主条目对应 31 条原文，父批失败拆分后第一子批对应 30 条，分别仅原始响应保存、提交 20 条但任务未确认、确认 20 条后三个中断点恢复；请求兄弟前断言 30 条均已提交，仅最后一个主条目发生新请求，总提交 31 条/总请求 3 次。没有重新翻译已保存译文。静态结构/边界/符号及 git diff --check、冲突扫描通过，未实际构建、测试或 Lint；未提交、推送或触发 Actions。Room 仍为 v8，失败标记恢复、完整任务队列和其他当前范围仍需继续实现，整体目标未完成。


## 96. 错误标记提交不能转成翻译成功（2026-10-24，未构建）

发现终止失败的 ERROR 标记已提交后，任务可能成为 READY 且 remaining=0；原服务在所有活动主条目 done 时会清空包括 terminalFailure 在内的快照，从而把错误标记当成完整翻译成功。现在服务读取终止失败即传播原失败类别，不清除该事实；预检在提交回执核对后保留终止失败、将任务标为 FAILED 并拒绝续跑。新增 failed 执行钩子，运行期终止失败处理完成后也记录 FAILED，不把标记计入成功译文数。

任务列表新增五语失败终止说明，FAILED 不显示付费续跑入口；用户需要重新发起翻译，按新确认范围选择失败/剩余条目。已有审计、失败类型、原 command 与 ERROR 事实保留，不重写译文或回退修订。新增 2 项存储回归源码覆盖错误标记提交后未确认/已确认两个中断点：原回执结算、成功数仍零、状态 FAILED、失败快照保留；另增 Compose 失败说明已加载后无续跑/付费确认操作的回归源码，均未运行。

静态结构/模块边界/资源/符号及 git diff --check、冲突检查通过；静态审查，未实际构建、测试或 Lint；未提交、推送或触发 Actions。Room 仍为 v8，标记提交前或多批标记中的完整接续、完整任务队列及其他当前范围仍开放，整体目标继续进行。


## 97. 终止失败事实不可清除与未回执核对状态（2026-10-24，未构建）

快照存储额外保护 terminalFailure：只有原目标、预算、队列、活动 attempt、失败类别和退避信息完全相同的重放可以写入，不能清空失败、换失败类别、伪装成功空队列或重新回队。该约束位于生产存储，避免仅依赖服务调用顺序维持终态。

任务 failed 收尾检查全 Run 未回执派发数；有 DISPATCHED 时使用 NEEDS_REVIEW 并保留原审计/计数，不按普通 FAILED 处理。任务列表显示已有五语核对说明并禁止直接付费续跑；不存在未回执时保持明确失败。项目提交中的状态仍不能由 failed 钩子覆盖。

新增 2 项未运行回归源码：终止失败精确重放可接受，清空/修改/回队拒绝；未回执任务重复收尾仍 NEEDS_REVIEW，预检拒绝且原 DISPATCHED 行保留。静态结构/模块边界/符号及 git diff --check、冲突扫描通过；未实际构建、测试或 Lint，未提交、推送、触发 Actions。Room 仍为 v8。人工请求裁决、多批错误标记恢复、完整任务队列及其他当前范围仍待实施，整体目标继续进行。


## 98. 当前入口与恢复契约状态同步（2026-10-24）

按 §78—97 源码重新同步 §3 总表、§4 后续顺序及恢复/调度专题的当前边界：共享 Key 状态和账本已持久化，翻译已有暂停、任务分页、固定队列与预算快照及指定回执边界的显式恢复；这些不再列为“完全未实现”。仍保留完整排队、人工裁决、多批错误标记接续、Agent 跨进程能力、工作区/格式/完整 UI 和全量验收等原范围，没有因已写源码缩减目标。

历史开发轮记录保留当时事实。当前源码阶段与旧 CI 基线分开，未新增构建/测试/设备证据。静态结构/本地文档引用、git diff --check 和冲突检查通过；未实际构建、测试或 Lint，未提交、推送或触发 Actions，整体目标继续进行。


## 99. 用量账本请求明细与分页原生入口（2026-10-24，未构建）

新增 domain UsageLedgerEntry/Page/Cursor 与三态请求状态；明细保留请求/Run/模型修订、可确认的本地工程身份、通道及供应商用量/失败事实，不含正文/端点/凭据。未回执 usage 为空，不伪造零或成本。游标绑定完整 UsageLedgerQuery，跨范围复用拒绝。

NativeUsageLedgerStore 以 rowid 降序每页查 51 条/返回 50 条及下一游标，复用汇总的完整工程实例/代际/Chat/Run/模型修订过滤，每页同事务；翻页期间新插入请求不会推移旧页，已显示回执更新需显式刷新。无新表或版本变更，Room 仍为 v8。账本汇总新增明细入口，原生明细 LazyColumn 显示准备/派发待回执/响应、模型/通道及五类实际/估算/未知用量，支持更早记录、失败重试保留已加载内容、显式刷新；五语文案齐备，不调用模型。

新增 2 项未运行回归源码：105 条轻量请求分页中插入新请求仍覆盖原集合、范围/游标隔离、保留失败响应与未知用量；界面翻页失败保留第一屏并按原游标重试。静态模块/结构/资源/符号及 git diff --check、冲突检查通过；未实际构建、测试或 Lint，未提交、推送、触发 Actions。费用定价/账本导出、人工裁决、多批错误标记/任务队列、Agent/工作区/格式及其他当前范围继续开放，整体目标未完成。


## 100. 账本 JSON Lines 导出与本轮停止交接（2026-10-24，未构建）

UsageLedgerReadPort 新增导出契约，NativeUsageLedgerStore 在一致读取事务中按每批最多 50 条流式输出 JSON Lines；首行保存格式/版本/精确查询作用域，每个请求按明确字段白名单输出通道、身份、配置修订、模型、状态、回执及五类三态用量，末行 complete/entries 表示导出结束。不读取正文或凭据，不把缺回执/未知用量写成零，不估算账单。写入或取消失败传播，不显示导出成功；即使 writer 刷新失败也关闭目标流。

账本汇总新增五语导出按钮，经 Android SAF CreateDocument 保存 .jsonl；打开系统选择器前把全部/工程/Chat 范围绑定进 SavedState，迟到结果不读取新选择范围，工程身份变化时拒绝写入。运行期忙碌禁用导出，取消选择器清除挂起记录。导出是本地只读，不调用供应商或修改原账本。

新增 3 项未运行回归源码：三个请求状态的作用域导出/未知值/完整结束标记/不含私密消息，输出失败关闭流且保留原回执，以及界面导出回调绑定所选会话。静态结构/模块边界/资源/符号与 git diff --check、冲突扫描通过；未实际构建、测试或 Lint，未提交、推送或触发 Actions，Room 仍为 v8。

用户要求“将这一轮执行完毕后停止，并汇报做了哪些，还剩哪些”。本轮到此收拢，停止后续实现并按要求暂停持续目标。目标尚未达成；剩余费用定价、人工请求裁决、多批错误标记接续/完整任务排队、Agent 输入队列/分支压缩/检查点与全工具审批、真实脚本工作区/技能联网、复杂格式/PDF 与全 UI/旧链路退役，以及本批 CI/schema/真机/视觉/供应商验收。已取消的旧 LNT 迁移和 .lg/Pi 不重新加入范围。


## 101. 审查确认问题修复（2026-10-24，未构建）

密钥池读取/恢复持久状态失败统一转为 UNKNOWN，保留取消异常并继续拒绝使用新鲜密钥回退，避免本地状态损坏误入 PARSING 后把条目标为 ERROR。更新损坏状态回归断言，增加 IO/字段异常、密钥数量不匹配与取消传播用例源码。Agent 页打开校对快捷按钮统一使用 foregroundExecutionActive。账本导出准备在忙碌/作用域不符时设置明确错误，拒绝后关闭账本弹窗以显示提示；不启动文件选择器。运行历史新增五语暂停短标签，原暂停说明保留。

静态审查，未实际构建、测试或 Lint；新增/更新测试尚待 CI/PC 执行。未提交、推送或触发 Actions。


## 102. LG 规则 JSON 交换与系统文件入口（2026-10-24，未构建）

用户授权继续完成全部剩余修改，直至只剩验收；目标保持全部当前有效范围，不按本切片缩减。先核对干净工作区、当前 `77daf74` 与 Actions #158，并更新 §3 的 CI 覆盖说明。

新增 LgRuleJsonCodec，独立读取 LG 外部质量规则数组、字符串键值表、RPG 角色名称/昵称映射，并按明确选择的规则类别转换。稳定 ID 由匹配语义派生；对唯一现有匹配绑定原 ID 和启停状态，复用原冲突预览/覆盖选择，保留提示词。错类型、重复键/身份、超额、无效字符或不能表达的规则拒绝；不静默修复或部分导入。导出仅当前类别五个外部字段，拒绝把禁用规则静默变成启用。JSON 已接规则页预览/草稿/保存链路，另接系统 OpenDocument 读取和 CreateDocument 保存、严格有界 UTF-8、取消传播、IO Dispatcher 与五语说明。文件导入只装载文本，仍须主动预览与应用；导出冻结选择器打开前的内容。导入弹窗绑定草稿指纹，草稿改变会重建预览，避免旧预览覆盖新内容。

新增四项 domain 回归及一项 Compose 回归源码，覆盖字段往返、唯一身份覆盖/禁用状态/提示词、角色映射、类型/重复/预算拒绝与仅改草稿不隐式保存，均未运行。本切片尚不支持 XLSX 规则交换、CSV、LG 的宽容 JSON 修复或非字符串键值转换；没有以局部格式支持标记全功能验收。

静态审查，未实际构建、测试或 Lint；新增源码不继承 #158。未提交、推送或触发 Actions。下一步继续规则 XLSX/CSV 与完整规则管理，并推进任务人工裁决/排队、Agent/工作区、模型/格式/UI 等既定剩余实现，整体目标保持 active。


## 103. 规则 CSV/XLSX 交换与文件交付（2026-10-24，未构建）

继续用户全部剩余实现目标。新增 RuleCsvCodec：明确 src/dst/info/regex/case_sensitive 五列表头，支持引号/逗号/换行/转义引号，保留字符串内容，严格拒绝坏列数/引号/布尔值及超额记录；CSV 是明确的原生交换契约，不冒充 LG 当前源码已有 CSV 文件标准。复用 LG JSON 转换的稳定身份、已有规则绑定、冲突预览及规则限制。

新增 LgRuleXlsxCodec：复用既有 XLSX 包定位和富文本读取，读取首工作表前五列，支持 shared/inline 字符串与布尔单元格，并沿 LG 表格读取的 trim 行为处理字段；拒绝公式、重复坐标/行、无效索引/标志。解压前独立扫描限制 2 MiB 输入/总展开、512 KiB 单成员、128 成员，再交既有路径/重复成员/安全 XML 容器解析；这些是未实机测量的规则文件预算。输出标准 OOXML 包、首表 rules 和五列表头，字符串使用 inlineStr，不写公式或外链；XML 无法表示的字符拒绝而非丢失。XlsxFormatAdapter 的包与字符串辅助方法仅扩大为模块内 internal，共享读取逻辑，没有改变旧资产导入/写回行为。

规则页接入 CSV/XLSX 格式选择和对应系统 MIME/扩展名。XLSX 文件装载只转换有界文本、不在 UI 状态保存二进制；切换到/离开 XLSX 清除旧装载数据，仍须主动预览和应用。三种导出均在打开选择器前冻结当前类别与外部规则内容，返回后 IO 线程生成/写入对应格式；不读取后来选中的类别或草稿。所有外部导入继续保留提示词，原生保存入口提交工程修订。外部无 ID 规则遇到多个同源现有身份时明确拒绝，不任意匹配一个。

新增四项 CSV domain、四项 XLSX formats 回归源码，另补一项模糊身份拒绝和一项 Compose CSV 导入回归源码，均未运行。覆盖完整字段往返、引号/换行、已有身份、共享字符串与布尔、公式/重复/索引、展开预算、禁用规则导出拒绝、只改草稿不隐式保存。JSON/XLSX 仍不支持宽容坏 JSON 自动修复/非字符串键值转换；大文件/所有 Excel 变体/设备 SAF 和 UI 视觉验收仍缺。

本轮静态审查，未实际构建、测试或 Lint；新增代码不继承 #158。未提交、推送或触发 Actions。整体目标 active，规则系统预置/完整模板与校对、翻译人工裁决/任务队列、完整 Agent/脚本工作区/联网、模型管理、复杂格式与 UI 退役等剩余实现继续开放。


## 104. 模板库文件交换与删除确认修复（2026-10-24，未构建）

继续全部剩余实现目标。核对模板库现有源码，启停、顺序和语言元数据编辑已存在，不再把它们整体登记为零进度；完整 LG 模板语言/系统预置仍未完成。发现确认删除时先清空 deleting 再读取它，导致真实用户删除误走覆盖分支，已改为先冻结删除决策。新增 Compose 回归源码断言删除只移除所选模板，保留另一模板内容且不使用当前工程编辑器正文覆盖它。

新增 PromptTemplateImportPreview/previewImport：默认合并仅添加新 ID，相同 ID 内容不同报告冲突并禁止确认；用户可明确选择更新同 ID，或替换整个库。预览冻结原库指纹、完整目标库和新增/更新/未变/删除/冲突明细；内容含正文、描述、启停、顺序与语言限制。替换缺失模板和空库清空均显式计数，不在解析时写入。新增系统 JSON 文件导入界面、严格有界 UTF-8 解码、逐项修改前/后查看，确认才经现有 NativePromptTemplateStore.replace 指纹 CAS 原子保存；并发库修改时拒绝旧预览。工程提示词不被库交换隐式修改。

模板库新增系统 CreateDocument 导出，冻结打开选择器前的库快照，IO 线程生成和保存原生 JSON；不将完整库写入 SavedState。旋转/进程重建导致冻结快照丢失时，收到文件结果明确报错并拒绝改用后来加载的库。库导入预览为临时状态，重建需重新选择文件，不自动恢复授权。五语资源覆盖全部新文案。

新增五项 domain 回归、一项真实文件库 CAS/重开回归和一项 Compose 删除回归源码（均未运行），覆盖默认冲突、更新与未提及模板保留、替换/空库清空、顺序/语言/启停往返、库容量上限、并发修改拒绝和删除真实分支。静态审查，未实际构建、测试或 Lint；本轮源码不继承 #158。未提交、推送或触发 Actions。

整体目标保持 active，尚非只剩验收。翻译任务人工裁决/完整排队与失败标记恢复、Agent 队列/分支/压缩/接续/完整工具审批、真实脚本工作区/技能/联网、模型与费用、系统预置/完整模板与校对、复杂格式/PDF、完整 UI 与旧链路退役等实现继续开放；验收也仍需 CI/schema/真机/视觉/真实供应商证据。


## 105. 模板自身正文编辑、触屏排序与草稿保护（2026-10-24，未构建）

继续全部剩余实现目标。所选模板现在有独立正文编辑和保存入口，保存正文/名称/描述/启停/顺序/语言元数据时使用所选模板草稿，不再只能经“用当前工程编辑器正文覆盖模板”来保存元数据；原来的覆盖当前正文入口保留明确确认。使用模板可把已校验的本地编辑草稿交回渲染编辑器，仍不隐式写库/工程或调用模型。新增未保存说明，切换所选模板/关闭库前需明确丢弃；有未保存修改时阻止导入和直接重载覆盖草稿。

新增 PromptTemplate.withOrder 与 PromptTemplateSet.reordered，完整 ID 排列校验后按新顺序编号，保持全部正文/元数据/启停/语言及稳定身份；库 UI 提供上移/下移触屏入口。排序经原指纹 CAS 保存，未保存的正文修改继续留在草稿，不被排序顺手提交。首次/末尾操作禁用。保存正文后刷新编辑基线和元数据；库重载清理旧选择，避免把已失效选择显示为可保存。

所选模板原始快照（单模板、正文上限 16 KiB）、编辑正文和库基线指纹以完整工程身份绑定 SavedState。界面重建重新读取库，原指纹变化时禁用保存/使用/排序/删除，保留草稿可查看；重新选择该模板需显式丢弃有修改的草稿，不能以新库指纹自动授权旧草稿覆盖。导出仍冻结已保存的完整库，不把未保存草稿混入文件。五语提示齐备。

新增两项 domain 排序回归及四项 Compose 回归源码（未运行），覆盖相同顺序值的稳定移动/正文与语言保留、错误排列拒绝、独立保存所选正文与启停、排序不提交正文草稿、切换显式丢弃、SavedState 恢复遇新库时拒绝旧草稿写入。静态 XML/资源引用、符号与调用点、diff 空白/冲突扫描核对；静态审查，未实际构建、测试或 Lint。未提交、推送或触发 Actions。本轮不继承 #158，也不把回归源码当作已运行或真机恢复证据。

整体目标 active，尚未达到只剩验收；完整 LG 模板语言/系统预置及此前记录的翻译任务、Agent、真实脚本工作区/联网、模型费用、校对/复杂格式/PDF、完整 UI 与旧链路退役等仍需继续实现，CI/schema/设备/视觉/供应商验收仍开放。


## 106. 全查询/质量筛选字面译文替换（2026-10-24，未构建）

继续全部剩余实现目标，推进校对台全查询查找替换。新增 QueryManualBatchPort.prepareReplacementBatch：在同一工程修订的读取事务内遍历当前查询全部条目，或核对质量索引的固定成员、原查询、指纹与过滤条件；只冻结实际变化条目，按稳定 ID 排序并受工程条目/单条长度预算约束。大小写策略、查找和替换文本属于不可变批次内容。保留原文和条目状态，不将排除/错误条目顺手改为已处理。

原 TextLiteralReplacer 的 RE2/J 字面、非重叠、无捕获展开算法移入 domain 的 LiteralTextReplacement，保留 4,000,000 步/取消检查和 65,536 字符输出预算；engine 原入口委托共享核心，storage 不依赖 engine。ManualTextBatch 增加显式 REPLACE_TRANSLATION 与必需替换参数，v2 摘要绑定全部参数/成员/身份/修订；普通状态批次保留原 v1 摘要。参数被改动会生成不同子命令，不能借旧回执重放新替换。状态批次入口拒绝没有替换配置的替换请求。

提交继续走唯一 ProjectWriteStore.editBatch，在写租约和一个 SQLite 事务内先核对全部原指纹，再重新计算各条已绑定的替换、记录修订与回执；任一取消/长度失败回滚整批。重放返回原终局事实，不再次执行替换，不新增工程 schema/Room 版本。普通批次逻辑保持。大选择继续写 app 私有预览文件，SavedState 只携摘要；编码兼容旧 v1，同时新增 v2 替换字段并严格校验形状/布尔值，恢复后仍需显式确认。

校对查询菜单接全结果替换，参数弹窗固定完整工程实例/代际、修订、原查询与质量选择；VM 拒绝参数填写期间已变化的范围。准备与最终确认分开，确认展示实际参数、大小写策略和固定受影响行。提交后从原显示窗口重新读取；读取失败时清空旧译文列表并提示刷新失败，不用旧正文伪装替换结果。全部新增文案覆盖五语，无模型调用。

新增一项 domain、四项真实工程存储、一项批次恢复和两项 Compose 回归源码（均未运行）：105 条跨页全查询、查询状态/文本过滤、原状态保留、原文不变、字面美元/反斜线、不重放覆盖后来修改、参数篡改拒绝、首条写入后取消的全事务回滚、固定质量成员/过期索引拒绝、v2 原样恢复与错类型拒绝、先准备后确认。既有 engine 字面替换测试将经委托覆盖共享核心。静态 XML/资源/调用点、diff 空白与冲突核对；静态审查，未实际构建、测试或 Lint。未提交、推送或触发 Actions，本轮不继承 #158。

当前是完整查询范围的字面译文替换，正则/姓名等 LG 全查找替换分支仍需继续补齐，不把该切片标为整个校对台验收完成。整体目标 active；完整 Agent、任务人工裁决/排队/失败标记恢复、真实工作区脚本/技能/联网、模型费用、系统预置/完整模板、复杂格式/PDF、完整 UI/旧链路退役和全量验收等此前剩余范围继续开放。


## 107. 校对正则模式与 JavaScript 替换字符串（2026-10-24，未构建）

继续全部剩余实现目标。只读核对 LG proofreading-service.ts / shared/text/text-pattern.ts：校对正则替换显式使用 JavaScript replacement string，与规则文件的 backslash 语法不同。TextReplacementOptions 新增默认 false 的 regex，预先编译并共享不可变 RE2/J Pattern；字面模式依旧不展开美元或反斜线。正则模式经现有 TextRuleValidation 编译/程序预算，未支持的模式明确拒绝，不转字面或忽略。

新增独立 JavascriptReplacement，逐次按原始输入和当前匹配展开 $$、$&、前缀/后缀、$1—$99（含两位回退与前导零）、$<name>；未参与捕获为空，无命名组时命名占位保持字面，反斜线保持原字符。每次输出走统一长度预算，展开时响应取消；零宽匹配依 RE2/J 的有限 find 推进，预览计数上限允许输入长度+1。共享预览/事务提交使用同一核心，规则流水线仍使用原反斜线语法。

单条草稿替换与全查询替换均新增正则开关、语法说明和不合法模式提示，最终确认显示实际模式。正则参数绑定 ManualTextBatch v3 摘要和私有预览 v3 编码，恢复重新校验模式；旧 v1 状态批次和 v2 字面批次的摘要/编码不变。切换模式不能复用原回执；仍经完整成员冻结/指纹/修订和 ProjectWriteStore 原子提交。无新增数据库版本或付费请求。新增四项 domain、真实工程存储/原生快照、批次恢复和 Compose 模式门禁回归源码共七项，另扩展摘要模式绑定断言，均未运行。

静态 XML/五语资源引用/调用点/差异空白与冲突核对；静态审查，未实际构建、测试或 Lint。未提交、推送或触发 Actions，不继承 #158。本轮使用 RE2/J 正则语言，不具备 JavaScript lookaround/模式反向引用等完整兼容；姓名译文替换尚未接入，不登记完整 LG 查找替换验收。整体目标 active，继续全范围剩余实现和最终验收，不能把这些明确缺口当成仅待设备确认。


## 108. 姓名写回链路核对与用户要求暂停（2026-10-24）

用户在本轮核对期间要求“完成这一轮后，暂停”。本轮收拢为姓名链路的静态核对与交接，不启动跨存储版本的大批改造，保留 §102—107 全部未提交修改。姓名编辑/替换尚未实现，不能把本节当成功能完成或仅待验收。

已确认实际链路：TextItemMetadata.nameTranslation 已存入 items；TextProjectFingerprint.item 已把姓名元数据纳入条目指纹。ProjectFormatService.formatUnit 把它传给格式单元，MessageJsonFormatAdapter 在 writeTranslatedNames 开启时读取并写回姓名，RenpyFormatAdapter 也有消费。缺口在统一写入口：ManualTextEdit/其命令指纹只携译文与状态，ProjectWriteStore.editBound 没有姓名更新载荷，修订记录也不保存逐版本姓名事实。NativeImportValidator.validateItemRevisions 当前用条目的当前元数据重建历史指纹；若直接修改当前姓名，会让旧修订的重建不再可靠。因此不能只改 UI 或更新 items.name_translation。

恢复后应完成一个连贯切片：为显式姓名修改增加不可变/有界载荷和命令摘要；设计原生 .lnt 的版本升级与逐修订姓名快照（独立于应用 Room），同时处理旧修订、已归档条目和导入验证；让单条/批量更新共用 ProjectWriteStore 和原子回执；再把查找替换目标扩展到译文/姓名/两者，冻结到预览摘要与私有恢复格式；补真实格式导出往返、快照重导入、旧版本升级、篡改/回滚/重放与界面用例。具体 schema 尚未实施，本轮没有更改版本号、用户数据或任何姓名字段。

本轮只更新本交接，完成 diff 空白、冲突与工作区核对；静态审查，未实际构建、测试或 Lint，未提交、推送或触发 Actions。用户要求暂停持续目标，后续实现到此停止。全部目标未达成：除姓名和完整正则兼容外，Agent、任务裁决/排队/恢复、真实脚本工作区/联网、模型费用、系统预置/完整模板、复杂格式/PDF、完整 UI/旧链路退役，以及 CI/schema/真机/视觉/供应商验收仍按前述记录开放。


## 109. 按代码实际同步文档（2026-10-24）

用户要求先根据代码实际同步文档，本轮只修改说明，不修改产品源码、测试、数据库或用户数据。

已按实际代码重新核对：当前 HEAD 为 `77daf74`，Actions #158 / run 40085555783 为 test/lint/release/签名成功基线；工作区另有 §102—107 的代码/测试增量（涉及 27 个修改和 13 个新增文件），对应规则 LG JSON/CSV/XLSX 交换、模板库导入导出/编辑排序、全查询字面替换、正则/JavaScript 替换，未提交、未构建；本轮文档同步另修改了根与计划文档。原生 `.lnt` 当前为 v4（`NativeProjectFormat.SCHEMA_VERSION = 4`），应用 Room 为 v8（`NativeAppDatabase` 保留 v1→v8 迁移链），`core/storage/schemas/.../NativeAppDatabase/` 仍只有 `1.json`。`NativePage` 仍只有 AGENT/WORKBENCH/PROOFREADING/RULES 四页；`app/src/test/screenshots/` 只有 `greeting.png`，测试源码中的 12 处 `captureRoboImage(filePath = ...)` 产物尚未入仓复核。`ModelProtocolTransport` 明确只做非流式；`core/workspace` 仍只有契约/编排核心与文件适配器，没有真实脚本引擎。`ManualTextEdit` 仍只携译文/状态，姓名写回缺口与 §108 一致。

本轮同步了根 `AGENTS.md`、根 `README.md`、计划入口 `README.md`、`core/storage/schemas/README.md`、本记录 §3/§4，以及 ARCHITECTURE、NATIVE_STORAGE、RULES_AND_PROMPTS、REMOTE_TRANSLATION、MODEL_SCHEDULING、MODEL_ENDPOINTS、RUN_RECOVERY、TXT_TRANSLATION、UI_SPEC、FOREGROUND_TRANSLATION、AGENT_CONVERSATION、AGENT_CONTRACT、APPROVAL_CONTRACT、S2_AGENT_SLICE、PROOFREADING_QUERY、MANUAL_BATCH、QUALITY_SCAN、TEXT_QUALITY、EDITOR_TOOLS、STATIC_REVIEW、RGSS_CODECS 的当前状态段落。历史开发轮的“未构建/未运行”保留为当时事实，不再作为当前结论；§102—107 统一标为工作区未提交/未构建，§51—101 标为由 #158 覆盖。

静态验证：`git diff --check`、冲突标记扫描、文档链接/符号/版本号与资源引用核对通过。未实际构建、测试、Lint、录制截图或设备验收；未提交、推送或触发 Actions。整体目标仍未完成：§102—107 待 CI，姓名写回、Room 2—8 schema、设备/视觉/供应商验收，以及翻译任务裁决/队列、Agent 队列/检查点、真实工作区、PDF/复杂格式、完整 UI 与旧链路退役继续开放。


## 110. 姓名写回、Agent 队列持久化、任务裁决与费用核心（2026-10-24，未构建）

用户要求持续推进剩余待实施业务、允许并行执行，直到只剩验证。本轮在 §102—109 工作区之上新增以下未提交增量，未修改用户数据，未提交、推送或触发 Actions。

- **姓名写回**：`.lnt` 从 v4 升至 v5，`item_revisions` 增加 `name_source`/`name_translation` 快照；v1—v4 可在姓名写事务前原子升级，旧修订以当前姓名回填。新增 `ManualNameEdit`/`ProjectNamePort`，命令摘要使用独立 `name-edit-v1` 编码，旧 `ManualTextEdit` v1 摘要不变；`ProjectWriteStore.editName` 在同一事务内核对命令/修订/指纹、更新条目姓名、重算完整 metadata 指纹、增加修订并写回执。`NativeImportValidator` 使用修订姓名快照重建历史指纹，active/archived 均覆盖。原生编辑器新增“编辑姓名”入口和五语姓名对话框；姓名保存是独立命令，不覆盖正文草稿。
- **Agent 输入队列/steer**：新增纯核心 `AgentInputQueue`/`AgentSteer` 和 Room v9 持久化（`native_agent_input_queues`、`native_agent_input_drafts`、`native_agent_steer_runs`、`native_agent_steer_attempts`）。队列只消费队首、历史回执确认后才移除、旧 attempt/revision 不清新草稿；steer 仅允许在 `TOOL_LOOP_BOUNDARY` 注入，停止/结束取消未注入项。Agent 页接“暂存输入”“使用/删除/暂停/发送下一条”；发送前持久化 pending，接受后使用真实 USER 消息 ID 确认，失败/启动失败释放 pending；成功后可自动继续下一条（暂停即停止）。steer 工具循环接线仍未完成。
- **翻译任务裁决**：新增 `TranslationTaskQueue` 纯状态机，覆盖 QUEUED/RUNNING/PAUSED/NEEDS_REVIEW/FAILED/COMPLETED/COMPLETED_WITH_ERRORS/CANCELLED、同工程串行、跨工程排队、失败批次隔离、重试/跳过/保留错误/终止、缺失回执与未知用量拒绝完成。尚未接 Room/UI/真实任务执行。
- **模型费用**：新增 `ModelPricing`/`PriceTable`/`UsageCost`/`UsageCostCalculator`；整数微单位 + BigDecimal 12 位小数，actual/estimated 分离，未知与缺价显式，不伪造零费用。尚未接模型配置、账本 UI 或真实供应商价格。
- **RGSS 数据文件**：新增 `RgssDataStrings`，对 `Data/*.rxdata/.rvdata/.rvdata2` 的 Marshal 字符串做白名单扫描、稳定节点定位与写回；`RubyMarshal` 增加 `u` opaque 节点支持，脚本与数据可在归档内共存；`ProjectAssetFormat.detect` 识别 `Data/` 下数据文件。真实 RPG Maker 工程/设备往返和目录选择 UI 仍缺。
- **项目集备份**：新增 `NativeProjectSetBackupService`，把全部可读工程逐个保存为 `.lnt` 快照并写入严格 manifest，恢复为新的本地实例；工作台已接“备份全部工程/恢复全部工程”。它仍不含应用设置、凭据、Agent 会话或模板/预设库，不冒充完整应用级备份。
- **测试源码**：新增姓名命令/存储/导入、Agent 队列/steer 及 Room v9、翻译任务裁决、模型费用、RGSS 数据 codec/adapter、格式检测等测试源码；均未运行。

静态验证：`git diff --check`、冲突标记扫描、`tools/rebuild/check_structure.py`（模块边界、导入、Kotlin 分隔符、文档链接与资源引用）通过。未实际构建、测试、Lint、Room KSP、截图或设备验收；未提交、推送或触发 Actions。当前 `.lnt` v5、Room v9、`2—9.json` 均未由 CI/PC 生成。整体目标未完成：翻译任务持久化/UI、Agent 自动续跑与 steer 接线、姓名批量/双目标替换、全应用备份、设置/日志、PDF/复杂格式、真实工作区/技能/联网和旧链路退役继续开放。

## 111. 审查修复：项目集备份与 Agent 队列（2026-10-24，未构建）

按用户“修复”处理四条审查意见：移除不存在的 `ProjectSetEntry.entry` 引用，改在 manifest 解码时校验规范路径与有界整数索引，并核对 ZIP 实际路径；全量备份查询 101 条作为 100 工程预算的溢出探针，超限抛 LIMIT_EXCEEDED，在打开目标输出前拒绝，不再静默截断。列表端口与生产实现同步支持 101 条，默认首页仍读取 100 条。

队列成功完成后以 ViewModel 协程等待当前 action 清除 busy，再核对工程/会话、审批、暂停、pending、队列读取完整性和后台状态后发送下一条。初始化重附着根据持久 pending 的 RunIdentity 和文本绑定选择队列观察/真实消息回执结算。界面观察取消只在 runner 已终止时释放未接受 pending；后台仍 active 时保留 pending 和 token，供新界面重附着。结算前等待旧观察结束，避免观察与 finally 并发写同一队列。

新增两项备份回归源码：路径篡改在导入前拒绝；超过 100 项在打开输出前拒绝，并检查生产列表支持 101 条。未运行这些测试；队列续发、发送前重附着及界面销毁/后台终止竞态仍需 CI/PC 与设备验证。

验证：静态结构脚本通过，git diff --check 通过，冲突标记扫描无匹配，已核对工作区状态并保留既有改动。静态审查，未实际构建、测试或 Lint；未提交、推送或触发 Actions。


## 112. 用户模型单价与账本费用接线（2026-10-24，未构建）

继续执行“实施修改，直到只剩检验和验收”。当前仍有实质实现缺口，目标保持进行中，不以本轮切片代替完整功能。

- 新增 NativePriceTableStore：私有 AtomicFile 保存严格价格表，IO dispatcher 读写，进程内共享锁、指纹 CAS 和递增版本保护并发编辑；有界读取/严格 UTF-8，损坏文件不降级为空价格，已有价格不允许悄悄换币种。
- 应用图/ViewModel 接读写端口；用量账本新增模型单价入口，五语表单编辑模型与五项每百万 Token 单价，严格十进制与范围验证、禁止缺项隐式免费，保存不发模型请求。
- 账本明细按请求模型与用量计算当前价格下的费用，实际/估算独立展示，标注价格表版本、缺价和未知用量。页面明确这是按当前用户价格重算，不是供应商账单；原始用量回执不变。尚未实现历史请求固定价格快照、多模型汇总及价格删除/交换。
- 修正 §3 中 Agent 自动续发仍记为未实现的过时描述：实际已有 §111 接线，待竞态/设备验证；steer 实际注入仍未完成。
- 新增存储回归源码：重开、过时编辑、币种冲突、保留其他模型、版本递增，以及损坏表拒绝覆盖；未执行。

静态结构脚本、git diff --check 通过；冲突标记扫描无匹配。静态审查，未实际构建、测试、Lint、截图或设备验证。未提交、推送、触发 Actions。其余任务裁决持久化/执行、Agent steer/分支/压缩/恢复、工作区脚本/技能/联网、PDF/格式分支及完整 UI/系统交付继续待实施。


## 113. 翻译任务核对与显式终止裁决（2026-10-24，未构建）

- 沿用已有固定目标/响应/暂存任务库，不另建不一致的任务执行事实。新增 TranslationTaskReviewPort 及不可变元数据快照；完整任务身份、状态、恢复字段和计数均纳入摘要，UI 不加载正文。
- NativeTranslationTaskStore 在独占 project-write 预留下核对任务，新增显式终止；Room 从 v9 升至 v10，MIGRATION_9_10 增加裁决表及索引，裁决回执与 CANCELLED 在同一事务保存。同 command/摘要重放幂等，不同参数拒绝；状态变化后过时预览拒绝。
- 原生任务历史接核对、显示未知响应数、确认终止与终态标记。终止保留既有译文、固定目标、请求/响应及暂存，不清除未知付费事实；终止任务仍可只读核对，禁止再次继续。
- 新增回归源码验证未知派发证据保留、重开后幂等、终止后不可恢复、伪造/过时预览拒绝及活动执行互斥，均未运行。
- 全任务排队/人工裁决还未完成：逐批成员重试/跳过/保留错误、执行器队列、完整恢复继续待实施，本节只关闭任务级终止入口缺口。

静态结构脚本、git diff --check 和冲突标记扫描通过；未实际构建、测试、Lint、KSP 或设备验收。Room 2—10.json 仍待授权 CI/PC 生成，#158 仅覆盖到 v8。未提交、推送或触发 Actions，目标保持进行中。


## 114. 错误条目选择与独立固定任务重试（2026-10-24，未构建）

- 新增 TranslationTaskRetryPort 与轻量成员分页快照；每页 20 个固定目标，只返回条目身份、当前状态和指纹，不把正文载入任务界面。原生任务核对可翻页选择当前 ERROR 条目。
- NativeTranslationTaskStore.retryErrors 在运行协调器独占写预留下重核对原任务快照、当前工程修订、目标成员、当前指纹/ERROR 状态与资产启用状态。存在任何未收到确定响应的派发时拒绝重试，不把未知副作用当失败。
- 显式重试建立独立 Run/命令、从 0 开始的请求预算和只包含所选条目的固定目标；原任务、旧重试预算/检查点、成功条目和全部请求证据保留。原 Room v10 裁决表记录 RETRY_ERRORS 与新任务关联，裁决/新任务/固定目标在一个事务保存；相同命令和输入幂等，不同选择冲突。
- 准备只落库，不发请求；界面随后显示模型/目标语言确认，通过原有前台翻译执行器和统一网关执行。关闭确认可从任务历史显式继续；不会自动执行准备好的新任务。
- 新增存储回归源码：仅选中错误成员进入新任务、原任务仍 FAILED、新请求预算不继承、重复准备幂等、非 ERROR 拒绝，以及未知派发拒绝且不创建任务；未运行。

实现边界：本节是显式新任务重试，不是原 Run 预算重置或自动重试。逐批跳过/保留错误、完整任务队列派发及原任务内多批裁决仍待实施，未将纯 TranslationTaskQueue 状态机宣称为生产执行器。当前目标继续进行中。

验证：静态结构脚本和 git diff --check 通过，冲突标记扫描无匹配；静态审查，未实际构建、测试、Lint、设备验收。未提交、推送或触发 Actions。


## 115. 逐条保留错误裁决与任务终态守卫（2026-10-24，未构建）

- 复核 §114 后修复底层恢复旁路：NativeTranslationTaskStore.open 对 COMPLETE/CANCELLED 先拒绝，再判断暂存响应是否可恢复，终止任务不能因保留的暂存数据被重新打开执行。
- Room v11 / MIGRATION_10_11 增加 native_translation_member_decisions，按 task/itemId 主键保证原任务成员只有一次最终裁决。§114 新任务重试同事务写入所选成员决策；已经重试或保留错误的成员不能再次被另一个命令裁决。
- 新增 keepErrors：绑定任务核对快照、当前工程修订、错误条目状态/指纹和选定成员，持久保存 KEEP_ERROR 与逐条证据。保留错误不修改工程、不抹除未知请求、不把任务标为成功、不发新请求；命令回放幂等。
- 成员分页显示已裁决状态并禁止重复选择；原生界面接保留选中错误，即使存在未知派发也可保留错误，但仍禁止重试。任务级终止仍是独立操作。
- 新增回归源码：重开后保留错误及幂等、禁止重开成员裁决、终止且有暂存响应时拒绝底层恢复；均未运行。

静态结构脚本、git diff --check、冲突标记扫描通过；静态审查，未实际构建、测试、Lint、KSP、截图或设备验证。Room 2—11.json 待 CI/PC 权威生成；未提交、推送或触发 Actions。逐批跳过/完整任务队列及 Agent/工作区/格式等剩余实现继续开放。


## 116. Agent 执行中 steer 的工具循环接线（2026-10-24，未构建）

- 新增 AgentSteerController，应用图装配唯一进程实例，串行管理真实 RunLease 与 Room 中的 steer ledger。受理/阶段推进/注入先持久化，既有 ledger 不自动恢复或重放；命令重复受理幂等。
- NativeKoogConversation 通过 domain 的 AgentSteerExecutionPort 接入，不改变 agent 模块依赖方向。模型请求与工具执行显式推进阶段；完整工具结果成对写入之后、下一次请求之前才注入引导。引导作为当前 Run 的临时系统上下文，不写入 Chat 用户消息或下一轮输入队列；共用原模型网关和请求边界，累计正文预算包含引导。
- 前台 Agent 执行开始创建 ledger，正常终结/等待审批取消尚未注入内容，停止/失败持久化对应终态。RuntimeCoordinator 新增仅限真实 AGENT 租约的 metadata-write 预留，允许停止阶段保存执行元数据；已有业务写入口仍执行原有运行阶段检查。
- Agent 页运行中允许编辑输入并提交“引导当前执行”，提交失败保留文本；只有持久受理成功且输入/工程/Chat 仍匹配时清空。提交只受理引导，不独立发请求；没有下一个工具循环时取消未注入内容。
- 新增控制器回归源码：受理先落库、重复命令、仅一次边界注入、停止后拒绝注入与取消状态落库；未运行。

剩余：跨进程 steer 显式恢复/取消内容展示、完整时间线状态、真实 Koog/供应商工具循环与设备竞态仍待补；本节不代表分支/压缩/检查点和完整 Agent 恢复已经完成。

验证：静态结构脚本、git diff --check、冲突标记扫描通过；静态审查，未实际构建、测试、Lint、截图或设备验收。未提交、推送或触发 Actions，目标保持进行中。


## 117. steer 历史分页与未注入内容恢复草稿（2026-10-24，未构建）

- NativeAgentInputStore 增加按完整工程实例/代际/Chat 隔离的运行元数据分页。列表不读取引导正文；以 RunId/RunCommandId 复合游标分页，避免既有 ledger 删除重插造成 rowid 游标漂移。打开单次运行后才加载其有界尝试明细。
- Agent 页新增五语引导历史对话框，展示最后持久阶段与受理/注入/拒绝/取消状态、引导文本，按需刷新。明确“已注入”只证明进入后续请求上下文，不证明模型完成执行；中断运行的最后记录不能冒充实时状态。
- 用户可把 CANCELLED 或失去活动运行的 ACCEPTED 内容恢复为空输入草稿。ViewModel 重读持久记录，核对 Project/Chat/Run/状态，拒绝恢复当前活动 Run 的内容；IO 后再次核对当前身份和空草稿，避免覆盖新输入。不修改旧 ledger，不自动发送或重新注入，不重放旧命令。
- 新增存储分页回归源码：25 个运行、更新已分页的 ledger 后继续读取无重复/遗漏、Chat 隔离与重开状态保留；未运行。

仍需实现：跨进程完整 Agent 执行接续、分支/压缩/检查点、全工具/审批与工作区等；本节的文本找回不等于恢复原执行。UI/IME/大文本/返回交互、恢复竞态与供应商流程待设备/CI 验证。

验证：静态结构脚本、git diff --check、冲突标记扫描通过；静态审查，未实际构建、测试、Lint、截图或设备验收。无 schema 版本变更，未提交、推送或触发 Actions；目标保持进行中。


## 118. 手动上下文摘要与模型历史切点（2026-10-24，未构建）

- AgentConversationPort 分离 modelHistory 默认入口；NativeAgentStore 实现持久摘要 + 当前上下文后缀，Koog 发请求及取消后的成对工具核对改用该入口。原 history 与消息行不删除、不重写，用量/审批和已提交工程事实不受摘要影响。
- Room v12 / MIGRATION_11_12 新增 native_agent_contexts，保存摘要、边界消息 ID/时间、前置会话摘要指纹及版本。当前窗口保持有界；追加预算改按窗口计数/字节，避免历史累计超过 256 条后仍永久阻止压缩后的对话继续。
- AgentContextCut 校验完整工具历史，只在用户轮次边界裁剪，并保留最近两个用户轮次。手动准备绑定窗口消息身份/内容与既有摘要；保存独占运行预留重核对快照，拒绝会话新增消息、审批等待或已有摘要变化导致的过时操作。
- 原生 Agent 页接五语手动摘要编辑、替换/保留消息数和明确说明。用户填写包含既有必要信息的摘要；本操作不调用模型，不自动删除历史，也不恢复旧写入授权。
- 新增回归源码：工具调用/结果不拆分、保存只影响模型上下文、重开保留、旧预览拒绝、累计 280 条历史不删除且模型上下文仍有界；未运行。

实现边界：已接手动摘要，不等于模型自动压缩。自动压缩策略/付费确认、完整历史分页、分支/编辑、检查点和跨进程执行恢复仍待实施。摘要语义由用户负责，真实供应商/长对话/IME/设备交互未验。

验证：静态结构脚本、git diff --check、冲突标记扫描通过；静态审查，未实际构建、测试、Lint、KSP、截图或设备验收。Room 2—12.json 待 PC/授权 CI 权威导出；未提交、推送或触发 Actions。目标保持进行中。


## 119. 完整 Agent 历史分页与轻量查询（2026-10-24，未构建）

- NativeAgentStore 增加完整历史轻量分页：按完整本地工程实例/代际/Chat 查询、createdAt/消息 ID 复合游标，单页 30 条，并在每页内保持时间正序；跨工程/Chat 游标拒绝。SQL 仅取角色、身份、短预览与排序键，不查询完整 payload。
- 原生 Agent 新增五语完整历史对话框：较早/较新/刷新、稳定唯一 key、单消息按需详情，复用正文/思考/工具详情组件。摘要切点不影响公开历史查询，历史查看不改变模型上下文、用量、审批和运行状态。
- 实时列表仍保留最近 50 条，改为 SQL 短预览投影；追加消息定位时间改用 MAX(createdAt) 标量查询，避免仅为列表或时间戳读取最近 50 条完整 payload。
- 新增存储回归源码：65 条同时间消息按复合键无重复/遗漏、翻页中追加新消息不干扰旧页、跨会话游标拒绝、预览 2000 字且详情保留完整正文、刷新看到最新消息；未运行。

剩余：历史编辑/分支、自动模型压缩、检查点/恢复等仍待实施；完整历史的手机/平板、长正文、IME、返回与可访问性需要真实 UI 验证。

验证：静态结构脚本、git diff --check、冲突标记扫描通过；静态审查，未实际构建、测试、Lint、截图或设备验收。无 schema 变更，未提交、推送或触发 Actions，目标保持进行中。


## 120. 从历史建立分支与纯文本编辑（2026-10-24，未构建）

- 新增 AgentBranchHistory/AgentBranchPreview，验证完整历史前缀和工具结果配对。纯文本 User/Assistant 可以在新分支中编辑，含图片/思考/工具的消息不提供无损性不明的文本编辑；可在完整工具结果之后原样建立分支。
- NativeAgentStore.prepareBranch/createBranch 使用运行协调器独占写预留，摘要绑定工程实例/代际、会话、选定消息、消息前缀、最新消息时间与上下文版本。创建前重核对，活动执行/待处理审批/过时快照拒绝。复制前缀使用新消息身份与严格递增时间，防相同原时间的消息被随机新 ID 重排。
- Room v13 / MIGRATION_12_13 增加分支命令回执。新 Chat、消息复制与回执同一事务提交，重复命令幂等，不同参数冲突。旧会话和工程事实不删除；不复制审批授权、队列、Run、请求、账本或执行状态，不发请求。
- 完整历史页新增五语分支入口、复制条数、可选纯文本编辑及确认；原生 ViewModel 拒绝非空输入草稿时切换，成功后读取新会话。
- 新增回归源码：编辑后的分支保留原消息、命令幂等、独立 Chat/消息身份、无新审批/工程写入、未配对工具拒绝、过时快照拒绝；均未运行。

边界：当前复制前缀限制 256 条/2 MiB，继承完整前缀而非旧摘要。超限分支明确拒绝，尚需扩展长历史共享/归档分支与上下文继承；不据此宣称全部 AG07 完成。原地历史编辑/分支管理、自动压缩、检查点和跨进程恢复等继续开放。

验证：静态结构脚本、git diff --check、冲突标记扫描通过；静态审查，未实际构建、测试、Lint、KSP、截图或设备验收。Room 2—13.json 待 PC/授权 CI 导出；未提交、推送或触发 Actions，目标保持进行中。


## 121. 长历史分支的流式复制与摘要继承；按用户要求暂停（2026-10-24，未构建）

- 分支准备与复制改为每次最多 8 条消息的小页扫描，帧长编码的增量 SHA-256 绑定前缀与摘要状态；不再把完整历史正文装入一个 List/JSON。每页/每条检查协程取消，创建仍由 Room 单事务回滚/提交。
- AgentHistoryValidator 提供与原 AgentHistory.validate 一致的增量校验，跨页保留工具配对、重复消息/调用身份和角色顺序检查；原 validate 委托同一实现，避免两套规则漂移。
- 分支公开历史可复制至选定消息的全部前缀；若选定点严格位于父会话摘要切点之后，继承摘要并把边界映射到新 Chat 的消息身份/时间，保持模型上下文有界。若从摘要覆盖的旧消息处分支/编辑，不继承可能失效的旧摘要。
- 当前静态硬边界为最多 10000 条/32 MiB 历史前缀、模型后缀最多 256 条/2 MiB；这是未测量的保护预算，不是设备性能结论。没有合适摘要的超大旧切点仍拒绝，需要后续补历史切点摘要/归档分支能力，不能宣称全长历史分支已验收。
- 新增回归源码：280 条历史完整复制、继承后模型窗口仍为摘要加最近两轮、边界身份归属新 Chat、原历史保留、编辑摘要覆盖的旧消息不继承旧摘要；未运行。

本轮验证：静态结构脚本、git diff --check、冲突标记扫描通过；已核对工作区状态并保留所有既有改动。静态审查，未实际构建、测试、Lint、KSP、截图或设备验收；无 schema 版本变化，未提交、推送或触发 Actions。

用户在本轮明确要求“本轮修改完成后，停止任务”。完成本节源码/回归源码/文档与静态检查后暂停，不再展开新功能。整体目标尚未完成：当前大量未提交增量不继承 #158，自动压缩、完整分支/检查点/跨进程恢复、任务跳过与队列、真实工作区/脚本/技能/联网、PDF/格式分支及完整 UI/备份/旧链路退役继续开放。恢复工作须先重新核对实际工作区、本节及 §3；先处理未构建增量和已有安全边界，不把本节保护预算当作最终范围缩减。


## 122. 功能收口批次：持久任务队列、自动压缩、PDF、诊断/应用备份、技能与 More 页（2026-10-25，已通过 CI）

用户要求持续推进剩余业务、允许非冲突子任务并行，直到只剩验证。本轮在 §102—121 工作区之上完成以下增量，已提交为 `b47178a` 并由 Actions run 40132451221 全绿验证。

### 本轮落地

- **翻译任务队列生产接线**：新增 `NativeTranslationQueueStore`（每工程一个 AtomicFile，严格版本/UTF-8/路径/字节/指纹/CAS/tail-loss 校验，损坏文件不被当成空队列）与 `TranslationQueueCoordinator`（跨工程单一 RUNNING 槽、工程槽、FIFO、命令幂等、暂停/取消/裁决、`TranslationQueueExecutionPort`、`resumeQueue`）。原生 ViewModel 在远程翻译确认后提供“加入队列”，复用既有前台执行器顺序执行，按任务存储的 `COMPLETE/FAILED/NEEDS_REVIEW/CANCELLED` 结算队列事实；队列对话框展示任务状态、序号、范围，并提供暂停后续跑/取消入口。仍缺跨进程调度、完整成员跳过/多批裁决 UI 与跨工程自动续排。
- **Agent 自动上下文压缩**：新增 `AgentCompactionPolicy/Planner/Service/Prompt`、`AgentSummaryPort`、`AgentAutoContextPort` 与门禁；`NativeAgentStore` 实现自动摘要准备/CAS 保存，复用 `native_agent_contexts`，不新增 Room 版本。`NativeKoogConversation.summarize` 使用既有 `AgentRequestBoundary` 记录摘要请求/回执/用量；前台 Agent 回合在发送用户消息前自动压缩，摘要失败只记录诊断并保留旧上下文。尚缺用户开关、自动压缩提示/确认、真实供应商与长对话验收。
- **PDF**：新增 `PdfBox-Android` 依赖、`PdfBoxRuntime` 初始化、`PdfFormatAdapter` 与页决策/布局/边界/异常核心；注册到 `NativeFormatAdapters`，`TranslatorApplication` 启动时初始化。读取按页提取文本，写入支持 keep/omit/translate、双语追加、页面尺寸/旋转保留、Unicode 字体覆盖检查，失败用类型化异常。仍未实现页决策 UI、原版式/图形保留、扫描件 OCR 策略、文档大纲/表单/XMP 迁移；CJK 字体依赖设备系统字体或后续 `installFont`。
- **诊断日志与完整应用备份**：新增 `NativeDiagnosticsStore`（JSONL 轮转、脱敏、游标分页、详情、导出、清空）与 More 页日志对话框；新增 `NativeApplicationBackupService`（版本化 ZIP、manifest/摘要/路径/预算、staging 恢复）及 app 端口 `NativeAppBackupDataPort`，备份模型端点（不含 Key/OAuth）、价格表、规则预设、提示模板，恢复后要求重新填写凭据。工程实例/审批/队列/诊断本身不在完整备份内；队列文件与应用级恢复仍开放。
- **技能库**：新增 `FileWorkspaceSkillStore` 与技能目录/正文/元数据 CAS、启用、排序、删除；原生 More 页新增只读内置 + 用户技能管理对话框。技能执行/人格/自动选择/权限仍在 Agent 工作区接线中。
- **More 页与外观**：新增 `NativePage.MORE`（底部导航仍四页，RULES 从 More 进入）、模型/规则/工程设置/存储统计/通知/诊断/应用备份/技能入口、应用版本、支持格式列表；新增应用级主题 `SYSTEM/LIGHT/DARK` 与 `NativeAppSettingsStore`。设置仍只覆盖外观，语言/请求/实验参数未建立生效链路。
- **Web Fetch 基础**：新增 `NativeWorkspaceFetchAdapter`，实现 HTTP/S 公网地址与 DNS 校验（拒绝回环/私网/链路本地/CGNAT/唯一本地 IPv6）、手动重定向逐跳校验、媒体类型/大小/超时/取消/Retry-After 分类；尚未接入 Agent 工具/搜索/MCP。
- **依赖**：version catalog 增加 `quickjs-android 0.9.2`（workspace）与 `pdfbox-android 2.0.27.0`（formats）；新增 AAR 是否可在 CI/设备解析与 native ABI 仍需构建验证。

### 验证与边界

- Actions run 40132451221 的 test/lint/release 与签名校验全部成功；该 run 还暴露并修复了 domain 可变决策表、queue snapshot 类型、RGSS bare-data 路径、storage 备份类重名、诊断协程临界区、SQLite 预算比较、JUnit 返回值/测试夹具和 Compose 懒列表滚动等问题。
- 本地仅执行 `python tools/rebuild/check_structure.py`、`git diff --check` 与冲突标记扫描；本机不运行 Gradle/测试/Lint。
- 没有新增 Room schema 版本；仍为 `.lnt` v5 / Room v13，`2.json`—`13.json` 未生成，`.lnt`/Room 升级未设备验证。
- 已提交并推送到 `refactor/translation-agent-2026-10` 并触发 Actions；#158 仅覆盖到 §101。

### 仍开放

- `QuickJsWorkspaceScriptEngine`/脚本目录/材料桥与隔离 Service 尚未接入 Agent 工具；搜索/DeepSeek/MCP、OAuth、模型目录更新/动态限额、代理设置未完成。
- 翻译队列跨工程自动调度、失败成员跳过/多批裁决、真实暂停恢复 UI 与设备验证；Agent 检查点/跨进程恢复、附件/图片、用户问题、多对象审批、完整写工具；PDF 页决策 UI 与版式保真；旧主链路退役、完整 U05—U15、真机/视觉/供应商/升级验收。
