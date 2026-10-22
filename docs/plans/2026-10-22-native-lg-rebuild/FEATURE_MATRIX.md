# LG → 原生 Android 功能对齐矩阵

本矩阵定义最终范围，不是实现完成表。实际状态和证据只记录于 [HANDOFF](HANDOFF.md)。任务 ID 在 [实施计划](IMPLEMENTATION_PLAN.md) 定义，UI 路由在 [UI 规格](UI_SPEC.md) 定义。

全部条目均为最终交付要求；阶段先后不等于降低优先级或移出范围。每项以本表 ID 关联测试、设备记录和交付产物。接口与参数不能仅凭下表实现，必须读取对应 LG 生产者、消费者、Schema 和测试。

## 1. 源码依据索引

`LG:` 指 `SOURCE_BASELINE.json` 中的 LinguaGacha-Termux 工作区；`LNT:` 指重构前 LNT。路径是仓库根相对路径，正式实施应保存固定源码快照。

| 来源 ID | LG 路径 / 需要核对的责任 |
|---|---|
| S01 | `src/backend/project/`、`src/backend/database/`、`src/backend/migration/`：工程、资产、事务、修订、复制、旧版本 |
| S02 | `src/domain/item.ts`、`src/domain/setting.ts`、`src/shared/project/`：状态、字段、设置与身份 |
| S03 | `src/backend/agent/agent-service.ts`、`docs/AGENT_RUNTIME.md`：对话、命令、恢复和占用 |
| S04 | `src/backend/agent/agent-input-queue.ts`、`agent-chat*.ts`、`agent-compaction.ts`：输入、分支与上下文 |
| S05 | `src/backend/agent/tools/`、`src/backend/agent/workspace/`：工具、Schema、材料、执行、提交 |
| S06 | `src/backend/agent/agent-skills*.ts`、`agent-skill*.ts`、`builtin/skills/`：动态资源、覆盖、管理与权限 |
| S07 | `src/backend/batch-translation/`、`src/domain/batch-translation.ts`：规划、执行、重试、进度 |
| S08 | `src/shared/text/`、`src/shared/quality/`、`src/backend/quality/`：文本与规则 |
| S09 | `src/backend/proofreading/`、`src/shared/proofreading/`：校对窗口、警告、查询与写入 |
| S10 | `src/domain/model.ts`、`model-agent.ts`、`src/backend/model/`、`src/backend/llm/`：模型、能力与用途 |
| S11 | `src/backend/network/`、`src/backend/auth/`：代理、HTTP 和认证 |
| S12 | `src/backend/agent/web-search-service.ts`、`mcp-web-search-service.ts`、`tools/web-search.ts`、`tools/web-fetch.ts`：搜索与获取 |
| S13 | `src/backend/file/file-format-service.ts`、`src/shared/project-source-formats.ts`、`src/backend/file/`：格式识别、准备与写回 |
| S14 | `src/native/`、`src/shared/pdf*.ts`、`src/backend/file/pdf/`：图片、PDF 提取与输出宿主 |
| S15 | `src/frontend/app/navigation/screen-registry.ts`、`src/frontend/pages/`、`DESIGN.md`、`src/frontend/app/shell/`：导航、页面和视觉 |
| S16 | `src/backend/log/`、`src/shared/log.ts`、`src/backend/api/api-routes.ts`：日志、诊断与公开行为索引 |
| S17 | `src/backend/file/web-file-service.ts`、`src/shared/web-upload.ts`、`src/frontend/features/web-file-picker/`：交付与暂存意图 |
| S18 | `src/backend/runtime-operation-gate.ts`、`docs/BACKEND.md`、`docs/FRONTEND.md`：一致性、占用和恢复 |

`builtin` 属于动态产品资源，不冻结技能名称/数量。S06 验收以本次固定资源清单逐包迁移；机制测试使用自有技能资源。

