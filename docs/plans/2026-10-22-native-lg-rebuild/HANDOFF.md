# 原生 LG 重构交接

更新：2026-10-27。这里只维护当前状态、证据和下一步；**正常接手只读本文件**。历轮细节完整保存在 [历史归档](HANDOFF_HISTORY.md)，仅遇到具体问题时搜索对应轮次，不要通读归档。

## 1. 范围与入口

目标是 LG Termux 业务能力的原生 Android 重建，保留并改造 Koog。工程/资产/条目或 PDF 页为中心，旧书架/阅读器主链路进入替换范围。

有效功能 96 项；历史 98 项中的 PR10（`.lg`/Pi 交换）与 OP06/R36（旧 LNT 数据迁移）已取消，不再新增实现或验收要求。2026-10-26 用户进一步收窄格式范围：**产品以 EPUB 和 TXT 为主；PDF OCR 取消；其他格式选择冷冻**——保留现有适配器、旧工程与旧用户数据，不再新增实现或验收，仅保留既有代码与历史数据可打开/导出。保留原生 `.lnt` 升级、备份恢复及 Koog 历史/恢复；不删除旧用户数据。

按需查阅：[目标](README.md)、[架构](ARCHITECTURE.md)、[功能矩阵](FEATURE_MATRIX.md)、[任务与门槛](IMPLEMENTATION_PLAN.md)、[UI](UI_SPEC.md)、[文档目录](../../DOCUMENTATION.md)。计划、来源索引及用例数量不代表功能完成。

## 2. 源码与验证基线

### 当前源码与 CI

