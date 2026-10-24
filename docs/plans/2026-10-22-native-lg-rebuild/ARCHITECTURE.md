# 原生 LG 目标架构

当前进度与验证边界统一见 [HANDOFF §3](HANDOFF.md)。提交 `77daf74` 的 [Actions #158 / 40085555783](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40085555783) 已通过 test/lint/release 和签名校验，覆盖至 §101；§102—122 已提交为 `b47178a`，Actions run 40132451221 的 test/lint/release 与签名校验成功。新增设备/视觉、真实供应商与 Room 2—13 schema 导出仍待补齐。

当前实现的构建/测试证据见 [HANDOFF §3](HANDOFF.md)。新 Agent/模拟切片已通过 Actions #158；本文目标中的后台/完整恢复/全格式等未实现能力不因 CI 全绿自动完成。

当前接线边界：MainActivity → NativeProjectApp → NativeProjectViewModel → NativeAppGraph 装配的工程存储、运行协调器、统一模型网关及 Koog 对话。工程修改经 ProjectWriteStore；本地模拟与远程 TXT 批翻经 NativeModelGateway → TextTranslationService → TranslationCommitPort；翻译任务队列经私有 AtomicFile 与 TranslationQueueCoordinator 冻结 spec 后复用前台执行器；Agent 对话经 NativeKoogConversation → AgentRequestBoundary → 共享网关，并由 NativeAgentStore 保存请求/响应与审批记录，回合前按阈值执行一次受审计的自动摘要压缩。多资产导入导出、规则与校对、远程翻译、Koog 对话/SSE/单条审批已有 #158 CI；当前 `.lnt` v5、Room v13、全查询批写、预设/模板库、协议/多密钥、备份、RGSS、PDF 与工作区核心为工作区增量，详见 [HANDOFF §3](HANDOFF.md)。完整 Agent 应用级执行、跨进程接续、工作区生产接线和全格式仍未完成；真实供应商与新增设备/视觉验收仍缺，TXT 手工实机确认仅限 §22。旧 AppViewModel、旧 Koog 适配和旧翻译引擎不在新启动链上；下文完整服务模型属于目标设计，应按 HANDOFF 核对实际实现。

这是拟实施设计，现状与授权见 [总入口](README.md)。功能验收以 [功能矩阵](FEATURE_MATRIX.md) 为准，实施依赖见 [任务计划](IMPLEMENTATION_PLAN.md)。

## 1. 架构选择与依赖

使用 Kotlin / Compose 原生应用。Koog 沿用 LNT 1.3.0 接入框架，在真实 Android 原型中核对依赖和 API；不以最新网站示例直接替换锁定版本。业务不依赖 Koog 的具体会话类型、Android ViewModel 或某一家模型 SDK。

`minSdk 24`、`targetSdk 36`、Java 17 为起始约束。现有 Jackson 排除只说明旧实现不用 Koog 检查点，不能原样保留后宣称新持久化可用。`R02` 比较自定义存储/序列化与必要依赖，验证 D8、最低 API 和恢复行为；若最低版本必须提高，先记录失去的设备范围与迁移影响，再更新全部构建和验收矩阵。

逻辑边界如下。`R01` 先建立 7 个 Gradle 模块，细分目录不等于要求立即建立十余个模块。

| 初始模块 | 内部职责 | 允许依赖 |
|---|---|---|
| `:app` | Application、Activity、Compose 页面/导航、应用装配、Service 声明 | 下列模块 |
| `:core:domain` | 值对象、命令/回执、状态、窄接口、纯业务规则 | Kotlin / 序列化基础 |
| `:core:engine` | 工程、翻译、校对、规则、模型调度应用服务 | domain |
| `:core:agent` | 保留改造的 Koog 框架、策略、工具适配、会话协调 | domain、Koog；通过端口调用 engine |
| `:core:storage` | SQLite `.lnt`、Room 应用记录、文件、凭据、迁移适配 | domain、Android IO |
| `:core:formats` | 解析/写回注册表、文档/游戏引擎及输出宿主适配 | domain、受审查原生库 |
| `:core:workspace` | 脚本引擎、模块解析、能力桥和隔离执行端口 | domain、受审查脚本引擎 |

模块公开签名直接使用 domain 类型，因此 domain 依赖以 `api` 暴露，否则消费者拿不到 `RunIdentity`、`AgentMessage` 等类型。
engine 不直接依赖 storage/workspace 实现：跨模块能力经 domain 端口在 app 装配时注入，避免依赖环，也让原型测试能替换假实现。

模型网络传输先在 engine 的独立包中实现；隔离脚本 Service 由 app 装配，执行器不依赖 UI。WorkspaceService 的工程协调在 engine，脚本机制在 workspace，通过 domain 端口调用格式/文件能力，不让 workspace 与 formats 互相依赖。将来构建时间或依赖图证明需要时再拆模块。禁止 engine 依赖 app，禁止 Agent 工具通过 `AppViewModel` 执行业务，禁止 formats 自行开工程数据库连接。

```mermaid
flowchart TD
    UI[Compose 与页面 ViewModel] --> App[应用服务]
    Service[RuntimeService] --> App
    App --> Project[ProjectService / ProjectWriteStore]
    App --> Agent[Koog Agent Runtime]
    Agent --> Tools[受类型约束的工具适配]
    Tools --> Project
    Tools --> Batch[BatchTranslationService]
    Tools --> Workspace[WorkspaceService]
    App --> Batch
    Batch --> Models[ModelGateway / RequestScheduler]
    Agent --> Models
    Project --> ProjectDb[ProjectStore: 私有 .lnt]
    App --> RuntimeDb[AppStore: Room]
    Project --> Formats[格式注册表]
    Workspace --> Script[隔离脚本执行器]
    Script --> Host[受控文件/网络/文档宿主]
```

## 2. 核心领域模型

| 对象 | 身份与职责 | 持久化位置 |
|---|---|---|
| `Project` | 工程身份、设置镜像、分区修订与资产集合 | 活动 `.lnt` |
| `Asset` | 原始字节、类型、路径身份、排序；零条目文件也必须保留 | `.lnt` |
| `Item` | `itemId`、源文/译文、姓名完整字段、状态、原始定位、格式私有数据 | `.lnt` |
| `PDFDocument / PDFPage` | 原页身份及 `null / keep / omit / translate` 的不同决定 | `.lnt` |
| `RuleSet / RuleEntry` | 术语、保护、译前、译后规则及启用/排序/修订 | `.lnt`；预设在应用存储 |
| `PromptConfig` | 模板/用户覆盖/预设及版本冲突依据 | 工程与应用按 LG 归属分别保存 |
| `AgentChat` | 工程内产品对话、活动分支、消息身份和附件引用 | 可交换历史在工程；执行辅助状态在 AppStore |
| `AgentRun / InputCommand` | 回合、输入尝试、命令去重、配置快照和运行终态 | AppStore |
| `ApprovalRequest` | 固定差异、工程/会话身份、对象指纹、裁决和消费记录 | AppStore；提交回执关联工程 |
| `TranslationRun / RequestAttempt` | 本轮目标、进度、请求用量、恢复状态、取消来源 | 已提交事实在工程；运行审计在 AppStore |
| `Workspace` | sources、datasets、changes、work、uploads、reference、版本化工具文档 | 私有文件目录，身份关联 Project / Chat |
| `ModelConfig / Credential` | 协议、能力、阈值、用途和认证；密钥独立 | 应用配置 / 凭据存储 |

旧 `Book / Chapter / Segment / Edition` 不进入新业务模型；它们只出现在一次性旧数据读取器中。不要用“把每个字幕块叫 Segment”继续隐藏旧模型依赖。文件与 Item 身份稳定，导出不依赖列表序号或正文相等定位。

状态必须有唯一拥有者：

- 工程数据库拥有原稿、译文、修订与累计统计。
- Runtime 拥有当前工程、租约、活动回合和执行任务。
- 应用数据库拥有命令、审批、输入队列、执行回执与恢复索引。
- 页面 ViewModel 拥有筛选、滚动、选区、未提交草稿；跨页面草稿按 Project/Chat 身份进入独立草稿存储。
- 缓存只投影已提交事实；不得作为第二个工程数据库或从 UI 回写整个快照。

## 3. 原生 `.lnt` 存储与备份恢复

### 3.1 选择

LNT 原生工程统一使用 `.lnt`，活动工程以应用私有目录中的 `.lnt` 为工程事实。底层继续采用专用 SQLite 适配器，但 schema 版本、格式标识与迁移由 LNT 独立管理，不要求活动工程直接沿用 LG 的表结构。Room 只管理新应用记录；旧 Room v6 数据库独立保留，当前不要求旧 LNT 数据迁移（见 §12）。

原生工程格式独立于 LG；不实现外部工程交换或 Pi 会话历史转换。首个 TXT 切片采用 `raw-v1` 无损原稿分块（不压缩），后续 codec 变更须版本化并保留完整原稿。读取时必须校验格式标识、版本和结构，不以扩展名代替验证。

| 文件类型 | 用途 | 验证边界 |
|---|---|---|
| `.lnt` | 原生工程的创建、打开、保存与工程文件交付 | LNT 格式标识、版本迁移、工程事实与重开；不等于包含全部 AppStore 的恢复备份 |
| 完整恢复备份（扩展名待定） | `.lnt` 一致快照及关联运行记录、正文与材料 | 按 §3.5 验证一致切点与恢复；不能冒充普通工程文件 |

工程导入：SAF 字节流 → 暂存 → 格式身份/版本/结构/大小/压缩预算验证 → `.lnt` 验证 → 私有 `.lnt` 活动副本 → 索引发布。导出：数据库一致快照 → 原生 `.lnt` 校验 → SAF 写入 → 关闭流 → 回报交付结果。正在访问 WAL 的数据库不得直接复制主文件。

源文件可能来自云文档提供者，不假定存在真实路径，不直接在 SAF URI 上运行 SQLite。目录权限失效时保留私有工程并提供重新授权或另存。

### 3.2 原生工程边界

仅支持 LNT 原生工程与自身备份；PR10 已取消，不设置 LG 数据库/Zstd 版本夹具、跨框架历史映射或双向回灌门槛。Koog 自身的历史、检查点和任务恢复继续由 R02/R17/R18 实现与验收。

### 3.3 多存储提交

工程提交和 Room 回执不共享一个物理事务。用稳定 `commandId / changeId` 与工程内提交回执协调：

1. AppStore 写准备记录，包含目标工程、意图摘要与内容摘要。
2. 同一工程事务检查去重回执、分区修订/对象指纹，应用修改并保存提交回执。
3. 提交成功后更新缓存并发布带修订的变化。
4. AppStore 补记已提交结果；该步失败时重开从工程回执核对，不能重新提交。

缓存/通知失败属于“已经提交、需要重载”，不是普通写入失败。不要先发“完成”再等待数据库。

审批命令的准备记录还必须包含不可变变更正文引用、授权消费绑定和恢复结果；细节以 [APPROVAL_CONTRACT](APPROVAL_CONTRACT.md) §5 为准。上述审批提交步骤是目标协议，尚无生产审批跨库事务适配器。首个手工 TXT 切片已有单工程事务与回执，边界见 [NATIVE_STORAGE](NATIVE_STORAGE.md)；不把它视为审批生产接线。

### 3.4 工程身份、外部来源与恢复代际（部分字段已落地）

至少区分以下概念；完整字段与原生存储落点由 R04/R07 验证；现有 TXT v1 仅实现其中的工程身份、Room 本地实例和初始代际，不能把本表视为全部现有 schema：

| 身份 | 语义与边界 |
|---|---|
| 可交换工程身份 | 表示工程来源/谱系，随交换文件保留；旧文件没有可靠身份时登记来源并生成映射，不以文件名猜测 |
| 本地实例身份 | 私有活动副本的主键；两个副本即使具有相同来源身份，也有不同实例，不共享审批/运行记录 |
| 恢复代际 | 覆盖恢复或替换活动副本时生成新的不可复用值，拒绝旧代际命令、回调、授权和缓存；不能从备份还原旧值 |
| 内容修订 | 工程分区/对象的已提交版本，用于冲突检测；不替代本地实例或恢复代际 |
| 外部来源登记 | URI/授权、最近导入/导出摘要与原生格式版本；只作来源关联，不作为工程主键 |

AppStore 关联、缓存、草稿、工作区与可执行命令必须能定位本地实例和代际；现有 `ProjectId/ChatId/RunId` 尚未表达完整恢复边界。`domain/Identity.kt` 已加入 `ProjectInstanceId` 与 `RecoveryGeneration` 值类型，`ApprovalCommandBinding` 已要求二者参与绑定；TXT 切片已在独立 Room v1 中保存本地实例/初始代际，在原生工程 v1 中保存来源工程身份、修订与按实例/代际绑定的手工回执；完整对象、覆盖恢复与代际推进仍待 R07/R08 实现。导入文件里的 ID 和命令记录都是外部数据，不自带本机执行权限。

| 用户动作 | 目标行为 |
|---|---|
| 从最近工程重开、外部文件改名/移动 | 打开已登记私有实例；重新关联来源不改变实例，不自动重建 Chat |
| 再次选择已有来源且内容未变 | 允许打开已有实例或显式导入副本，不静默产生重复工程 |
| 同一外部来源内容已修改，或同 ID 文件内容不同 | 展示“保留本地/作为副本/替换本地”的明确选择；不隐式合并。替换须先停止并核对运行、保护旧副本、暂存验证，再发布新代际 |
| 复制工程 | 新本地实例；原稿与可回看的历史可复制，运行/审批仅保留历史事实，不继承可执行授权。来源谱系和 Chat 映射随副本登记 |
| 恢复旧备份 | 默认作为新实例；显式覆盖时保留旧副本并生成新代际，旧 Run/待决授权不自动继续 |

私有副本不会因外部文件变化自动更新。重选/重新导入或导出覆盖前比较来源摘要；无法读取来源时保留私有工程并要求重新授权/另存，不依赖路径或修改时间作为唯一证据。覆盖导出发生冲突时不静默替换外部新内容。

### 3.5 工程交换、单工程备份与完整备份

| 交付类型 | 必须包含 | 恢复/继续边界 |
|---|---|---|
| 原生 `.lnt` 工程 | LNT 工程事实、原稿、修订、产品对话历史与必要工程附件 | 使用一致快照；外部导入后新建本地实例/代际，不携带可执行授权；不替代完整备份 |
| 单工程 ZIP 备份（§70 已实现） | `manifest.json` + `project.lnt`；版本、名称、来源身份、修订、字节数、SHA-256 与时间 | 恢复只接受精确条目并校验摘要，创建新实例；不覆盖现有工程，不恢复执行授权；不含凭据、Agent 会话、模型设置或模板库 |
| 完整恢复备份（R35 目标） | 一致 `.lnt` 快照、相关 AppStore 记录、不可变变更正文、历史附件和不可再生工作区材料；版本化 manifest、逐文件摘要、实例/代际与切点 | 可重建事实和核对不确定操作；导入后新建实例/代际，旧审批只作审计，继续操作重新取得授权 |

完整备份由 R35 编排，R07/R08 提供一致切点：暂阻新写入（包括 Chat/队列/审批/用量记录及工作区材料），等待实际写入者收尾，核对跨库未决提交；对不确定模型请求只记录状态，不重发。记录同一快照 ID 与工程/应用修订，固定附件引用集合并保护材料不被 GC，再生成数据库一致副本与文件清单。可变 work/uploads 必须在此期间固化为快照文件，仅持引用租约不能阻止内容被改写。若不能取得一致切点，返回可重试失败，不把时间上相邻的两份数据库复制品当作一致备份。§70 只实现单工程 ZIP，不满足本节完整应用级备份目标。

快照取得后可释放业务门禁，复制期间保留文件引用租约；缺失文件导致完整备份失败，缓存可按清单标为可再生并排除。保存到 SAF 失败保留本地已校验产物；发布后才标记交付成功。恢复验证 manifest、版本、摘要、路径与预算，staging 全部通过后发布，失败不改当前实例。

原生 `.lnt` 与完整备份默认均不导出 API Key、OAuth token 或设备绑定密钥；备份清单标明需重新认证。凭据引用不能被当成已恢复可用凭据。完整备份的封装与扩展名由 R04/R35 原型定型。

### 3.6 Koog 原生历史与恢复证据

R02/R17/R18 验证原生 Chat/分支、消息与工具结果配对、摘要切点、附件、用量和检查点；重启先核对副作用回执，不自动重发模型请求或重复写入。外部 Pi 历史映射已取消，不作为这些原生能力的前提。

## 4. 唯一写入口、互斥与事件

`ProjectWriteStore` 处理 Item、PDF、规则、提示词、被动去重协调、修订与统计。手动编辑、Agent `workspace_apply` 和批量翻译走同一入口。工程打开/关闭/重置、技能修改、模型认证与任务启动的占用矩阵按 LG `RuntimeOperationGate` 逐项还原。

`RuntimeCoordinator` 同步预留工程与回合身份，成功预留后才启动协程；发送、切工程、切分支和 Stop 操作核对同一个执行实例。旧执行的 finally 只能释放自己的 lease，不得释放新执行。

短临界区顺序：

1. 协调器锁内核对身份、命令去重和占用并登记执行；立即释放锁。
2. 网络、等待用户、脚本和宿主请求在锁外执行。
3. 提交前取得允许的写租约，再进入工程短事务。
4. 实际请求、脚本与宿主收尾后结束执行并释放运行占用。

会话存储队列与工程事务有固定先后顺序；不得持数据库事务等待模型、审批或 UI。SDK 回调不能反向等待已经持有自己执行权的队列。用竞争测试证明顺序，而不是注释宣称“线程安全”。

应用服务发布不可变 `ProjectChange`、`RuntimeSnapshot` 和 `ChatSnapshot`；包含 Project/Chat/Run 身份与递增修订。UI 从中心 StateFlow 读取一致快照，命令回包只指向同一修订，不自行维护另一套列表。迟到结果按身份丢弃，内容刷新失败保留最后成功显示。

## 5. Koog 框架改造

保留代码范围见总入口。先新增 `AgentModelPort`、`AgentToolPort`、`AgentPersistencePort`、`AgentEventSink`，使 Koog 文件不再依赖旧 Entity、ViewModel、LlmClient 或书籍工具。

### 5.1 模型消息与工具

- 主路径保存 typed user / assistant / tool-call / tool-result，保留调用 ID、供应商关联、图片和需要回传的推理字段。
- `PromptExecutor` 将 Koog 消息适配新模型网关；不用“展平成 system+user”作为所有供应商的固定历史格式。
- 原生工具按 Schema 注册并统一窄化参数。字符串、JSON 数值和整数边界逐项验证，禁止先 `toInt()` 再检查。
- 文本协议仅用于确有 LG 支持需求、且该模型不支持原生工具的能力分支；配置与回执明确，不因任意 HTTP 400 就自动重放付费请求。
- 不保留“同一工具同一参数永远第二次拒绝”的粗糙熔断。只读查询可在数据变化后重复；有副作用操作靠调用身份和持久化回执去重，循环上限与预算单独控制。

### 5.2 对话、输入与分支

一个活动工程对应一个产品 Chat，历史可分支。持久化 `messageId / branchId / inputCommandId / inputAttemptId / runId`。修改历史改变后续模型上下文，不撤销已发生的工程写入。

队列状态区分 draft / queued / sending / accepted / paused / failed。传输或 UI 重试沿用命令身份；用户重新发送产生新的尝试。草稿只在真实入历史后消费，停止前未受理的输入退回草稿。`steer` 的接收点、插入位置及 Koog 可执行阶段由 R02 原型确定，不能把“等待本轮结束后排队”标为执行中引导。

### 5.3 压缩与恢复

产品公开时间线保留压缩前内容；模型上下文保存已提交摘要、切点及成对工具历史。预算按模型上下文、最大输出和压缩预留计算；累计消耗不随压缩减少。

检查点包含策略版本、节点/阶段、活动分支、模型快照、已受理输入、工具调用回执、摘要和运行状态。持久化数据可迁移，禁止保存不可序列化的 Coroutine、Bitmap 或打开的句柄。

恢复先核对工程副作用与模型回执。遇到“请求已发出但无结果”“工具可能已提交”等不确定状态，显示中断并要求明确继续，不自动重发。Koog 状态回滚不回滚已经提交的工程事实。

## 6. 审批与用户问题

`ask_user` 是普通问答；`ApprovalService` 才拥有写入授权。手动/自动偏好是持久化配置，当前审批展示后沿用原裁决，不因切换偏好自动通过。

准备变更时保存不可变 `PreparedChangeSet`：工程/会话身份、涉及对象、指纹、差异摘要和内容摘要。显示内容与最终提交使用同一个对象；提交前重新核对身份和冲突。`allow_once` 只能消费一次，不授权未来修改。

拒绝、取消、超时、断线、旋转、进程重启和工程切换都有明确状态迁移；旋转或暂时离开界面本身不结算审批。等待期间不持数据库锁，恢复审批不能绑定到新 Chat/实例/代际。普通问答可配置的自动选择计时只累计前台可操作时长；写审批不自动倒计时批准，待决审批的可选绝对截止时间在后台不延长，到期不能再批准。已批准后的执行资格另查 Run 与命令绑定。审批消费与工程提交通过稳定回执协调，详见 APPROVAL_CONTRACT §5。

## 7. 可编程工作区与动态技能

### 7.1 执行模型

选择“嵌入 JavaScript 引擎 + Kotlin 能力桥”，不运行 Node 业务服务。QuickJS 是 R03 首个评估对象；若异步/模块/WASM/资源限制不满足，则评估其他 Android 嵌入引擎，记录包体、ABI、性能和维护成本再定型。支持脚本是完整版本门槛，不允许退化成有限按钮式 Agent。

脚本执行放隔离 Service 候选中，R03 验证 `isolatedProcess` 的可用 API、生命周期与加载边界。普通 `android:process` 只提供进程隔离，不构成文件权限边界。宿主仅传允许材料的只读描述符/受控句柄；大数据走文件描述符或流，不能塞入 Binder 大消息。

不暴露脚本引擎默认的任意文件、进程和网络 API。文件访问通过规范化路径、句柄与能力令牌；保护 sources、快照、内置技能和工程数据库。用户技能写入按真实目录权限校验。网络走同一代理策略和取消边界，回环请求保持直连。

执行有时间、内存、输出、图片数量和文件预算。取消/超时触发引擎中断，宿主请求和流收尾；未确认旧执行结束前不释放写入占用或允许下一执行接管。引擎不能中断的情况必须在原型中暴露，不以取消 UI 遮盖仍在运行的脚本。

### 7.2 能力兼容

| LG 能力 | 原生责任 |
|---|---|
| `ws.contract` / datasets / reference | 同一 Schema 生成校验器、Kotlin 类型、脚本声明与模型文档 |
| `node:fs/promises`、Buffer、路径、文本编码等已使用接口 | 建立明确兼容子集与测试；不声称完整 Node API |
| ZIP、表格、Markdown、编码、PDF、JS/Ruby 解析 | 锁定资源包；可移植 JS 在构建时打包，原生适配器补齐依赖 |
| `@lg/workspace` / `@lg/text` / `@lg/pdf` | 提供等价能力和版本化模块名/契约，复用新业务实现 |
| `emitImage` / `host(print_pdf)` / doing | Kotlin 图片、PDF 宿主与执行进度端口 |
| `workspace_apply` | 变更校验、指纹、审批和唯一工程写入口 |

R03 从 LG `package.json.workspacePackages`、实际部署目录和全部内置脚本生成兼容清单：逐个记录使用的导出/API、目标实现、样例、限制和证据。所有内置技能脚本必须可运行；用户脚本迁移说明必须准确。若现有声明承诺的 API 未覆盖，对应功能仍未完成，不把“引擎能跑加法”当工作区验收。

兼容清单必须在引擎定型前产出。基线含 `exceljs`、`mupdf`、`oxc-parser`、`@ruby/prism` 等包；包名存在不证明 Android 可加载。按实际导入与传递依赖逐项分类：

| 类别 | 决策与最低证据 |
|---|---|
| 可移植 JS | 固定版本与导出子集；在候选引擎执行实际材料脚本，验证模块/Promise/编码 |
| Node 兼容接口 | 列出使用的 fs/Buffer/path/stream 等具体 API、行为差异和拒绝边界；不能承诺完整 Node |
| Kotlin/native 宿主 | 明确替代 API、参数/结果 Schema、ABI、资源/许可证和取消回执；有真实读写产物 |
| WASM/native 包装依赖 | 单独验证加载与宿主能力；引擎不支持时选能力桥或另一引擎，不把未验证包登记可用 |
| 未解决项 | 记录影响的功能/脚本、候选方案、包体与内存预算、下一验证步骤；阻止对应功能验收 |

每项附版本、来源快照、调用者、样例、目标实现、预算和证据。QuickJS 的 JS 中断不等于宿主取消：必须分别验证 JS 停止、异步请求取消、文件流关闭和 native 工作退出。未确认真实执行者退出时保留占用；跨进程强制结束也须核对宿主回执和迟到回调。此清单和能力声明由 R03/R20/R21 共用，不维护三份互相矛盾的支持列表。

§70/§73 源码已由 #158 覆盖，落地 `core/workspace` 的路径/预算/材料/changes/审批/脚本端口/技能/联网契约与编排核心，以及合成引擎/端口测试；但真实脚本引擎、隔离 Service、生产 apply/审批/材料适配器、技能资源和搜索/Fetch/MCP 实现仍缺失。该核心只证明边界与失败语义，不构成 G2 通过或工作区可用。

### 7.3 文件、技能与资源寿命

`sources` 随资产修订重建，`datasets/changes` 随快照刷新，`work/uploads` 跨回合保留，按 Chat 重置/数量清理；当前活动工作区受保护。已发送图片保持固定结果，不能因源文件变化改写历史。

技能包含元数据、`SKILL.md`、references、scripts 和其他资源。支持用户同名覆盖、启停、显式引用、显示顺序、资源树编辑与文件版本冲突检查。模型选择权限、列表显示与文件权限分别判定；隐藏不等于禁用。技能文件改动与 Agent 执行按 LG 互斥，执行收尾后刷新目录。

指令、类型声明和示例必须随真实宿主能力变化；不保留虚假的 Node/npm/Chromium 声明。测试使用自有技能包，不断言动态内置技能的具体名称、数量、顺序或正文。

## 8. 翻译与文本流水线

新 `BatchTranslationService` 独立于 Agent。UI 和 Agent 共享它；Agent 内调用借用当前合法运行租约，不另建竞争运行。

流水线：固化原稿 → 资格/语言/重复来源判定 → 文本保护/译前替换 → 同文件规划和 token 预算 → 前文/术语/提示词 → 模型请求 → 输出解析 → 文本恢复/译后替换 → 校对证据 → 同事务保存有效结果。

对齐 LG 的同文件去重、姓名字段、过滤跳过、语言、ruby、MTool 优化、空译文和错误条目语义。重试区分请求错误与内容错误；内容采用 LG 的对半切与逐条收敛，不能继续套用 LNT 的章节/META 协议。

调度统一管理请求额度、RPM/并发、密钥失效/冷却/恢复、Retry-After 与自动并发。全密钥不可用时按 LG 等待恢复或取消；不要把内容错误当认证故障。每次请求使用固定配置快照，设置变化供后续执行使用。

本轮进度、工程累计统计、工程完成率分别维护。取消保留有效提交，completion 等全部请求与宿主收尾。服务异常后标为 interrupted，不自动恢复可能计费的请求。

## 9. 模型、搜索与网络

新模型层覆盖 LG 的 OpenAI Chat、Responses、Google、Anthropic、SakuraLLM 及可配置兼容端点；思考等级 DEFAULT/OFF/LOW 等、服务档位、生成参数、上下文、最大输出、额外 headers/body、密钥池、用途选择和能力发现均有显式模型。

Koog 和翻译引擎消费同一能力/认证/用量事实。兼容参数与失败回退在协议适配器里处理，不散布到 UI，不因响应是 HTTP 200 就认定成功。OAuth 复刻 LG 已有登录、刷新、目录与模型测试边界；不把 API Key 输入当成 OAuth 完成。

分开记录输入、缓存读取/写入、思考、输出、费用与实际/估算/未知状态；供应商不回传时不伪造零成本。

搜索按基线：每次先尝试 DeepSeek Search；缺配置/请求失败转既有五家 MCP 备用，正常空结果不触发回退；Fetch 按当前 DeepSeek 能力。取消结束整条链，结果截断明确。MCP 采用 Android 可用的远端传输适配，桌面 stdio 示例不能当作可运行证据。

新凭据存储使用 Android 加密边界，日志和工具结果脱敏。代理失败不静默直连，重定向、认证回调和本地回环分别验证。来源正文作为外部资料进入模型，不能直接变成产品指令。

## 10. 文件格式与导出

`FormatRegistry` 通过内容和扩展名识别，提供 discovery / parse / regenerate / write / validate。源原始字节和格式定位先入工程，再允许翻译；写回只读取固化原稿。格式层不维护工程会话，不直接提交译文。

- TXT/Markdown：编码、换行、首尾空白、标记、姓名/正文与双语策略。
- EPUB：ZIP 安全、spine/nav/NCX、资源、图片、样式、章节身份与重新打包；保真与 LG 写回语义逐项比较。
- 字幕：时间轴、样式/说话人、ASS/SSA 非文本字段、VTT 标记和零条目文件。
- JSON/表格/TRANS：原始数值表示、结构/字段位置、工作表、单元格和非翻译数据。
- RenPy/MV/MZ：脚本 AST/字符串精确定位、引擎过滤、媒体/目录准备、加密与失败恢复。
- RGSS：归档/Marshal、Ruby 源码字符串定位、数据写回；原型必须覆盖服务级闭环，不能沿用 LG Termux 的验收空缺。
- PDF：原页提取、图像、文本/字体/坐标、页级决定、静态 HTML 排版、合并/省略和整份输出；Android 系统打印只作为交互适配，不能假设与 Chromium 版式相同。

具体库在 R05 实测后锁定；不得把 PdfRenderer 仅能显示页面当成完整 PDF 提取/写回能力。检查 native ABI、WASM 运行条件、字体、许可证适配与发行资源；未通过的库不能进入全格式实现排期。

导出 staging → 内容校验 → 发布 → 清理。源稿缺失、结构不匹配、部分写回失败都应报告具体文件/位置；不能生成遗漏译文的文件后报成功。格式结果验收用内容事实和结构，不强制 ZIP 时间戳等非语义字节相同。

## 11. Android 生命周期与存储

`RuntimeService` 管理应用级执行作用域；同进程 UI 通过应用服务/Flow 调用，不为复刻 Web 增加 HTTP/SSE。Service 存活不依赖 Activity 引用，关闭页面不取消已受理任务。前台通知提供运行进度、停止和返回入口；通知权限拒绝不能破坏数据恢复。

长任务根据真实用途声明前台服务类型并处理超时；后台启动限制、Doze、网络切换、低内存和强制停止纳入设备矩阵。WorkManager 只用于适合重新调度的维护/恢复提醒，不作为绕过系统时长或配额的手段，不自动重放模型付费操作。

Android 15+ 在相应目标版本下限制 dataSync/mediaProcessing 后台时长；Android 16 的长 Worker 也涉及 job 配额。用可恢复分批提交与用户主动继续满足长任务，而不是承诺无限保活。[前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)、[长 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

文件 IO 分开限制输入字节、压缩展开、解析后内存、输出和并发。LG Web 的 64 文件/64 MiB/128 MiB 是浏览器暂存政策，不是新 Android 产品不经验证的永久硬编码上限。R06 在低内存设备上形成预算和大目录分批策略；实际占用包含在途工作，失败释放预算并保留可重试材料。

以下为 R06/R18/R19 待实现的生命周期契约，现有进程内协调器不等于已提供这些状态：

| 阶段 | 持久边界与占用 | Service/资源与恢复 |
|---|---|---|
| 模型/脚本/宿主活动执行 | 保存请求/执行身份与已知回执；持有实际资源 lease | 按真实用途运行 Service；停止先禁止新工作，再取消并等待资源收尾 |
| 等待问答/审批 | 保存问题/审批、固定正文、策略检查点和 Run 归属；保留可查询的逻辑预留 | 无其他活动执行者时释放网络/脚本/DB 资源并结束活动前台服务；按平台允许方式通知，用户操作时重取执行权 |
| 限流退避/密钥不可用 | 保存下一尝试条件、配置与尝试身份；不确定请求不进入自动重试 | 短退避可沿用合法活动服务；超过 R06 实测阈值转持久等待，用户恢复或合法运行条件满足后重查，不靠定时器无限保活 |
| 正在提交/停止收尾 | 保存命令绑定、工程事务回执及停止原因；旧执行者退出前不释放占用 | 系统要求停止时不无限拖延；来不及完成则依事务/回执恢复，不提前报告成功或空闲 |
| 中断/进程重启 | 无法证明完成的请求标为不确定；读取工程回执后补记 AppStore | 不重建旧进程 lease，不自动发模型请求；用户继续时核对实例/代际/修订，取得新 lease |
| 终态 | 保存结果与用量来源，清理只涉及自己的材料引用 | 无其他活动任务则停止前台服务；再次运行产生新的执行身份 |

逻辑预留是可恢复的业务状态，不持有进程锁。等待期间，同一工程的竞争执行仍须经占用规则拒绝或显式取消/收尾；释放 Service 不等于工程已空闲。用户切工程必须解决旧预留，不让后台等待无限阻断且无可操作入口。恢复旧 Run 或显式结束后创建新 Run 必须区分，旧审批不得自动改绑新 Run。

R06 记录服务类型、启动来源、等待阈值、通知拒绝、超时与 API/设备结果；阈值未经测量不得作为性能承诺。普通问答的前台计时与待决审批绝对截止时间分别持久化，用户手动回答/裁决与超时以持久状态竞争结算。

## 12. 旧 LNT 数据迁移（已取消）

用户于 2026-10-24 明确决定：当前仍在开发阶段、尚未自用，不需要旧 LNT 数据迁移。R36 移出当前路线图，旧 Room v6/旧文件读取器、迁移器、档案入口和升级迁移流程不再作为交付项；本轮新增的迁移代码与测试已删除。旧数据/旧代码仍不主动删除，外部 LG 工程与 Pi 会话兼容也已按后续用户决定取消；原生工程升级与备份恢复仍保留。

应用 ID、签名与 versionCode 仍须维持覆盖升级连续性；产品名称/图标变化不自动改变包名。

## 13. 官方依据与适用边界

- [Koog Functional agents](https://docs.koog.ai/agents/functional-agents/)：策略/工具编排参考，具体 API 仍以锁定依赖为准。
- [Koog Persistence](https://docs.koog.ai/features/agent-persistence/)：检查点与工具副作用不同，需要应用协调。
- [Koog Providers](https://docs.koog.ai/llm-providers/)：框架支持能力不是全部 LG 供应商行为的证明。
- [QuickJS](https://bellard.org/quickjs/quickjs.html)：可嵌入、内存和中断接口；不提供完整 Node/npm 环境。
- [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files)：URI 与文档提供者，不承诺真实本地路径。
- [Android Service](https://developer.android.com/reference/android/app/Service)：隔离执行与生命周期原型依据。

上述资料于 2026-10-22 查询。没有由此推断新增依赖已经编译、真机可用或格式输出已经一致。


当前 S2 单条切片：nativeapp → nativecore.agent.NativeKoogWorkspace → AgentRequestBoundary/AgentToolRegistry → NativeAgentStore（独立应用 Room v13，审批表在 v2 引入）→ ProjectWriteStore。查询/准备操作使用本地固定后端，真正项目写入只由界面显式允许一次后走存储内部授权路径；不开放通用 PROJECT_WRITE。正文/消费绑定和跨库回执核对见 [S2_AGENT_SLICE](S2_AGENT_SLICE.md)，结构化工具循环的后续实现见 §25，完整 Run/恢复仍待实现。


§25 对话源码接线扩展为 NativeKoogConversation → AgentRequestBoundary → 共享 NativeModelGateway → core/engine CompatibleAgentModel；NativeModelSettingsStore 拥有独立 Keystore 密文和配置修订，NativeAgentStore 拥有结构化历史、选中会话与请求回执。Room v1→v8 迁移源码已由 #158 覆盖，v9—v13 与 2—13.json 仍为工作区待验证，真实供应商尚未联调；完整执行、恢复和计量目标未因此关闭，见 [AGENT_CONVERSATION](AGENT_CONVERSATION.md)。


### 持久执行记录切片接线

`nativeapp → DurableRunExecutor → RunJournalPort → NativeRunStore` 为 §28 的运行记录接线，应用 Room 当时升至 v3；当前 v9 保留 v1→v9 显式迁移，后续增加远程请求元数据、协议/多密钥、用量账本、固定翻译任务和响应/提交暂存。它保存执行与请求/提交边界，重启只核对现有回执，不恢复执行权或自动重放。契约见 [RUN_RECOVERY](RUN_RECOVERY.md)，当前 CI 与未验证范围见 HANDOFF §3（原开发轮见 §28）；§34—40 增加当前页/整工程模拟前台 Service 与共享固定调度，契约见 [FOREGROUND_TRANSLATION](FOREGROUND_TRANSLATION.md)，已由 #158 覆盖；设备验收、Agent 应用级执行、完整调度及原 Run 的执行接续仍是本章目标。

## 共享调度与格式核心的装配边界

`NativeModelGateway` 的 Agent / 翻译入口共用进程内调度器，具体固定限额、FIFO、取消、跨配置修订时钟及运行期用量投影见 [MODEL_SCHEDULING](MODEL_SCHEDULING.md)。整工程模拟沿用原有唯一 Runtime/写入链路，不调用旧翻译引擎。

`:core:formats` 增加独立的字幕解码文本核心，见 [SUBTITLE_CODECS](SUBTITLE_CODECS.md)。字幕核心身份只绑定当前解析文本；§49 已通过 SubtitleFormatAdapter 和 ProjectFormatService 接入 `.lnt` v5 资产格式/槽位存储及工作台。禁止 formats 直接打开工程数据库或把非 TXT 文件登记为 TXT 来绕过存储约束。开发记录见 HANDOFF §39—40，当前 CI 与验证状态见 HANDOFF §3。