## 2. 工程与数据（PR）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| PR01 | 从文件/目录建立工程；内容识别与导入预览 | ProjectService；R09 | 多文件、多层目录、取消及解析失败；原稿不丢，名称冲突明确 |
| PR02 | 最近工程、打开/关闭、重命名/移动后的工程身份 | ProjectLibrary；R09/R30 | 冷启动、失效路径、重复导入、外部修改；副本隔离，替换/恢复不继承旧授权 |
| PR03 | 固化原始资产，保留类型、路径、顺序和零条目文件 | ProjectStore；R07/R09 | 移走源文件仍能导出；空字幕与不可译资产仍存在 |
| PR04 | 追加导入、删除、重排与准备/提交分离 | ProjectWriteStore；R08/R09 | 同名/父子路径冲突、半途失败、事务与事件一致 |
| PR05 | Item 正文、完整姓名、状态、过滤跳过与格式私有字段 | domain/store；R07/R08 | 全字段回灌、空译文、姓名数组额外槽位不丢 |
| PR06 | 分区修订、对象指纹与冲突回执 | ProjectWriteStore；R08 | 过时快照拒绝；Agent 部分对象冲突与数据库整批回滚分开 |
| PR07 | 工程/应用设置镜像、对齐预览和确认 | ProjectSettings；R09/R34 | 打开旧工程时预览差异；运行中不偷偷改变已定格配置 |
| PR08 | 从已存原稿全部重置及局部译文重置 | ProjectLifecycle；R09/R13 | 先全部解析再提交；失败不留下半份重建结果 |
| PR09 | 工程副本、手动备份、恢复和一致性交付 | ProjectSnapshot；R04/R35 | 跨库/附件一致切点、manifest/摘要、缺材料失败；恢复新代际与事实核对 |
| PR10 | 历史 `.lg` 导入为 `.lnt` 与 `.lnt`→`.lg` 兼容导出 | CompatibilityStore；R04/R07/R37 | 版本/字段级转换、未知表保留、Zstd、Pi 分支/工具/附件及 LG↔LNT 双向业务回灌；不以改后缀代替转换 |

来源：S01、S02、S18。LNT 旧 Book/Edition 表只能作为 R36 迁移源，不作为上述能力的完成证据。

## 3. Agent（AG）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| AG01 | 工程内 Chat、角色、模型、流式内容、最终回答 | Koog Runtime；R16/R17/R31 | 多轮、工具交错、供应商失败；预览与落库内容不重复 |
| AG02 | 原生工具 Schema、参数校验、错误回执和调用身份 | ToolRegistry；R16 | 非法 JSON、小数/越界整数、重复调用和取消 |
| AG03 | 发送命令去重与受理结果核对 | InputCoordinator；R17 | 回包丢失、重复点击和进程恢复不重复执行 |
| AG04 | 可编辑排队草稿、暂停队列、继续 | InputQueue；R17/R31 | 当前输入入历史后才消费；新草稿不被旧回执清掉 |
| AG05 | 执行中 `steer`，运行阶段与即时输入恢复 | RunCoordinator；R02/R17 | 工具循环中引导实际进入当前执行；停止前未受理可恢复 |
| AG06 | Stop、失败、继续及取消后的真实收尾 | RunCoordinator；R16/R18 | 不再发新请求，脚本/宿主退出后才释放占用 |
| AG07 | 编辑用户/助手历史、分支和重置 | ChatStore；R17 | 活动分支正确；原工程写入不随历史编辑撤销 |
| AG08 | 公开完整时间线与工具详情、原始回执 | ChatProjection；R17/R31 | 压缩前历史仍可回看；UI 不改模型实际收到的内容 |
| AG09 | 自动/手动上下文压缩、截断恢复和预算 | CompactionService；R18 | 成对工具历史、有效摘要落库、压缩取消、恢复额度受限 |
| AG10 | 检查点、重启恢复与不确定副作用核对 | RunRecovery；R02/R18 | 请求/工具各崩溃窗口；不自动重发可能付费操作 |
| AG11 | 用户问题、选择、文本回答、超时与恢复 | DecisionService；R19/R31 | 旋转、离开、超时、断线、重启；问题身份不串会话 |
| AG12 | 手动/自动审批、固定差异、单次允许/拒绝 | ApprovalService；R19 | 不可变正文与同命令绑定；消费后崩溃、部分冲突、停止/恢复不重授；偏好不替当前裁决 |
| AG13 | 文件/图片附件、实际图片处理与已发内容固定 | AttachmentService；R20/R29/R31 | 大图/损坏图/源文件变化/提供方拒绝及附件失效 |
| AG14 | 工程切换、会话恢复与执行身份隔离 | RunCoordinator；R08/R17 | 发送/切工程/Stop 竞争，迟到结果不覆盖新工程 |
| AG15 | Agent 用量、当前上下文、累计消耗和模型配置 | ModelGateway；R11/R18/R31 | 压缩不减少累计；失败/取消缺 usage 明确标为未知 |
| AG16 | Agent 内批量翻译、模型跟随/固定和用户停止决定 | AgentTools/Batch；R13/R16 | 共用引擎和 lease；同轮工具不能擅自恢复用户已停止任务 |

