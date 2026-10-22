# 首轮实施静态审查与构建交接

本文件记录源码边界和核对依据，不是进度表。实际状态只见 [HANDOFF](HANDOFF.md)。

## 1. 新模块和执行原型

实际新增七模块装配：保留 `:app`，新增 `:core:domain`、`:core:engine`、`:core:agent`、`:core:storage`、`:core:formats`、`:core:workspace`。

- domain、engine 使用 Kotlin/JVM；domain 没有 Android、Koog、协程或旧业务依赖，使用 JSON 序列化基础库描述端口数据。
- engine 当前依赖 domain 与既有版本的 coroutines，保存进程内运行占用和生命周期原型。
- agent/storage/formats/workspace 使用 Android Library 与 AGP 9 内置 Kotlin。agent 已补模型请求/只读工具边界原型，见 [AGENT_CONTRACT](AGENT_CONTRACT.md)；engine 增加审批与变更批次协调原型，见 [APPROVAL_CONTRACT](APPROVAL_CONTRACT.md)；storage/formats/workspace 仍只有构建声明和 Manifest。
- 除 domain 自身外，各模块对 `:core:domain` 统一使用 `api`：公开签名直接出现 `RunIdentity`、`AgentMessage`、`RunLease`、`PreparedChangeSet` 等类型，若用 `implementation` 会把这些类型从消费者编译类路径隐藏。`tools/rebuild/check_structure.py` 已把该边写入静态检查。
- app 声明上述模块依赖，SDK 版本统一来自 catalog；应用 ID、签名、升级版本、旧库与启动导航不变。
- 新 Kotlin 包为 `com.breakyuna.noveltranslator.nativecore`，用于隔离旧 `app/.../core` 迁移来源，避免误将旧类引入新模块。
- 旧 Koog 仍在旧 app 链路，尚未迁移，也未把旧 Jackson 排除复制到新模块。R02 在外部最小编译后再验证检查点依赖。

`RuntimeCoordinator` 通过同一锁检查选定工程/会话和运行身份，先返回不透明 lease 再允许启动协程。
Stop 改变实际运行阶段，不先清空运行；请求、脚本、宿主与写入的所有权未释放前保持占用。
迟到 Stop/finally 和伪造的同值身份都不能操作另一个 lease。
快照在锁外发布，修订号 CAS 拒绝迟到回退；收集协程不会在协调器的临界区内被通知。
技能写入只阻止 Agent，认证提交阻止运行启动，工程写入和活动运行互斥；运行内写入须出自自己的真实 lease。

`ManagedRunLauncher` 使用调用方提供的应用/Service 作用域，ATOMIC 启动保证已取消父作用域仍执行 finally。
运行体用 coroutineScope 等待子任务，operation/projectWrite 在 finally 释放占用。
此实现不启动 Service，不执行 HTTP/脚本，不提供跨进程恢复或审批，也不替代副作用回执。
平台适配器仍必须取消并等待真实网络/native 资源；不得让宿主在协程返回后继续运行。
模型网关接线前，不能据此宣称“停止后绝无付费请求”。

`ToolArguments` 接收解析器保留的 JSON 数字 token，经严格 JSON 语法、BigDecimal 精确转换与范围检查，再转换为 Int/Long。
不先经过 Double/Number.toInt，不强制转换字符串/布尔值；错误不回显参数值。
`RelativePath` 只校验规范相对路径，实际文件适配器还需阻止符号链接逃逸；不把它当成文件沙箱。

## 2. LG 行为核对依据

来源路径均相对固定 LG 工作区；[BEHAVIOR_INDEX](BEHAVIOR_INDEX.json) 保存逐条文本引用和测试线索。
这些引用只帮助定位，不能替代动态调用、Schema 收窄及完整测试审计。

| 功能 | 已核对的链路 / 语义 | 后续实现责任 |
|---|---|---|
| OP01 / AG14 | backend/runtime-operation-gate.ts 的同步占用、真实 lease、迟到释放；Agent 回合身份在 agent-service 中协调 | 新协调器实现进程内占用；持久回执和恢复仍待 R08/R18 |
| PR04 / PR07 | backend/api/api-routes.ts → project-content-service / app-settings-command-service → 工程准备与提交 | 工程事务和设置补偿；不能直接从 UI 写配置/数据库 |
| AG03 / AG04 / AG07 | API 的 message/input-status/queue/round 分别进入 agent.input_command 或队列命令 | 发送去重、输入尝试、草稿版本、分支不能合成一个发送按钮状态 |
| AG11 / AG12 | question/resolve 与 write-approval/resolve 是不同入口 | 普通回答不替代工程审批 |
| WS05 / UX07 | Agent save-path pending/respond；translation files outputs/download | SAF 真实完成回执；不移植 HTTP/SSE，但保留迟到应答与材料寿命 |
| QL04 / QL05 / QL06 | quality rules/prompts 各有 import/export、presets/read/save/rename/delete | 导入预览、工程事实与应用预设分开 |
| FM01—FM12 | shared/project-source-formats.ts 共 15 个发现目录；item.ts 另列底层文件类型 | 目录不是所有格式变体；KVJSON/MESSAGEJSON、XLSX/WOLFXLSX 等分别闭环 |
| WS02 / WS03 | workspace/runtime/api.ts 只提供 contract/userSkillDirectory/emitImage/doing/host 外壳；具体 IO/module API 在 bootstrap/能力桥 | 不把这 5 个字段当完整工作区 API；10 个预装包逐包验证 |

