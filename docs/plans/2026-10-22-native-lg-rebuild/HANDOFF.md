# 原生 LG 重构交接

更新：2026-10-25。这里只维护当前状态、证据和下一步；**正常接手只读本文件**。历轮细节完整保存在 [历史归档](HANDOFF_HISTORY.md)，仅遇到具体问题时搜索对应轮次，不要通读归档。

## 1. 范围与入口

目标是 LG Termux 业务能力的原生 Android 重建，保留并改造 Koog。工程/资产/条目或 PDF 页为中心，旧书架/阅读器主链路进入替换范围。

有效功能 96 项；历史 98 项中的 PR10（`.lg`/Pi 交换）与 OP06/R36（旧 LNT 数据迁移）已取消，不再新增实现或验收要求。保留原生 `.lnt` 升级、备份恢复及 Koog 历史/恢复；不删除旧用户数据。

按需查阅：[目标](README.md)、[架构](ARCHITECTURE.md)、[功能矩阵](FEATURE_MATRIX.md)、[任务与门槛](IMPLEMENTATION_PLAN.md)、[UI](UI_SPEC.md)、[文档目录](../../DOCUMENTATION.md)。计划、来源索引及用例数量不代表功能完成。

## 2. 源码与验证基线

### 2026-10-25 Actions #188 剩余四项 UI 测试修复