来源：S03、S04、S05、S10、S18。保留 Koog 的旧消息存储不能代替 AG03—AG12 的执行语义。

## 4. 工作区、技能与联网（WS）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| WS01 | datasets、sources、changes、work、uploads、reference | WorkspaceService；R20 | 寿命、刷新、工程/Chat 归属和原稿只读 |
| WS02 | 可编程脚本、模块导入、异步、受控文件与网络 | ScriptRuntime；R03/R20 | 真实材料处理、循环中断、超时、内存/输出预算和拒绝矩阵 |
| WS03 | 预装包、`@lg/*` 和已有脚本能力迁移 | CompatibilityModules；R03/R20/R21 | API 清单逐项验证；全部当前内置脚本完成真实用途 |
| WS04 | 变更文件准备、合法性验证、指纹、批量提交回执 | WorkspaceApply；R08/R19/R20 | 创建/更新/删除/排序、冲突与依赖失败；重试不重复提交 |
| WS05 | stdout/stderr、doing、脚本/产物浏览与保存 | WorkspaceArtifacts；R20/R31/R35 | 超长输出补读、中文名、多文件、保存取消和权限失效 |
| WS06 | 图片输出、PDF 宿主与能力如实声明 | NativeHosts；R03/R29 | 实际图片/PDF 内容与资源收尾；缺能力不声明可用 |
| WS07 | 动态技能目录、显式引用、禁用、隐藏与自动选择 | SkillCatalog；R21 | 选择和文件权限分离；测试自有资源而非固定内置名称 |
| WS08 | 用户覆盖、重排、文件树编辑、重命名/删除与冲突 | SkillEditor；R21/R33 | 同名回退、资源路径、并发保存、执行期间修改互斥 |
| WS09 | 人格/系统指令编辑、运行环境与 Schema 文档生成 | Prompt/Contract；R16/R21/R33 | 工具、类型、说明一致；旧指令不会虚构 Node 能力 |
| WS10 | DeepSeek 优先搜索/Fetch、五家 MCP 备用 | SearchService；R22 | 首选、空结果、逐来源失败/超时、取消、长度预算与脱敏 |

来源：S05、S06、S12、S14。文本模型能回答问题不构成 WS02 或 WS10 的证据。

## 5. 翻译引擎（TR）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| TR01 | new / continue / reset、范围和目标快照 | BatchService；R13 | 选定文件/状态、续跑与重置差异，无额外目标 |
| TR02 | 原稿资格、规则/语言过滤、手动放行 | TranslationPolicy；R12 | RULE_SKIPPED/LANGUAGE_SKIPPED/EXCLUDED 等状态正确 |
| TR03 | 同文件同文本去重、继承与被动协调 | Deduplication；R08/R12 | 文件内/跨文件差异、修改后状态/统计/事件同步 |
| TR04 | token/行数预算、同文件规划与前文截取 | Planner；R12 | 精确/估算能力标记、边界和预算溢出，不误跨文件 |
| TR05 | 源/目标语言、ruby、MTool 和重复来源配置 | TextPolicy；R12/R34 | 每个开关有真实数据路径和前后效果样例 |
| TR06 | 姓名/正文、JSONLINE 等实际输出契约 | TranslationProtocol；R12/R13 | 重排、缺行、坏 JSON、姓名数组与空输出 |
| TR07 | 保护、替换、术语、前后文及提示词组装 | TextPipeline；R12/R14 | 原生与 LG 黄金样例输入/输出及边界一致 |
| TR08 | 有界并发、RPM、密钥池、冷却与自动调整 | RequestScheduler；R11/R13 | 多任务共用额度、429/Retry-After、密钥恢复 |
| TR09 | 请求故障与内容重试分离、对半切及逐条收敛 | RetryPlanner；R12/R13 | 不重复已成功条目，永久错误不盲重试，预算可审计 |
| TR10 | 分批有效提交、取消保留、completion 等待 | BatchRuntime；R08/R13 | 最后一批失败/Stop/关闭服务；终态与数据库一致 |
| TR11 | 本轮进度、工程累计和工程完成率分开 | ProgressStore；R13/R32 | 局部重翻、重开、重置、PDF 与文本口径一致 |
| TR12 | requested/running/stopping/终态及崩溃恢复 | RuntimeService；R06/R13 | 受理后 UI 离开、进程死亡、用户主动继续和错误反馈 |