## 3. 设置消费者的静态核对

21 个公开设置字段全部在 domain/setting.ts 定义；统一更新经 frontend/features/settings-editor 和 backend/app/app-settings-command-service。
下表记录已核对的消费入口与迁移注意点；不是原生设置已实现清单。

| 设置 | 消费入口 / 注意点 |
|---|---|
| agent_approval_mode | agent/agent-service.ts；当前偏好与已展示审批分开 |
| app_language | frontend/app/locale、app-setting-service；新 UI 迁 Android 资源五语 |
| source_language / target_language | project-content-service、batch-translation-runner、workspace/service；工程镜像及已定格请求快照 |
| project_save_mode / project_fixed_path | frontend/pages/project-page/page.tsx 的创建流程选择输出位置；不能因 backend 没出现字段就判为无消费者，原生映射 SAF |
| output_folder_open_on_finish | file/translation-file-generation-service.ts；Android 转为可用的保存/分享交付动作 |
| request_timeout | llm/llm-request.ts、model-service.ts、translation-request-scheduler.ts；统一请求配置，不按按钮本地计时 |
| preceding_lines_threshold | batch-translation/planning/translation-planner.ts；Sakura 分支不附加普通前文 |
| clean_ruby | cache/proofreading-cache.ts 形成文本处理选项，shared/text 消费；必须追踪间接选项，不能只搜同名字段 |
| deduplication_in_bilingual | translation-file-generation-service.ts → file-format-types 与各格式 writer；不是翻译请求去重 |
| write_translated_name_fields_to_file | file-format-types、RenPy/MESSAGEJSON 等 writer；完整姓名槽位仍须保留 |
| prompt_enhancement_enable | batch-translation/work-unit/work-unit-prompt-builder.ts；运行快照生效 |
| mtool_optimizer_enable / skip_duplicate_source_text_enable | project-content-service、project-lifecycle-service、project-write-state；可能改变工程状态和去重统计，不只是提示词选项 |
| glossary_default_preset / text_preserve_default_preset / pre_translation_replacement_default_preset / post_translation_replacement_default_preset | quality-rule-service.ts 通过 domain/quality.ts 的 default_preset_setting_key 动态读取；project-default-preset-reader.ts 在建工程时读取 |
| translation_custom_prompt_default_preset | custom-prompt-page state 通过 domain/prompt.ts 常量读写；project-default-preset-reader.ts 动态读取；不能因字面检索缺 backend 命中就判为失效 |
| recent_projects | app/app-setting-service.ts；仅移除最近记录不删除工程 |

models、model_selection、agent_skills、agent_personality 不属于这 21 个公开快照字段，仍是必须另查的完整配置。
公开 API 路由、页面动作、模型所有字段、技能文件实际 API、迁移各 Entity 字段尚需继续审计；R00 不因本表完成而自动通过。

## 4. 外部验证交接

本机只做静态审查，未实际构建。下列命令仅给 PC/获授权 CI：

```sh
python3 tools/rebuild/check_structure.py
python3 tools/rebuild/audit_consumers.py --lg /path/to/LinguaGacha-Termux \
  --index docs/plans/2026-10-22-native-lg-rebuild/BEHAVIOR_INDEX.json \
  --output docs/plans/2026-10-22-native-lg-rebuild/CONSUMER_AUDIT.json
python3 tools/rebuild/coverage_report.py \
  --matrix docs/plans/2026-10-22-native-lg-rebuild/FEATURE_MATRIX.md \
  --audit docs/plans/2026-10-22-native-lg-rebuild/CONSUMER_AUDIT.json \
  --output docs/plans/2026-10-22-native-lg-rebuild/FEATURE_COVERAGE.json
./gradlew :core:domain:test :core:engine:test testDebugUnitTest lintDebug assembleDebug
```