- 2026-10-27：[Actions #204 / 40361934256](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40361934256) 对应 `4dd4b21`，`test`、`lint`、`release`、两组 API 35 设备作业全部通过；该提交移除 warning 配色对旧 `ui.theme.LocalAppColors` 的依赖，在 `NativeTheme` 内按主题模式提供原生警告色。此前 [Actions #203 / 40358823290](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40358823290) 对应 `25217b2`，test 在模块边界静态检查失败、单元测试未执行，由 `4dd4b21` 修复。#204 结论只覆盖 `4dd4b21`，不覆盖其后的工作区增量。

- 2026-10-27：本轮修复前 HEAD 为 `a4c86b5`，[Actions #202 / 40357740599](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40357740599) 总体为 cancelled，但 lint 作业实际在 `:app:compileDebugKotlin` 报错：`NativeLogsPage` / `NativeRunHistory` 引用未定义的 `NativeTone.WARNING`，`NativeModelsPage` 缺少 `FontWeight` 导入；Unit tests 和 Release 构建步骤失败，设备测试取消，签名 APK 未上传。本轮修复补齐 WARNING 枚举及两个共享组件的主题警告色映射，补上 FontWeight 导入。静态审查，未实际构建；未运行测试或 Lint；用户已授权再次提交，随本轮提交推送当前分支并触发 CI，结果待核对。下述 `1d3dd1c` / #201 仅为上一绿色基线，不覆盖 `a4c86b5` 或本轮修复。

- 分支为 `refactor/translation-agent-2026-10`。上一绿色代码基线为 `1d3dd1c`，其后 `fe77886` 仅 HANDOFF 文档；`1d3dd1c` 的 [Actions run 40329494778](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40329494778) 的 `test`（2268 项，0 失败）、`lint`、`release`（签名 APK artifact）与两组 API 35 设备 job（app 2/2、workspace 13/13）**全部通过**。此前 `057758a` 的 [run 40324194475](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40324194475) 因 engine/storage 编译与 NCX 单测失败，经 `8ea0433`（队列/检查点/测试编译与 NCX）、`c40f626`（app 端口导入与质量证据类型）修复后，`1d3dd1c` 再修正 RGSS 压缩断言与 Robolectric SDK 35。上一基线 `67b0b2e` 的 [Actions #197 / 40255046354](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40255046354) 仍只覆盖该提交。
- `057758a` 之前的全部未提交改动（工作区 apply、搜索、上传、检查点/恢复、EPUB、宽屏、RGSS 只读浏览、格式冷冻策略等）现已随 `057758a` 提交，编译/测试修复见 `8ea0433`、`c40f626`、`1d3dd1c`；本机未构建，以上绿测结论来自 CI。
- 2026-10-26 代码审查修复（`057758a` 提交，`8ea0433` 修复其 CI 编译/NCX；未本地构建）：修复 `repairPendingToolTail` 成员扩展导致 `core:agent`/app 无法编译；队列租约改为显式 Token，获取失败不再释放他人锁，启动/恢复/续排统一在 IO 获取，并改用 API 24 可用的 `RandomAccessFile` 文件锁；EPUB 不再把 `dc:language`/`dc:date`/`dc:identifier` 当正文翻译，NCX 跳过 `docAuthor`，旧 `linear="no"` 译文仍可导出；切换工程清空工作区提案并在 Agent 加载期间 fail-closed；工作区审批后结算旧的 `WAITING_APPROVAL` 执行；开机 receiver 等待首轮 drain；工作区审批对话框批准/拒绝后关闭；宽屏规则编辑器阻止未保存导航；上传文件名按 UTF-8 字节清洗；apply intent 记录预检结果，崩溃恢复不再误报已提交；RGSS 浏览增加 24 MiB 上限；检查点每分区 16 个并增加全局 128 MiB/256 文件上限，拒绝 v2 编码；新增 `WorkspaceFileApplyStore` 设备测试与检查点版本测试。本机仍为静态审查；CI 已在 `1d3dd1c` 运行 2268 项单元测试、两组 API 35 设备测试、Lint 与 Release，全部通过。
- 原 PDF 诊断仍保留在 `HANDOFF_HISTORY.md`；当前 PDF 仅保留已完成的 IO/错误处理和既有页编辑代码，OCR/版式/字体保真已取消。
- 历史 [Actions #193 / 40249427707](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40249427707) 对应 `ac4fd8e`，app 单元测试因缺少协程上下文编译失败；六核心模块缓存测试、11 项设备测试、Release/签名/APK 上传及 Lint 通过。元数据 CAS 测试已通过 `runBlocking<Unit>` 修复，保留普通 JUnit、TemporaryFolder、全部断言与 finally 清理；该修复已有 #194 的两项测试通过证据。
- 历史完整 app 单元测试基线 [Actions #192 / 40212487481](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40212487481)，对应 `93b3484`；完整测试 XML 显示 2228 项单元测试中 2227 通过、1 失败、0 跳过，12 项 API 35 x86_64 模拟器设备测试全部通过；Release/签名/APK 上传及 Lint 通过。六个核心模块 1340 项全过，唯一失败为 app 的 `NativePdfPagesDialogTest.originalThumbnailLoadsOnlyThePreviewedPageAndKeepsTextPreview`，等待图片节点 10 秒超时。#193 未执行 app 单元测试，不能认定该超时已解决。
- #191 的五项对话框超时已在 #192 全部通过：`NativeSkillsDialogTest` 4/4、`NativeWorkspaceDialogTest` 3/3。当前非默认宽度窗口/有界面板得到这次 CI 证据，不再归因于业务保存、只读或导入回调失败。#188—#191 为历史失败基线，不覆盖当前工作区差异。
- 上一轮 PDF 修复：列表与预览从两个同时存在的 Dialog 改为同一窗口的互斥内容，防止异步预览被后创建的列表窗口遮挡；返回先回列表，固定工程/修订/资产/页号、后台解码和保存保护保留。#194—#196 已通过图片加载与可见断言，但关闭按钮高度为 0、未返回列表；不把源码中的布局风险直接当作已复现的设备根因。
- 测试优化：移除标题截图、包名、固定应用名称 3 项示例检查；MainActivity/manifest 已切原生入口，`AppNavigation` 无生产调用，删除旧阅读器→工作台导航测试的 4 项（不删除旧用户数据或旧生产代码）。保留术语 JSON 围栏/非法响应测试，重命名为 `TermExtractionResponseTest`，因 Android `org.json` 保留 Robolectric；`ExampleUnitTest` 中实际业务回归继续保留。
- 合并 PDF 加载中→图片显示与固定页号/正文预览用例，使用 `CompletableDeferred` 控制返回，保留真实 PNG 解码、失败检查及单窗口断言；合并技能取消重开与元数据保存流程，保留重新读取、取消不保存及实际 draft 字段断言。两项合并减少重复启动，不用增大超时/忽略失败。`NativeSkillMetadataTest` 两项改用普通 JUnit + `TemporaryFolder`，继续真实文件 CAS/元数据验证，无需 Android Context 或 Robolectric。
- 当前源码静态盘点早于本轮新增测试；CI 在 `1d3dd1c` 实际报告 **2268 项单元测试 0 失败**，设备测试 app 2/2、workspace 13/13。`@Test` 数量只说明覆盖规模，不能折算功能完成度，也不替代设备与供应商验收。
- 本机仍只做静态检查，未构建、未运行测试/Lint、未查看原生截图；`1d3dd1c` 的绿色来自 CI，结构/差异/冲突标记检查不证明设备视觉、供应商或完整升级行为。

### 2026-10-27 本轮增量（已提交推送，CI 待核对）

- 审查修复增量：预算兼容构造显式指定内存参数、混合审批 UI 投影可见性、MCP 搜索后备鲜读目录并验证只读、生成探测传 OAuth 模式、同响应最多 8 次工具调用收集同 Run 提案后等待审批（新发送仍阻断）、工作区执行重附着所有权。完整备份冻结真实用量 JSONL 与历史定价绑定；持有队列跨进程文件锁和运行时归档保留，工程快照使用专用保留句柄，诊断在释放前冻结。完整恢复在发布设置前保存私有回滚快照（加密模型行保持密文、价格及历史/模板/预设/网络/限额/实验室/偏好），失败及中断恢复回滚，提交后清理；两种备份均清除代理凭据引用。新增保留边界和中断回滚回归测试源码，未执行。静态审查，未实际构建；未运行测试或 Lint；本轮已随提交推送并手动 dispatch CI，结果待核对。

- 本轮在 `4dd4b21` 之上新增源码增量（约 205 个文件），随本轮提交推送当前分支；本分支 push 不自动触发 Actions，已手动 dispatch。只做静态检查，未运行 Gradle/构建/单测/Lint。下表及本节旧描述保留 10-26 基线；本轮增量以本节为准，**不等于功能完成**。
- vision：`core/engine` 已支持 USER 图片 part、OpenAI Chat/Responses、Anthropic、Gemini 编码、纯 Kotlin Base64、`AgentImageResolver` 与能力门禁；app 已接文件型附件存储、SAF 选择、缩略图/删除、纯图片发送、五语。真实供应商图片兼容、设备 SAF/内存未验收。
- MCP：core 客户端/JSON-RPC/HTTP/SSE/搜索 + app 配置存储、独立 Keystore 凭据、DuckDuckGo 后备、`mcp_list_tools`/`mcp_call` 只读工具、More 页配置 UI。只暴露 `readOnlyHint=true` 工具，有副作用工具不暴露；未连接真实 MCP server。
- OAuth：通用 OAuth2 + PKCE、回调 Activity、token 交换/刷新/退出、配置 CAS、模型 resolver 注入、模型页认证区；OAuth 传输改为 Bearer，API key 旧头不变。真实 provider、浏览器回调、Keystore 设备行为未验收。
- 代理/费用：代理凭据独立 Keystore + Basic proxyAuthenticator + 设置 UI；价格表版本化、回执记录价格版本、`UsageCostAggregator` 按历史版本/币种报价，旧回执缺失版本显示 UNKNOWN，不报 0。Room v20 已加回执价格列。
- 模型目录：目录快照存储、Gemini 元数据、响应头白名单、U10 显式元数据/生成探测、缓存/空覆盖/计费确认 UI。生成探测需确认，不自动触发/重试。
- 工作区：resident/peak 预算、逐资产 materialize、apply 取消、技能 `WorkspaceSkillPolicy`/选择器/执行门禁、编辑保留 policy、Agent 无 `allow_fetch` 提升、直接执行网络只能收窄；app 已接自动选择与 U09 资源入口。
- 队列/恢复：失败批次重建、暂停/取消持久意图、对账报告、app 控制存储适配、JobScheduler 唤醒、receiver 改为入队即返回；跨进程/进程死亡仍只能设备验收。
- U09：`ForegroundWorkspaceRunner` + app 执行 Adapter/Service/Host/通知停止/token 重附着；窗口关闭只 detach，进程死亡不自动重放。
- 备份：manifest v2、FULL_ARCHIVE 数据端口/恢复 receipt、app 完整备份入口；工程/会话/审批/队列/诊断按只读历史或 PAUSED/NEEDS_REVIEW 恢复，不继承执行授权、不自动 kick。
- 混合审批：领域模型 + Room v19 两张表 + `MixedProjectWriter` 单 `.lnt` 事务 + workspace 先行 saga + 子提案单消费拒绝 + 聚合审批 UI；workspace 成员标记 `WORKSPACE_DEFERRED`，由协调器 receipt 负责。
- 旧数据/退役：新增只读 `LegacyExportService`（TXT/EPUB/术语/规则/提示词/聊天/进度、manifest/SHA、凭据排除、不删除旧库）、More 页导出入口；新增架构测试禁止 `nativeapp` 重新导入旧 `ui/data/core` 主链路。旧代码与旧数据保留用于导出，物理删除未做。
- EPUB/TXT：head title 同步、竖排/RTL 清理、nav+spine、TXT 预算统一/UTF-8 回退/额外分隔符；新增 TXT/EPUB 多文件/目录新建工程导入，EPUB 首资产、TXT 正文、逐项失败。真实样本/阅读器未验收。
- 规则/校对：新增源姓名批量修改（`ASSIGN_SOURCE_NAMES`，保留译文姓名/正文/状态与一次修订）；旧任务弹窗收敛，队列任务只走新审阅/失败成员路径，历史非队列任务仍可查看。
- 编辑器：新增草稿撤销历史持久化（工程/资产/修订/指纹绑定、有界、AtomicFile、进程死亡恢复、修订变化拒绝、损坏 fail closed）；Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y 不变。
- 内置技能：补齐 agent-charter/epub/roleplay/translation，补 writing-guide/text-preserve/glossary 参考文档；pdf/xlsx/rpgmaker/skill-creator 按决定不迁或冻结。
- 数据版本：`.lnt` v8、Room v20、队列 v2；权威 Room `2.json`—`20.json` 仍未导出。QuickJS 模块/异步/硬堆限制仍受引擎能力约束，内存预算只是采样上限，不宣称硬限制；完整代码编辑器未实现，旧代码物理删除待只读导出验收后进行。

### 已取得的具体证据及边界

- #188 四项 UI 失败及 #191 新增重开用例的超时已有 #192 通过证据，具体范围见上文；窄屏/IME/滚动/焦点仍需实际查看原生 UI。PDF 预览及测试精简已提交为 `ac4fd8e`；#194—#196 图片/正文显示已通过；#196 语义树明确关闭按钮高度为 0、仍停在预览。权重宿主修复及关闭尺寸断言已由 #197 验证。
- #188 单元 XML 确认 `NativeAppDatabaseUpgradeTest` 四种 v17 表存在组合的 v18 打开/数据保留用例均通过。这是自动化迁移证据，不等于覆盖所有历史版本真实安装升级。
- #188 workspace 模拟器报告覆盖：隔离服务声明及执行、大脚本/材料通过描述符传递、越权写拒绝、提案返回、死循环超时后再次运行、主动取消后再次运行（6 项）；完整分页快照生成类型化提案（1 项）；Unicode 归一化/字素及非法/超限输入拒绝（2 项）。已具备 API 35 x86_64 的隔离执行/超时/取消证据，仍缺 ARM 真机、其他 Android 版本和内存硬预算证明。
- #188 app 3 项设备测试中，1 项为包上下文检查，2 项实际运行 APK 内置 text-preserve/glossary 脚本；不能把 3 项都计为业务或视觉验收。上述 Unicode/脚本行为在该模拟器已运行，不再标为“只有回归源码”。
- 当前工作流单元测试使用 `--continue`，设备矩阵独立执行 app/workspace 的 `connectedDebugAndroidTest`，`fail-fast: false`，始终上传报告。#188 已证明该设备路径可运行；编译/基础设施失败仍可能使部分测试无结果。
- 当前版本为 `.lnt` v8、Room v18、翻译队列快照 v2。`MIGRATION_16_17` 创建 profile/资产导入表，`MIGRATION_17_18` 幂等补表；源码已注册迁移。仓库 schemas 目录仍只有 `1.json`，权威 Room `2.json`—`18.json` 待导出并审阅。
- 本地验证仍只做静态审查，未实际构建、未运行测试/Lint、未实际查看原生 UI；远端证据限定于各自提交和报告（当前为 `1d3dd1c` 的 run 40329494778）。既有真机手工确认仍仅限历史 §22 的 TXT 切片，新增视觉、后台/进程死亡、复杂格式、真实供应商及完整升级验收不足。

## 3. 实施状态

整体已完成基础工程与翻译闭环、较多业务扩展；仍有核心待生产接线和完整功能缺口，尚未到“只剩验证”。不按代码量或测试数计算完成百分比，S1/S2/S3、G1—G5 及全功能验收保持开放。

| 范围 | 已实现/接线（验证范围见 §2） | 仍缺功能或验收 |
|---|---|---|
| 架构与工程 | app + 六核心模块；原生 Compose 入口；`.lnt` 多资产、槽位/格式元数据、归档移除、姓名修订快照、写回回执、工程设置及 ZIP 备份恢复；`.lnt` v8、Room v18、队列 v2 迁移源码 | 来源冲突等完整工程操作；Room 2—18 权威 schema 导出、真实升级与一致恢复验收；v17→v18 四组合自动化已通过，仍需真实覆盖升级 |
| 翻译 | 远程批翻、统一网关、前台执行/通知停止、分批提交、NEW/CONTINUE/RESET、分组/前文/去重/重试拆批；固定任务/响应暂存、同 Run 接续、任务历史；应用级跨工程全局调度、重启 RUNNING 恢复、队列暂停/开始/取消、固定修订重校验、成员失败批次与同一任务重试/跳过/保留/终止、`COMPLETED_WITH_ERRORS` 真实记账、跳过成员执行排除；跳过部分成员后继续、零跳过同步终结任务、CANCELLED 恢复；打开应用加载完成后 kick 持久 QUEUED 任务；开机/覆盖安装恢复后触发一次受租约保护的 drain（receiver 最多等待 30s） | 多进程/进程被杀后的跨进程调度设备验收；任务存储与队列成员行完全对账；暂停/崩溃窗口与设备验收；旧任务弹窗已禁对队列任务走旧路径，但历史路径未删除 |
| 规则与校对 | 四类规则/提示词、预设和模板库、JSON/CSV/XLSX 交换、模板编辑排序；质量扫描/筛选/定位、全查询固定目标批写、字面/正则及 JS 替换；单条姓名编辑；正文/状态草稿增量撤销重做及 Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y；姓名译文数组批量赋值/清空；批量查找替换可选译文/姓名译文/两者，保留源姓名/槽位/状态与一次修订；TERM 的正文/姓名/两者作用域、姓名范围警告、正向命中统计与按命中/缺失筛选；U05—U08 分区；规则审批支持逐规则内容、排序、提示词分选，生成新持久提案复核后再确认写入 | 跨保存/跨进程的完整撤销历史与完整代码编辑器；源姓名批量修改、PDF/宽屏完整校对台及设备交互；真实命中统计的设备/大数据验收 |
| Agent | Koog 结构化对话、SSE、工程摘要/读条目/列表/搜索/统计、单条修改审批；前台执行/重附着；输入队列、steer、历史分页/分支、摘要；工作区隔离同步脚本、预算内完整工程条目快照分析、条目/规则/PDF 页差异提案、workspace artifact→工程资产导入提案与生产 apply；工作区文件变更审批/apply、DuckDuckGo 搜索链、`web_search`、`lg.search`；内置/用户技能资源树与文件读取/内置 writing-guide 及参考文档、text-preserve 同步扫描脚本包、glossary 字面匹配/关系/词根脚本/内置启用与排序偏好/用户资源文本文件 CRUD/`run_project_skill`/启用技能上下文；检查点/工具尾部恢复；队列失败成员审阅、技能元数据/人格注入 | MCP（缺少外部端点/凭据配置）、模型图片 vision、多对象混合审批、内存预算与取消；自动摘要长对话/供应商验证 |
| 模型与费用 | 统一 FIFO/固定并发/RPM；五协议核心及生产接线、多密钥池/冷却/认证分类、密钥状态；请求/用量账本；价格表；网络超时/代理/重定向、`Retry-After`、应用语言；多模型 profile、用途/能力/限额/生成参数/额外请求字段、复制排序删除、按用途选择、按 profile 限额 | OAuth、动态能力/限额探测、代理凭据、完整模型目录、历史定价快照/费用汇总；profile 能力/限额按当前行读取，不回写历史任务；真实供应商验证 |
| 格式 | **活动范围：TXT 与 EPUB**。TXT 原 charset/BOM/换行导出（不可编码时整份回退 UTF-8）；EPUB 元数据/nav 属性/ruby 结构，`dc:language`/`dc:date`/`dc:identifier` 与 NCX/OPF 作者作为身份数据保护；两者已有导入/导出、条目编辑、校对与修订门禁。其余格式（Markdown、字幕、KVJSON/MESSAGEJSON、TRANS、RenPy、XLSX、MV/MZ、RGSS、PDF 等）**冷冻**：适配器、旧工程和旧数据保留，可继续打开/导出，但不再新增实现、导入入口或验收 | EPUB/TXT 真实样本往返、阅读器/编码兼容与视觉验收；非 EPUB/TXT 格式不作为完成条件（用户 2026-10-26 决定），PDF OCR 取消；新导入入口已限制为 TXT/EPUB |
| 工作区/技能/联网 | 路径/预算/材料/产物/changes/审批/脚本契约、QuickJS 隔离服务与 IPC、固定修订材料快照、持久产物库、逐项完整性清单/时间/状态与按 Run 隔离读取、技能库、Web Fetch/搜索策略、`WorkspaceHttpFetch`、`workspace-files` 指纹清单、文件变更审批/写前 intent/回执、uploads/ 上传、Agent `web_fetch`/`web_search`；Agent 只读材料/产物/技能工具、技能文件树与显式执行、资产导入审批；应用启动/覆盖安装恢复 | MCP、模型图片 vision、多对象混合事务、模块/异步/内存预算；ARM/多 Android 版本隔离与 native ABI、内存预算验证（API 35 x86_64 死循环/取消已通过）；其余 LG 内置技能包迁移与宿主适配（writing-guide、text-preserve 与 glossary 三入口已迁入，后者需启用后执行）/自动选择执行/技能级权限 |
| UI/系统 | 工程首页与 Agent/工作台/校对/More 四主页面；U05—U10、U11—U15；五语 1105+ key；语言切换、网络/限额、日志筛选与 Debug、文件/存储、技能、主题、备份入口；资产列表固定修订时间/原稿分块可用性；多模型编辑、实验室开关、资产/工作区变更审批、工作区上传、RGSS 只读浏览、备份恢复确认、多文件分享；宽屏校对/规则/Agent 双栏 | 产物清单/后台并发与设备对账验收、宽屏校对的实际设备交互、U09 资源树执行入口的设备交互；手机/平板/IME/TalkBack/SAF/通知/锁屏/进程死亡与视觉验收；应用备份不含工程实例、会话/审批、队列、诊断。RGSS/PDF 等非 EPUB/TXT 专属 UI 已冷冻，不作为验收项 |
| 退役与交付 | 新入口已切原生工程，签名 APK 已有 CI 产物；旧数据未删除；新导入入口限制为 TXT/EPUB | 旧主链路/无关资源依赖退役，长任务/内存预算、安装覆盖升级、EPUB/TXT 功能证据与设备验收；非 EPUB/TXT 格式冷冻 |
### 3.1 源码复核依据与准确边界（2026-10-26 同步）

本节随代码实施同步更新，是当前调用链的静态核对，不是 96 项功能的逐项验收。检查实际消费者，分别登记生产接线、仅核心契约和缺少行为证据；本机未运行测试，远端测试证据见 §2。各路径相对仓库根目录。

| 能力 | 实际生产调用链 / 证据 | 本次确认的范围与限制 |
|---|---|---|
| 新入口与装配 | `app/src/main/java/com/breakyuna/noveltranslator/MainActivity.kt` → `nativeapp/NativeProjectApp.kt`；`NativeAppGraph.kt` 装配服务、存储与网关 | 原生入口已替换；不据此认定旧代码/依赖已退役 |
| 模型与实验室 | `NativeAppGraph.kt` → `ModelRequestScheduler` / `CompatibleAgentModel` / `CompatibleTranslationModel`；`core/engine/.../TextTranslationService.kt` 读取 laboratory | profile 被实际解析并参与模型请求和限流；三个实验室开关实际消费于 ruby 保守清理、提示词增强、同文件去重；不代表 MTool 或全部 LG 实验能力 |
| 翻译调度与恢复 | `NativeProjectViewModel.kt` 初始化等待非 busy 后 `translationQueueScheduler.kick()`；`NativeTranslationQueueScheduler.kt` → 核心 scheduler → foreground runner/service；`NativeRecoveryReceiver.kt` → `recoverExclusive()` + `kick()?.join()` | 应用打开后的持久 QUEUED 排队任务已有派发入口；中断 RUNNING 恢复暂停/审阅，不自动重放；开机/覆盖安装已触发一次受租约保护的 drain（receiver 最多等待 30s）；跨进程/进程被杀后的持续续排仍未设备验收 |
| 工作区分析与技能执行 | `NativeAppGraph.conversations.additionalTools` → `NativeWorkspaceAgentTools.kt` → `core/workspace/.../ProjectWorkspaceAnalysis.kt` → `IsolatedWorkspaceScriptEngine` | `analyze_project_script`、`prepare_workspace_changes` 与 `run_project_skill` 已注册；工作区提案模式注入只读 `workspace-files.jsonl`（最多 1024 条）并内联 material 内容；技能读取启用包的 `scripts/**` 入口，运行同步脚本并返回日志/产物；快照受预算限制，超限拒绝，不是无限工程或 Node/模块/异步运行时 |
| 工作区通用 apply 与联网 | `NativeAppGraph.workspaceChanges` → `NativeWorkspaceChangeProposalStore` → `WorkspaceFileApplyStore`（`work/`、`uploads/`、`reference/`，intent + receipt + 崩溃恢复）；搜索走 `WorkspaceSearchChain` → `NativeDuckDuckGoSearch`，`web_search`/`web_fetch` 已注册 | 生产已装配工作区文件审批/apply，但只限三个可写区，Agent 分析工具单批最多 64 项，且与工程对象仍是分开的提案存储，不是多对象混合事务；`sources/`、`datasets/`、`artifacts/` 永不可写；分析/技能仅在 allow_fetch=true 时注入 fetch/search（最多 8 次、累计字节受限）；MCP、通用 `WorkspaceRuntime` 装配、搜索反爬/真实公网与恶意页面仍未验收 |
| 专用审批及资产导入 | `NativeProjectApp.kt` 审批 UI → `NativeProjectViewModel.acceptAssetImportProposal` → `NativeAssetImportProposalStore.apply` → `NativeAppGraph` 的 `NativeAssetImportApplier` → `ProjectFormatService.importAsset` | 单个本 Run 产物导入新资产已有 Agent 审批路径；工作区产物列表新增人工确认导入，固定修订/Run/摘要/大小后复用格式服务与 ProjectWriteStore，提交后的刷新错误单独提示、不伪报未提交；SAF 另存为也冻结摘要与大小。条目/规则/PDF 页/资产导入/工作区文件各有专用提案存储，工作区文件审批后还会结算旧的 `WAITING_APPROVAL` 执行；不等于通用文件 CRUD/混合对象事务完成 |
| 技能 UI 与上下文 | `NativeSkillsDialog.kt` → `NativeSkillFilesDialog`；`NativeAppGraph.skillContext` → `WorkspaceSkillStore` | 内置 File/APK assets 来源已补完整资源树与文件读取（原先只暴露 SKILL.md）；脚本读取上限改为与技能文件 256 KiB 上限一致，避免 512 KiB 参数被无条件拒绝。管理弹窗已有资源文件树预览和用户文本资源创建/保存/重命名/删除（SKILL.md 仍走正文编辑器）；通过 ViewModel 获取 skill-write lease，阻止 Agent 执行期间改资源，文件摘要与技能元数据指纹共同校验。writing-guide 主文与两份参考文档、text-preserve 与 glossary 原生脚本已放入 APK；执行工具支持 input_json 对象字符串，保留宿主 revision/item_count 等参数；text-preserve 的完整计数/逐文件分布/零长度命中/UTF-16 范围逻辑来自 LG 原脚本，Node 异步输入改为固定修订 JSONL 同步迭代，结果通过产物发布，glossary 保留 LG Aho 匹配、NFKC/casefold、原文范围、关系与词根逻辑，字素/NFC/NFKC 通过有界 Android ICU/Normalizer 宿主调用，API 35 x86_64 Unicode/实际脚本用例已通过，跨设备 Unicode 版本差异仍待验证；内置包启用/排序偏好独立保存并做指纹 CAS，内容仍只读；管理写操作统一获取技能写占用。资源树新增 scripts/** 的直接执行按钮，原生执行窗口输入 JSON/显式授予网络，ViewModel 以固定工程/Chat/修订/脚本摘要运行，复用持久 Run 和隔离引擎，日志/产物可见，定向停止并等待收尾，不发模型请求；Agent 工具入口同时保留。直接执行由窗口协程持有，离开/重建窗口会取消，尚未提升为可重附着的前台任务。自动注入至多 4 个启用技能、每个读取至多 2048 字符，不是完整自动选技能/自动执行 |
| 格式注册与 PDF | `core/formats/.../FormatAdapters.kt` 的 `NativeFormatAdapters.install()` 由 graph 调用；TXT 由文本 store 负责；`PdfFormatAdapter.kt` / `PdfPageRenderer.kt` | 所列适配器有生产注册，但不代表全格式保真验收。PDF 已有字体候选/字形检查与静态替换排版；旋转文本、表单/附件和版式仍有明确限制，不能把“字体保真未完成”误写成“完全没有字体支持” |
| 规则与质量 | `core/engine/.../TextProofreadingInspector.kt` → `ProjectQualityScanner.kt`，读取 `nameSource/nameTranslation` 并累计 hits | TERM 正文/姓名作用域与正向命中有实际扫描消费者；设备正则、大工程和完整交互仍未验收 |
| 数据版本与迁移 | `core/domain/.../TextProject.kt` 的 schema=8；`NativeAppDatabase.kt` version=18；`NativeTranslationQueueStore.kt` version=2 | HEAD 的 Room 为 v18；v14 条目提案、v15 规则提案、v16 PDF 页提案、v17 profile/资产导入与 v18 补表迁移已纳入 #188 构建，v17→v18 四组合单元测试通过；schemas 目录实际只有 Room `1.json`，2—18 缺失 |

**完成口径**：当前已形成基础产品闭环与上述生产接线；`1d3dd1c` 已完成构建、2268 项单元测试、15 项设备测试、Lint 与 Release。工作区文件 apply、搜索、上传、检查点/恢复及 EPUB 增强已进入该提交。仍缺 MCP 外部配置、模型图片 vision、多对象混合事务、内存预算、完整备份、Room 权威 schema、设备视觉/进程死亡/ARM 多版本与旧主链路退役；非 EPUB/TXT 格式已按用户决定冷冻，不作为完成条件。尚未到“只剩验收”。无法从源码或测试数量推导“96 项中已完成多少项”；S1/S2/S3 和 G1—G5 保持开放；`1d3dd1c` 的绿色只覆盖该提交，不覆盖后续文档提交、设备视觉和真实供应商验收。

用户已授权本轮审查修复与测试修正的提交、推送当前分支并触发 Actions；`057758a`、`8ea0433`、`c40f626`、`1d3dd1c` 已推送，`1d3dd1c` 的 run 40329494778 全绿；`fe77886` 仅文档。合并 `main`、发布与付费调用仍未授权。

## 4. 下一轮顺序

以下为待办，不自动授权实施、提交、推送、CI 或付费调用；按用户当前要求执行。

当前源码增量已覆盖 HANDOFF 记录的可实现缺口；以下只剩外部构建与验收。任何“未构建/未看截图”的结论不能由静态检查替代。

1. **构建与 schema 导出**：由 PC/授权 CI 对当前工作区执行编译、单元测试、Lint、Release，并由 KSP 导出/审阅 Room `2.json`—`20.json`；确认 v18→v19、v19→v20 迁移和新增测试真实通过。当前本机未构建，不得把静态审查当绿测。
2. **设备/视觉验收**：手机/平板、窄屏/宽屏、IME、TalkBack、SAF、通知、锁屏、进程死亡、开机/覆盖安装、JobScheduler/前台服务、U09 执行窗口与混合审批聚合对话框；实际查看截图与语义树。
3. **供应商/网络验收**：真实 OpenAI/Anthropic/Gemini/Sakura、OAuth 浏览器回调与 refresh、MCP server/SSE/私网策略、DuckDuckGo/实网 Fetch、代理远端 DNS/407、vision 图片兼容；需要明确授权与测试账号。
4. **真实格式/数据验收**：EPUB2/EPUB3 真实样本往返与阅读器兼容、TXT 多编码/大文件、旧 LNT 数据库只读导出与 SHA/回滚、完整备份恢复/失败回滚；不迁移旧数据、不删除旧数据。
5. **性能/预算/退役收尾**：ARM/多 Android 版本、QuickJS 模块/异步/内存真实边界、大工程预算与取消；旧代码物理删除只能在只读导出验收通过后进行，当前只做生产入口隔离与架构测试。
6. 非 EPUB/TXT 格式（PDF OCR/版式、RGSS/WOLF、Markdown/字幕/表格/游戏格式）继续冷冻，不纳入本轮实施或验收；保留既有代码与旧数据。

## 5. 高风险与技术决策

[Actions run 40329494778](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40329494778) 对应 `1d3dd1c`：2268 项单元测试 0 失败、app 设备 2/2、workspace 设备 13/13、Lint 与 Release 全部通过；该结论只覆盖 `1d3dd1c`，不覆盖后续文档提交与设备视觉/供应商验收。当前 `.lnt` v8、Room v20、队列 v2、模型 profile、资产导入、工作区材料/产物、网络限流及 U05—U15 页面已有广泛源码/测试，工作区 apply、搜索、上传、检查点和恢复已进入该提交。`MIGRATION_17_18` 四组合自动化已通过，权威 schema 与历史版本真实升级仍缺。隔离服务、死循环超时及主动取消已有 API 35 x86_64 模拟器证据；仍需 ARM 真机、多版本与资源预算验证，QuickJS 0.9.2 API 未提供堆限额/中断处理器。草稿撤销历史仍只在活动 ViewModel 内，不设条数硬截断；进程死亡不恢复撤销栈。重点仍是 Koog 检查点/恢复、工作区权限/取消/回收、一致备份、大工程预算及 EPUB/TXT 真实样本。PDF OCR/版式、RGSS/WOLF 及非 EPUB/TXT 格式已按用户决定冷冻，不纳入本轮完成条件；保留代码和旧数据。Fetch/搜索真实公网行为未验收；代理远端 DNS、取消、重定向、非 UTF-8 站点、HTML 提取及恶意页面仍需独立验证。保持 API 24 支持边界，以真实设备测量冻结预算；未测量不宣称性能提升。

## 6. 历史追溯与维护

[HANDOFF_HISTORY.md](HANDOFF_HISTORY.md) 完整保留精简前内容及 §1—§122，包括初始源码/CI、实施记录、用户授权与暂停、故障修复和当时限制。其他文档引用旧轮次（如 §22、§78、§102—§122）时去归档定位；旧状态不覆盖本文件。

本文件不继续追加逐轮日志。后续完成业务时直接更新 §2—§4，记录实际提交/CI 和未验证边界；只有确需追溯的决定/故障再追加归档，并在相关状态处链接。文档整理不表示产品功能新增。新开对话不要求读取归档，局部任务也无需通读计划。