来源：S02、S07、S08、S10、S18。原 LNT 的章节并行、META 和 AI_POLISH 不是新引擎默认协议。

## 6. 规则、提示词与校对（QL）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| QL01 | 术语 CRUD、姓名/正文作用、备注、启用和排序 | RuleService；R14 | 与 Agent/翻译/校对共享匹配结果 |
| QL02 | 文本保护模式、自定义正则、按格式预设 | PreserveService；R12/R14 | Unicode、重叠、API 24/34+ 真机、非法模式定位 |
| QL03 | 译前/译后替换、字面/正则、大小写与恢复 | ReplacementService；R12/R14 | 反向引用、命名分组、资源标记、顺序和空值 |
| QL04 | 规则导入预览、重复分类、skip/overwrite、导出 | RuleExchange；R14/R33 | LG 实际 JSON/表格契约；取消不写入，不虚构伴随文件 |
| QL05 | 预设列表、读取/保存/改名/删除和默认预设 | PresetStore；R14/R33 | 应用预设与工程事实分离，失败补偿与版本冲突 |
| QL06 | 自定义提示词、启用、模板/风格、增强开关 | PromptService；R14/R33 | 当前工程覆盖、生效模型快照与语言模板正确 |
| QL07 | 校对列表、文件/状态/警告/术语筛选与统计 | ProofreadingQuery；R15/R32 | 搜索窗口稳定，正文编辑不重排，结构变化窗口失效 |
| QL08 | 六类真实警告及无警告、姓名/正文证据 | ProofreadingEvaluator；R12/R15 | 外文残留/相似度/行数/术语/保护/标点的内容证据 |
| QL09 | 手工编辑、批量状态、清空、查找替换与预览 | ProofreadingCommands；R08/R15 | 选区与全范围区分，过时修订拒绝，事务/回滚可见 |
| QL10 | 命中统计、规则定位、重载与大列表 | QualityStatistics；R15/R33 | 同语义计数、取消/刷新保留内容、增量变更一致 |

来源：S08、S09、S18；警告口径读取 `src/shared/proofreading/proofreading-types.ts`，不能复用 LNT 原 QA 名称代替。