现有 CI test job 已加入两个 JVM 模块任务；Android Library 由根 testDebugUnitTest/lintDebug/assembleRelease 任务选择器覆盖。
测试和 Lint artifact 路径增加 core 多模块输出；不修改并行 job、不触发 CI、不关闭原测试或 Lint。
构建插件/元数据解析、D8、最低 API、安装和新原型设备验证均未取得证据；R01 不登记为已通过。

新增测试针对：参数类型/整数/溢出/大身份、路径穿越、同步运行预留、发送切工程竞争、伪造身份、Stop 和真实收尾、迟到释放、技能/认证互斥、关闭、取消父作用域和结构化子任务。
本轮再增 19 个用例：变更批次身份/基准指纹/digest/摘要 7 个、审批记录不变量 2 个、审批协调器 10 个（单次消费、拒绝终态、重复裁决、超时、跨 Run 取消、AUTO 快照、单 Chat 唯一待决、重试幂等与内容冲突、非法超时）。
这些文件尚未执行，不能把测试数量登记为通过数量。

## 5. R00 消费者静态审计

`tools/rebuild/audit_consumers.py` 对 [行为发现索引](BEHAVIOR_INDEX.json) 的 194 条记录逐条补充证据：源文件行是否仍含该键、路由处理器调用了什么、被调用方法在哪个后端文件定义、前端以字面量还是动态前缀引用、有哪些测试文件提及。
结果写入 [CONSUMER_AUDIT.json](CONSUMER_AUDIT.json)，每条保留 `match` 类型（`owner_hint`、`wiring`、`same_file_helper`、`specific_name`、`name_only`），不把文本命中当成调用证明。

本轮基线（LG `23796556`）统计：

| 类别 | 结果 |
|---|---|
| route | 118 条全部定位处理器与调用方法；均找到后端定义与调用方/测试证据 |
| page | 14 条全部解析到页面组件与目录 |
| format | 15 条全部对应到已审阅的适配器目录及测试 |
| setting | 20 条前后端均有文本引用；`project_fixed_path` 仅前端引用 |
| workspace-api | 5 个外壳 API 在内置脚本或 workspace 源码中有使用 |
| workspace-package | 10 个预装包在源码中有导入；`chardet` 经动态 `import()` 使用 |
| skill | 11 个内置技能资源存在 |

`project_fixed_path` 由基础设置页读写并随建工程请求传入，后端只做 Schema 落盘；原生侧映射为 SAF 保存位置，`STATIC_REVIEW` 第 3 节已记录，不是缺失消费者。
所有条目在本次基线的源行检查均未漂移（`source_ok` 全为 true）。

边界：脚本只做文本与括号平衡分析，不解析 TypeScript、不执行 LG、不验证参数、Schema、动态注册或运行时行为；`name_only` 与 `specific_name` 仍需人工复核；行号只对本次基线有效。
`BEHAVIOR_INDEX.json` 的 `consumer_review` 仍保留 `pending`，本轮没有把它批量改成“已审计”：R00 仍需按功能 ID 逐项核对参数、可见操作、脚本实际 API 与完整 Schema，本表只是可追踪的定位证据。

`tools/rebuild/coverage_report.py` 把矩阵 98 个 ID 与发现条目求交集，结果见 [FEATURE_COVERAGE.json](FEATURE_COVERAGE.json)：75 个 ID 至少有一条发现条目，23 个没有任何条目——
`AG02 AG05 AG08 AG15 AG16 MO02 MO03 MO07 MO08 MO10 OP02 OP06 OP07 OP08 PR03 PR05 PR06 PR10 QL08 TR08 TR09 WS04 WS10`。
这不是“LG 没有该行为”，而是当前发现范围只覆盖路由、公开设置、页面、格式、workspace 外壳与技能；工具定义、模型协议、调度/重试、校对评估、运行时服务与迁移仍需人工绑定源码。R00 未完成前不得把这 23 项当作已验证或已排除。

## 6. 审批契约原型

`PreparedChangeSet` 固定对象身份、基准指纹和批次 digest；`ApprovalCoordinator` 以 `ApprovalPersistencePort` 为权威实现状态机：
单 Chat 唯一待决、模式快照、`allow_once` 单次消费、拒绝/超时/取消终态、重复请求幂等与内容冲突。
内核不持锁等待 UI、不起定时器、不写工程数据库。

未完成：Room `AppStore` 适配器、`ProjectWriteStore` 消费 grant、`workspace_apply`/工具接线、U02 审批界面、进程恢复与差异渲染；
`AgentToolRegistry` 仍拒绝全部 `PROJECT_WRITE` 工具。详见 [APPROVAL_CONTRACT](APPROVAL_CONTRACT.md)。
以上均为静态审查，未构建、未执行测试。