- 已核对 [Actions #188 / 40207671696](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40207671696)，提交 `876d554230a7ef6c417d24b47e0665e6e2ac63de`，与本轮修改前 HEAD 一致；2227 项单元测试中 2223 通过、4 失败，12 项 API 35 设备测试全部通过；Release/签名/APK 上传及 Lint 通过。此运行没有测试编译阻断或跳过；不覆盖下面工作区修改。
- 三个 `NativeSkillsDialogTest` 均在打开编辑器后等待正文超时；源码实际同时渲染编辑器与后置的目录 AlertDialog。改为同一个 Dialog 内切换目录/编辑器/删除确认，只展示当前内容；技能资源树入口不再叠加目录窗口。读取/保存、内置只读、冲突保留草稿契约沿用。
- `NativeWorkspaceDialogTest.artifactImportRequiresConfirmationAndPassesFrozenIdentity` 在打开导入确认后 `waitForIdle()` 超时。列表/预览/导入改为一个稳定 Dialog 窗口内切换，移除导入确认重建窗口及预览叠窗；导入 busy 状态上移，提交期间仍禁止返回关闭，固定工程/修订/产物/CommandId 参数保留。
- 新增仅绘制内容的 `NativeDialogSurface`，以有界 Surface/Column 布局共享标题、可收缩正文区和固定按钮区，避免 AlertDialog 多窗口及测量路径；文本/长列表保持各自滚动。技能测试显式滚动到字段后操作，不再吞掉任意异常等待；保留保存/冲突/只读断言，增加单窗口/目录隐藏断言与取消后重开相同技能重新读取用例；工作区现有导入用例增加取消不写入、重开确认及返回列表的断言。未增加超时或忽略失败。
- 本轮静态审查，未实际构建、未运行测试/Lint、未实际查看原生 UI；需 PC/授权 CI 复跑上述用例及完整测试，并在设备检查窄屏/IME/滚动与对话框焦点。#188 全部已通过项不继承为本轮修复的通过证据。未提交、推送或触发 Actions。


### 2026-10-25 Actions #181 修复与未完成测试盘点

- 最新已核对提交为 `40f6dd3fb93a599170513c6421369edf4239ca64`；[Actions #181 / 40197516389](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40197516389) 的 Release 构建/签名/APK 上传与 Lint 通过，test 失败。以下旧基线和“未提交增量”描述是历史验证边界；当前上述提交已包含产品增量，但没有完整测试通过证据。
- 本轮未提交修复：`NativeAgentStoreTest` 使用实际的 `AgentModelRequest.history`；四个提案存储测试的 `ChatId` 改为 `Delegates.notNull()`，避免 value class 的非法 `lateinit`；`NativeAppDatabaseUpgradeTest` 用 `try/finally` 关闭 Room；PDF 页请求、选择和回执解码的非法类型/未知 decision 统一抛出 `IllegalArgumentException`，保留原拒绝规则。扩展现有非法参数表，新增选择/回执畸形输入回归源码，并让参数表断言携带具体 JSON。
- 已下载并检查 #181 失败日志和全部测试 XML：formats 307/307、agent 36/36、domain 228/229；唯一报告失败为 PDF decision 异常类型不一致。storage 单元测试在编译阶段中断，不能把缺少 XML 当作通过。
- 按全部测试源码的 `@Test` 静态盘点，#181 没有结果的单元测试为 app 887、engine 329、storage 329、workspace 109，合计 1654 项、202 个源码文件；另有 app 3 项及 workspace 9 项设备测试，当前 CI 不执行 `androidTest`。这些数量用于定位缺失证据，不代表已运行或已通过。
- 对所有单元/设备测试扫描 value class `lateinit`、Room `use`、旧请求属性、显式工程导入、停用/占位标记与 JUnit 入口；593 处显式工程导入除生成的 `R` 外均能静态定位，157 处 app 测试资源引用均有定义；未发现 `@Ignore`、`@Disabled`、`TODO()` 或 `NotImplementedError` 测试占位。检查存储迁移版本断言及对应 `ensureV2`—`ensureV8` 路径，保留按写入能力升级的版本期望。扫描不覆盖全部类型推断、Compose 语义、协程时序或设备行为，不构成编译通过证明。
- 本轮仅静态审查，未实际构建，未运行测试/Lint，未提交、推送或触发 Actions。后续需 PC/已授权 CI 完整执行既有三个 job，再单独执行设备测试；#181 的绿色 Release/Lint 不覆盖本轮工作区修复。

### 2026-10-25 CI 尽量收齐全部测试失败

- 单元测试 Gradle 命令添加 `--continue`：某模块测试/测试编译失败后，继续运行依赖正常的其他模块，保留失败退出码与 `always()` 报告上传，不使用忽略失败。编译失败及其依赖测试仍无法执行，不能保证每轮都有全部用例结果。
- 新增与 test/lint/release 无依赖的 API 35 x86_64 模拟器矩阵作业，分别执行 app 与 workspace 的全部 `connectedDebugAndroidTest`；矩阵 `fail-fast: false`，一边失败不取消另一边，各自始终上传设备测试及构建计时报告。覆盖此前 CI 未纳入的 12 项设备测试；模拟器不代替真机/供应商验收。
- 以上为工作流源码修改，静态审查，未实际构建，未触发 Actions；模拟器启动、QuickJS x86_64 原生库、设备测试 APK 编译和运行仍需首轮 CI 证明。超时、基础设施故障、手动/新运行取消及公共依赖编译失败仍可能阻止完成所有测试。

### 历史基线与产品增量记录

- 本次审查修复：四个网络/限流 JSON 编解码与存储文件补齐 primitive `put` 扩展导入；profile extra-body 禁止覆盖 Responses `instructions` 和 Gemini `systemInstruction`，补对应拒绝回归用例源码。静态审查，未实际构建，未运行测试/Lint。

- 分支：`refactor/translation-agent-2026-10`。最新已提交基线 `d20c76f`，[Actions 40133193304](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40133193304) test/lint/release 全绿；2026-10-25 代码复核时通过 `gh run view` 再次确认 `head_sha=d20c76f602a591698c4cea9f2477da6982f546b2`，三个 job 均为 success；该提交的 Room 仍为 v13，不能覆盖工作区 v14—v18。
- 当前工作区是**多轮未提交产品增量**，不继承上述 CI。新增包括：PDF 页列表/预览→固定工程修订/资产摘要/页号读取→共享译文编辑器，未保存页处置时禁止跳转；工作区按 Run 的持久产物清单与逐项大小/SHA-256 对账、缺失/不符/不可读状态、创建时间、重新检查、禁用无效操作及独立健康产物读取；Agent 清单同步返回状态；U15 资产最后记录变更时间与原稿分块可用性投影（固定修订、分块连续/总长度检查、缺失禁导出、未知时间不伪造）；正文/状态草稿增量撤销重做（当前编辑会话不设固定条数上限、键盘快捷键、恢复生成新命令、保存/重载/姓名保存改变基线时隔离历史）；姓名译文数组批量赋值/清空（全查询或质量筛选固定目标，v5 恢复封套，摘要绑定完整数组，双阶段确认）；全查询/质量筛选固定目标的译文、姓名译文及双目标替换（单条目单修订原子写入、姓名快照、v4 恢复封套、可重建双目标回执）；规则审批按 ID/排序/提示词选择→新固定差异复核→二次确认，旧提案同事务拒绝、新摘要/命令/回执身份绑定实际子集；技能资源树直接执行入口（JSON 参数、显式网络开关、固定工程/Chat/修订/脚本摘要、持久 Run、定向取消与日志/产物展示，不调用模型）；有界 Android ICU 字素/Unicode 归一化宿主、glossary 字面匹配/词形关系/公共词根三个原生脚本入口与设备回归源码；技能 input_json 参数输入、快照真实 file_path、内置 text-preserve 原生同步扫描包及实际 APK/隔离脚本设备用例源码；分析/技能工具显式 allow_fetch 授权与隔离脚本 lg.fetch 生产接线、可等待挂起端口的同步桥、累计请求次数/字节预约预算；受控公网 HTTP Fetch 与 Agent `web_fetch` 生产工具（保存代理/超时、逐跳 DNS/IP 校验、重定向开关、64 KiB 文本预算、停止取消、HTML 文本提取）；工作区产物人工确认导入（固定修订/Run/摘要/大小、复用格式服务和工程写入回执）、SAF 另存为冻结产物摘要与大小；实际 writing-guide 内置包与两份参考文档迁入 APK、内置启用/排序偏好独立持久化与指纹 CAS（正文只读）、全部技能管理写操作接入运行互斥；内置技能 File/APK assets 完整资源树读取与脚本解析限额修复、用户技能资源文本文件创建/保存/重命名/删除（摘要 CAS、运行占用互斥、草稿放弃确认）与资源读取 ID 路径修复、v17→v18 非破坏性补表迁移与四种缺表组合回归源码、多模型 profile 与 U10 页面、U13 实验室真实消费者、U09 技能资源树/执行/上下文、工作区 artifact→工程资产导入审批、队列跳过继续/零跳过终结/CANCELLED 恢复、开机与覆盖安装恢复、规则 scope/正向命中/姓名检查、EPUB/Markdown/RenPy/TXT 格式保真、应用设置备份扩展与多文件分享。本机只做静态审查，**未实际构建、未运行测试/Lint、未提交/推送/触发 Actions**。
- 当前源码版本：`.lnt` v8（双目标手工命令回执语义，表结构沿用 v7，ensureV8 非破坏性升级）；应用 Room v18（模型 profile、资产导入提案）；翻译队列快照 v2。模型 profile 与资产导入表由 `MIGRATION_16_17` 创建；新增并注册 `MIGRATION_17_18`，幂等补齐较早开发版 v17 缺失的表/索引，保留已有行；四种表存在组合的 Room 打开/数据保留回归源码已补，未运行。权威 Room `2.json`—`18.json` 仍未导出。
- 当前静态检查：`git diff --check`、冲突标记检查、`python3 tools/rebuild/check_structure.py` 通过；五语资源 key 1105 条一致。上述检查不证明可编译或行为正确。
- 已有设备确认仅限历史 §22 的 TXT 手工切片，不能覆盖后续功能；新增视觉、后台/进程死亡、复杂格式、真实供应商与升级验收不足。CI 全绿不等于完整产品验收。

## 3. 实施状态

整体已完成基础工程与翻译闭环、较多业务扩展；仍有核心待生产接线和完整功能缺口，尚未到“只剩验证”。不按代码量或测试数计算完成百分比，S1/S2/S3、G1—G5 及全功能验收保持开放。

| 范围 | 已实现/接线（已提交基线有 CI；本轮增量另标） | 仍缺功能或验收 |
|---|---|---|
| 架构与工程 | app + 六核心模块；原生 Compose 入口；`.lnt` 多资产、槽位/格式元数据、归档移除、姓名修订快照、写回回执、工程设置及 ZIP 备份恢复；`.lnt` v8、Room v18、队列 v2 迁移源码 | 来源冲突等完整工程操作；Room 2—18 权威 schema 导出、真实升级与一致恢复验收；v17→v18 补表迁移的运行验证 |
| 翻译 | 远程批翻、统一网关、前台执行/通知停止、分批提交、NEW/CONTINUE/RESET、分组/前文/去重/重试拆批；固定任务/响应暂存、同 Run 接续、任务历史；应用级跨工程全局调度、重启 RUNNING 恢复、队列暂停/开始/取消、固定修订重校验、成员失败批次与同一任务重试/跳过/保留/终止、`COMPLETED_WITH_ERRORS` 真实记账、跳过成员执行排除；跳过部分成员后继续、零跳过同步终结任务、CANCELLED 恢复；打开应用加载完成后 kick 持久 QUEUED 任务；开机/覆盖安装只恢复不派发 | 开机/覆盖安装后台自动续排与跨进程调度；任务存储与队列成员行完全对账；暂停/崩溃窗口与设备验收；旧任务弹窗已禁对队列任务走旧路径，但历史路径未删除 |
| 规则与校对 | 四类规则/提示词、预设和模板库、JSON/CSV/XLSX 交换、模板编辑排序；质量扫描/筛选/定位、全查询固定目标批写、字面/正则及 JS 替换；单条姓名编辑；正文/状态草稿增量撤销重做及 Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y；姓名译文数组批量赋值/清空；批量查找替换可选译文/姓名译文/两者，保留源姓名/槽位/状态与一次修订；TERM 的正文/姓名/两者作用域、姓名范围警告、正向命中统计与按命中/缺失筛选；U05—U08 分区；规则审批支持逐规则内容、排序、提示词分选，生成新持久提案复核后再确认写入 | 跨保存/跨进程的完整撤销历史与完整代码编辑器；源姓名批量修改、PDF/宽屏完整校对台及设备交互；真实命中统计的设备/大数据验收 |
| Agent | Koog 结构化对话、SSE、工程摘要/读条目/列表/搜索/统计、单条修改审批；前台执行/重附着；输入队列、steer、历史分页/分支、摘要；工作区隔离同步脚本、预算内完整工程条目快照分析、条目/规则/PDF 页差异提案、workspace artifact→工程资产导入提案与生产 apply；内置/用户技能资源树与文件读取/内置 writing-guide 及参考文档、text-preserve 同步扫描脚本包、glossary 字面匹配/关系/词根脚本/内置启用与排序偏好/用户资源文本文件 CRUD/`run_project_skill`/启用技能上下文；队列失败成员审阅、技能元数据/人格注入 | 工作区文件/资产通用写 apply、搜索/MCP、附件/图片、其他对象多对象审批、内存预算与取消；Agent 检查点与跨进程执行接续；自动摘要长对话/供应商验证 |
| 模型与费用 | 统一 FIFO/固定并发/RPM；五协议核心及生产接线、多密钥池/冷却/认证分类、密钥状态；请求/用量账本；价格表；网络超时/代理/重定向、`Retry-After`、应用语言；多模型 profile、用途/能力/限额/生成参数/额外请求字段、复制排序删除、按用途选择、按 profile 限额 | OAuth、动态能力/限额探测、代理凭据、完整模型目录、历史定价快照/费用汇总；profile 能力/限额按当前行读取，不回写历史任务；真实供应商验证 |
| 格式 | TXT、Markdown、字幕、KVJSON/MESSAGEJSON、TRANS、RenPy、EPUB、XLSX、MV/MZ；RGSS 归档/Marshal/Scripts/Data；PDF 页级提取/替换/布局决策、页处置、固定修订导出、单页缩略图、页列表/预览直接进入页译文编辑（实际 1 起始页号与 page 标签校验、脏页处置阻止跳转）；EPUB 元数据/nav 属性/ruby 结构、Markdown 行内语法保护、RenPy 单/双三引号、TXT 原 charset/BOM/换行导出（不可编码时整份回退 UTF-8） | PDF OCR、原版式/图形/字体保真、PDF 页内容编辑的交互/导出验收；已有单 Unicode 字体替换排版，不等于原版式保真；RGSS 目录/非脚本 UI、WOLF 专有分支；EPUB 更多属性/阅读器兼容；各格式完整往返、视觉与设备验收 |
| 工作区/技能/联网 | 路径/预算/材料/产物/changes/审批/脚本契约、QuickJS 隔离服务与 IPC、固定修订材料快照、持久产物库、逐项完整性清单/时间/状态与按 Run 隔离读取、技能库、Web Fetch 策略及 `WorkspaceHttpFetch` 实现、Agent `web_fetch` 文本读取工具；Agent 只读材料/产物/技能工具、技能文件树与显式执行、资产导入审批；应用启动/覆盖安装恢复 | 文件/资产通用 apply、模块/异步/内存预算、搜索/DeepSeek/MCP、图片/PDF 宿主；隔离/native ABI/死循环/取消/内存设备验证；其余 LG 内置技能包迁移与宿主适配（writing-guide、text-preserve 与 glossary 三入口已迁入，后者需启用后执行）/自动选择执行/技能级权限 |
| UI/系统 | 工程首页与 Agent/工作台/校对/More 四主页面；U05—U10、U11—U15；五语 1105 key；语言切换、网络/限额、日志筛选与 Debug、文件/存储、技能、主题、备份入口；资产列表固定修订时间/原稿分块可用性；多模型编辑、实验室开关、资产导入审批、备份恢复确认、多文件分享 | 产物清单/后台并发与设备对账验收、RGSS 目录 UI、PDF 页编辑验收/宽屏校对、U09 资源树执行入口的设备交互；宽屏多栏、手机/平板/IME/TalkBack/SAF/通知/锁屏/进程死亡与视觉验收；应用备份不含工程实例、凭据、会话/审批、队列、诊断 |
| 退役与交付 | 新入口已切原生工程，签名 APK 已有 CI 产物；旧数据未删除 | 旧主链路/无关资源依赖退役，长任务/内存预算、安装覆盖升级、96 项证据与全格式/供应商/设备验收 |

### 3.1 2026-10-25 源码复核依据与准确边界

本节随代码实施同步更新，是当前调用链的静态核对，不是 96 项功能的逐项验收。检查实际消费者，分别登记生产接线、仅核心契约和缺少行为证据；未运行现有或新增测试。各路径相对仓库根目录。

| 能力 | 实际生产调用链 / 证据 | 本次确认的范围与限制 |
|---|---|---|
| 新入口与装配 | `app/src/main/java/com/breakyuna/noveltranslator/MainActivity.kt` → `nativeapp/NativeProjectApp.kt`；`NativeAppGraph.kt` 装配服务、存储与网关 | 原生入口已替换；不据此认定旧代码/依赖已退役 |
| 模型与实验室 | `NativeAppGraph.kt` → `ModelRequestScheduler` / `CompatibleAgentModel` / `CompatibleTranslationModel`；`core/engine/.../TextTranslationService.kt` 读取 laboratory | profile 被实际解析并参与模型请求和限流；三个实验室开关实际消费于 ruby 保守清理、提示词增强、同文件去重；不代表 MTool 或全部 LG 实验能力 |
| 翻译调度与恢复 | `NativeProjectViewModel.kt` 初始化等待非 busy 后 `translationQueueScheduler.kick()`；`NativeTranslationQueueScheduler.kt` → 核心 scheduler → foreground runner/service；`NativeRecoveryReceiver.kt` 只调 recover | 应用打开后的持久 QUEUED 排队任务已有派发入口；中断 RUNNING 恢复暂停/审阅，不自动重放；开机/覆盖安装没有 drain，不能笼统写成“完全没有重启续排”或“已完成后台续排” |
| 工作区分析与技能执行 | `NativeAppGraph.conversations.additionalTools` → `NativeWorkspaceAgentTools.kt` → `core/workspace/.../ProjectWorkspaceAnalysis.kt` → `IsolatedWorkspaceScriptEngine` | `analyze_project_script` 与 `run_project_skill` 已注册；技能读取启用包的 `scripts/**` 入口，运行同步脚本并返回日志/产物；快照受预算限制，超限拒绝，不是无限工程或 Node/模块/异步运行时 |
| 工作区通用 apply 与联网 | `core/workspace/.../WorkspaceRuntime.kt` / `WorkspaceChanges.kt` 有审批/apply 契约；`WorkspaceNetwork.kt` 有 search/fetch 端口 | 当前生产分析直接调用隔离 engine，没有装配通用 `WorkspaceRuntime`/`WorkspaceApplyPort`；分析/技能在 allow_fetch=true 时注入 Fetch 并授予 FETCH 能力，默认无网络；专用变更提案模式仍无网络。同步桥等待实际挂起端口并预约每次请求预算（最多 8 次，累计请求字节不超输出预算），取消共享 invocation token；Agent 也注册独立 `web_fetch`。搜索/MCP 与通用文件变更仍未上线 |
| 专用审批及资产导入 | `NativeProjectApp.kt` 审批 UI → `NativeProjectViewModel.acceptAssetImportProposal` → `NativeAssetImportProposalStore.apply` → `NativeAppGraph` 的 `NativeAssetImportApplier` → `ProjectFormatService.importAsset` | 单个本 Run 产物导入新资产已有 Agent 审批路径；工作区产物列表新增人工确认导入，固定修订/Run/摘要/大小后复用格式服务与 ProjectWriteStore，提交后的刷新错误单独提示、不伪报未提交；SAF 另存为也冻结摘要与大小。条目/规则/PDF 页各有专用提案存储；不等于通用文件 CRUD/混合对象事务完成 |
| 技能 UI 与上下文 | `NativeSkillsDialog.kt` → `NativeSkillFilesDialog`；`NativeAppGraph.skillContext` → `WorkspaceSkillStore` | 内置 File/APK assets 来源已补完整资源树与文件读取（原先只暴露 SKILL.md）；脚本读取上限改为与技能文件 256 KiB 上限一致，避免 512 KiB 参数被无条件拒绝。管理弹窗已有资源文件树预览和用户文本资源创建/保存/重命名/删除（SKILL.md 仍走正文编辑器）；通过 ViewModel 获取 skill-write lease，阻止 Agent 执行期间改资源，文件摘要与技能元数据指纹共同校验。writing-guide 主文与两份参考文档、text-preserve 与 glossary 原生脚本已放入 APK；执行工具支持 input_json 对象字符串，保留宿主 revision/item_count 等参数；text-preserve 的完整计数/逐文件分布/零长度命中/UTF-16 范围逻辑来自 LG 原脚本，Node 异步输入改为固定修订 JSONL 同步迭代，结果通过产物发布，glossary 保留 LG Aho 匹配、NFKC/casefold、原文范围、关系与词根逻辑，字素/NFC/NFKC 通过有界 Android ICU/Normalizer 宿主调用，设备 Unicode 版本差异和实际脚本用例尚未运行；内置包启用/排序偏好独立保存并做指纹 CAS，内容仍只读；管理写操作统一获取技能写占用。资源树新增 scripts/** 的直接执行按钮，原生执行窗口输入 JSON/显式授予网络，ViewModel 以固定工程/Chat/修订/脚本摘要运行，复用持久 Run 和隔离引擎，日志/产物可见，定向停止并等待收尾，不发模型请求；Agent 工具入口同时保留。直接执行由窗口协程持有，离开/重建窗口会取消，尚未提升为可重附着的前台任务。自动注入至多 4 个启用技能、每个读取至多 2048 字符，不是完整自动选技能/自动执行 |
| 格式注册与 PDF | `core/formats/.../FormatAdapters.kt` 的 `NativeFormatAdapters.install()` 由 graph 调用；TXT 由文本 store 负责；`PdfFormatAdapter.kt` / `PdfPageRenderer.kt` | 所列适配器有生产注册，但不代表全格式保真验收。PDF 已有字体候选/字形检查与静态替换排版；旋转文本、表单/附件和版式仍有明确限制，不能把“字体保真未完成”误写成“完全没有字体支持” |
| 规则与质量 | `core/engine/.../TextProofreadingInspector.kt` → `ProjectQualityScanner.kt`，读取 `nameSource/nameTranslation` 并累计 hits | TERM 正文/姓名作用域与正向命中有实际扫描消费者；设备正则、大工程和完整交互仍未验收 |
| 数据版本与迁移 | `core/domain/.../TextProject.kt` 的 schema=8；`NativeAppDatabase.kt` version=18；`NativeTranslationQueueStore.kt` version=2 | HEAD 的 Room 为 v13，工作区 v14 条目提案、v15 规则提案、v16 PDF 页提案、v17 profile/资产导入与 v18 补表迁移均未被该 CI 构建；schemas 目录实际只有 Room `1.json`，2—18 缺失 |

**完成口径**：当前能确认基础产品闭环与上述局部生产接线；无法从源码推导“96 项中已完成多少项”。S1/S2/S3 和 G1—G5 保持开放。真实供应商、设备生命周期/隔离、复杂格式输出、全量升级和视觉没有在本次复核中验证。当前工作区统计仅作审查快照：253 个变更条目（124 个未跟踪，资源编辑与 v18 实施前的复核快照），不作为完成比例；未提交增量整体需要独立构建与回归。

当前用户要求“此轮修改完成后暂停”。本轮收尾为 PDF 页译文编辑入口、窄屏按钮分行、1 起始页号/页标签校验、未保存页处置阻止跳转及 UI 回归源码；仅静态检查，未构建/运行测试。工作区全部未提交修改原样保留，不自动开启下一项、提交、推送或 CI。

## 4. 下一轮顺序

以下为待办，不自动授权实施、提交、推送、CI 或付费调用；按用户当前要求执行。

1. **Agent 工作区继续闭环**：文件/资产通用写审批与 apply、搜索/MCP、附件/图片、多对象审批、模块/异步/内存预算与取消；设备测试源码在 `core/workspace/src/androidTest`，尚未构建/运行，现有 CI 不执行这些设备测试。
2. **任务与恢复**：跨进程调度/开机后台自动续排（应用打开后已有 QUEUED kick）、任务存储与队列成员行对账、暂停/崩溃窗口、Agent 检查点与完整工具恢复；旧任务弹窗与队列裁决路径继续收敛。
3. **格式与系统**：PDF 原版式/字体保真/OCR、页编辑链路的交互与导出验证、RGSS 目录 UI、WOLF 分支、EPUB 更多兼容；产物清单的设备/并发对账验收、U09 执行窗口/取消的设备验证、其余实际内置包迁移、宽屏校对。
4. **验证与退役**：PC/授权 CI 生成并审阅 Room 2—18 schema；实际查看原生截图；补 SAF、后台/锁屏/进程死亡、升级、真机正则、性能及明确授权的供应商验证；逐格式闭环，完成旧代码退役与功能证据。

## 5. 高风险与技术决策

本轮 `.lnt` v8、Room v18、队列 v2、模型 profile、资产导入、工作区材料/产物、网络限流、U05—U15 页面及 PDF/EPUB 保真均为**未构建增量**，只能算静态源码，不能当作可发布行为。`MIGRATION_17_18` 已补上现有开发版 v17 的幂等补表升级路径；其 Room schema 校验、既有行保留与设备升级仍未运行验证。隔离声明依据 Android 官方 service isolatedProcess 契约（https://developer.android.com/guide/topics/manifest/service-element#isolated），仍需设备证实 native ABI、进程终止与资源预算；QuickJS 0.9.2 API 未提供堆限额/中断处理器，不能把隔离进程等同已完成内存预算。草稿撤销历史目前保留在活动 ViewModel 内，仅存增量且不设条数硬截断；进程死亡只恢复当前草稿，不恢复撤销栈，大量编辑的内存预算仍需测量。重点仍是 Koog 检查点/恢复、工作区权限/取消/回收、一致备份、PDF 版式/字体/资源及大工程预算。Fetch 新增路径未实际联网验证；代理远端 DNS 行为、系统 DNS 阻塞取消、重定向、非 UTF-8 站点、HTML 提取及恶意页面需独立验证，不把源码中的请求超时当作完整网络验收。保持 API 24 支持边界，以真实设备测量冻结预算；未测量不宣称性能提升。

## 6. 历史追溯与维护

[HANDOFF_HISTORY.md](HANDOFF_HISTORY.md) 完整保留精简前内容及 §1—§122，包括初始源码/CI、实施记录、用户授权与暂停、故障修复和当时限制。其他文档引用旧轮次（如 §22、§78、§102—§122）时去归档定位；旧状态不覆盖本文件。

本文件不继续追加逐轮日志。后续完成业务时直接更新 §2—§4，记录实际提交/CI 和未验证边界；只有确需追溯的决定/故障再追加归档，并在相关状态处链接。文档整理不表示产品功能新增。新开对话不要求读取归档，局部任务也无需通读计划。