## 7. 模型、认证与设置（MO）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| MO01 | 预设、自定义模型、复制、编辑、分类与排序 | ModelService；R10/R34 | 身份稳定、复制新 ID、固定预设规则与缺配置恢复 |
| MO02 | OpenAI Chat、Responses、Google、Anthropic、SakuraLLM | ProtocolAdapters；R10 | 各协议非流式/流式/工具/错误契约与独立端点 |
| MO03 | API Key、多密钥、禁用/恢复、供应商额度 | CredentialPool；R10/R11 | 不同失败分类，不串模型配置，不在日志泄露 |
| MO04 | ChatGPT 等 LG 已有 OAuth、刷新与退出 | AuthService；R10 | 登录/取消/重启/回调/刷新失败；登录与模型测试分离 |
| MO05 | 目录发现、离线基线、更新覆盖与自定义保留 | ModelCatalog；R10 | 有效空目录撤回旧覆盖；空闲应用，不改在途请求 |
| MO06 | translation/agent/Agent 批翻跟随或固定 | ModelSelection；R10/R16 | 改选不清历史；下一轮/本轮快照语义明确 |
| MO07 | 思考 DEFAULT/OFF/等级、服务档位与生成参数 | Capabilities；R10 | 不支持选项有依据；默认不被伪装成 OFF |
| MO08 | 上下文/输出/RPM/并发、extra headers/body | RequestConfig；R10/R11 | 验证与实际请求一致，凭据字段脱敏，覆盖规则明确 |
| MO09 | 连通性测试、测试取消与操作互斥 | ModelProbe；R10/R34 | 真请求须单独授权；假服务可覆盖所有失败/取消 |
| MO10 | token/cache/reasoning/output、费用和来源 | UsageLedger；R11/R18 | 取消、缺 usage、缓存读写、实际/估算/未知分开 |
| MO11 | 代理、超时、错误详情、回环直连与认证回调 | NetworkPort；R10/R22/R34 | 代理失败不静默直连；移动网络切换、流中断可恢复 |

来源：S10、S11、S12。Koog 内置供应商列表不能替代 LG 的协议和模型配置契约。

## 8. 文件格式（FM）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| FM01 | TXT 编码、换行、原译双语和空白 | TxtAdapter；R23 | 多编码/空文件/超长行/原译相同去重，重读核对 |
| FM02 | Markdown 结构、标记、代码和写回定位 | MarkdownAdapter；R23 | 标题/列表/链接/围栏/正文对应位置，结构不损 |
| FM03 | EPUB 解析、图片/资源、双语和容器写回 | EpubAdapter；R05/R24 | 原 CSS/资源/顺序/目录、ruby 配置和再次读取 |
| FM04 | SRT/VTT/LRC | SubtitleAdapters；R25 | 时间轴、标记、空字幕和双语结果可重新解析 |
| FM05 | ASS/SSA | SubtitleAdapters；R25 | 样式/说话人/逗号/控制标签与事件字段不损 |
| FM06 | KVJSON/MESSAGEJSON 与格式识别 | JsonAdapters；R26 | 原始数值和非翻译字段保留，源内译文与重置正确 |
| FM07 | XLSX/WOLFXLSX | SpreadsheetAdapters；R26 | 多工作表、公式/样式/定位与实际支持范围和 LG 一致 |
| FM08 | TRANS/WOLF TRANS 与容器关系 | TransAdapters；R26 | 原私有字段/路径、译文继承、导出回灌 |
| FM09 | RenPy | RenpyAdapter；R26 | 脚本语法、转义、多行与字符串位置，不破坏程序 |
| FM10 | RPG Maker MV/MZ | GameAdapter；R05/R27 | 目录/容器、JSON/JS、引擎过滤、准备恢复和媒体边界 |
| FM11 | RPG Maker RGSS | RgssAdapter；R05/R28 | 归档/Marshal/Ruby 字符串，服务级导入→译文→写回→重读 |
| FM12 | PDF 提取、页处置、预览、排版、合并和输出 | PdfAdapter/Host；R05/R29 | 文字/图像/字体/坐标、keep/omit/空translate、跨页排版及实际视觉 |

来源：S13、S14、S02。游戏导出应核对目标数据结构，Android 不承担 Windows 游戏启动。RGSS 的旧验收空缺不作为省略理由。所有格式均要求失败/取消、恶意路径、压缩展开预算与零条目行为。

## 9. UI 与原生交互（UX）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| UX01 | 工程首页、打开默认 Agent、完整导航 | AppShell；R30 | 手机/平板均能访问全部页面，无旧书架主导航 |
| UX02 | LG 的 Agent 资料/文件/工具/审批/输入工作流 | AgentScreen；R31 | 长对话、IME、附件、侧栏和审批全过程视觉/交互 |
| UX03 | 工作台文件表、翻译菜单、统计与输出清单 | Workbench；R32 | 大列表、批选、重试状态和输出定位 |
| UX04 | 完整校对台的原生表格与条目编辑 | Proofreading；R32 | 手机双栏替代、宽屏表格、选区、查找替换与键盘 |
| UX05 | 规则、提示词、技能、人格编辑器 | Editors；R33 | 文件树、脏草稿、冲突、预设和手机可达操作 |
| UX06 | 模型、基础/专家/实验室设置、日志与诊断 | Settings；R34 | 每个开关实际接线、详情延迟加载和错误恢复 |
| UX07 | 原生文件/目录选择、保存、分享与权限恢复 | PlatformFiles；R35 | 本地/云 SAF、中文名、多文件目录、取消、空间不足 |
| UX08 | LG 视觉语言、五语、深浅色和可访问性 | DesignSystem；R30/R37 | 窄/宽屏截图实看、字体放大、TalkBack、48dp 热区 |

来源：S15、S17。替代交互应保留结果；不能因为手机表格放不下就删字段/批量操作。

## 10. 运维、恢复与交付（OP）

| ID | 完整能力 | 原生责任 / 任务 | 最低验收证据 |
|---|---|---|---|
| OP01 | 单一运行占用、关闭与清理顺序 | RuntimeCoordinator；R08/R06 | 任务/写入/技能/认证竞争、迟到释放与资源收尾 |
| OP02 | 长任务、前台通知、用户停止与后台恢复 | RuntimeService；R06/R13 | 活动/持久等待分离、审批长等待、锁屏、通知拒绝、超时/强停恢复，不自动重发 |
| OP03 | 文件预算、在途预约、暂存回收和失败补偿 | FileStore；R06/R35 | 并发超量、取消、删除失败、重启孤儿材料 |
| OP04 | 操作日志、按页查询、详情、脱敏与导出 | LogService；R34 | 文件轮转、外部修改导致游标失效、敏感字段不泄露 |
| OP05 | 错误分类、已提交同步失败、自然恢复入口 | ErrorContract；R08/R37 | 唯一通知归属、重载不重复提交、保留可见内容 |
| OP06 | 旧 LNT 数据迁移与档案交付 | LegacyMigration；R36 | R07 前字段样本；多 Edition/修订/缺文件/无归属会话映射，断点恢复与源数据不变 |
| OP07 | 升级、工程/应用备份恢复和回退边界 | UpgradeService；R35/R36/R39 | 签名升级、迁移失败、兼容版本回退，不越版本盲降级 |
| OP08 | 旧实现/资源/依赖/测试清理 | RemovalAudit；R38 | 新主链路无旧业务依赖，仅保留必要数据读取器 |
| OP09 | 签名 APK、可重现资源包、版本与更新入口 | Release；R39 | test/lint/release、签名、安装升级、设备验收和完整矩阵 |

来源：S01、S16、S17、S18。LG 没有自动整包备份的事实不应被写成既有能力；本计划要求工程副本及可恢复升级材料，不额外把周期自动备份设为对齐门槛。

## 11. 状态与完整性规则

98 项功能是本轮规划的可追踪分组，不是对 LG 细节数量的永久上限。R00 根据接口、设置字段、所有页面可见操作、技能包和格式处理器补齐细项；发现遗漏必须补入，不以“表里没有”拒绝既定完整范围。

实施计划 §4.1—4.2 的子产物与 S1/S2 切片用于提前验证直接依赖，不改变本表的整项完成标准。源码发现覆盖、原型运行、生产接线和设备验收分别登记；98 个 ID 目前都有可追踪证据（75 条发现条目、20 条已审阅源码绑定、3 条原生项说明，见 [功能覆盖报告](FEATURE_COVERAGE.json)），这**不表示任何一项功能已完成**。

每项完成至少具备：

1. 明确生产实现和真实消费者；只有类/Schema/控件存在不算接线。
2. 覆盖成功、取消和其真实边界的行为证据。
3. 涉及系统交互、视觉或 native 依赖时有设备/实际输出证据。
4. 与相关 LG 黄金夹具对照，解释有意的 Android 替代行为。
5. 在 HANDOFF 记录提交、命令/环境、结果、产物与未验证范围。

“假模型闭环通过”“真供应商有界样例通过”“长任务通过”“真机通过”分别记录，不能互相替代。
