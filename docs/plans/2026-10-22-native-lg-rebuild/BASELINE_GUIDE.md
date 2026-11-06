# 实施源码保护与迁移索引

> **2026-10-24 范围更新**：用户决定当前开发阶段不需要旧 LNT 数据迁移，R36 已移出当前路线图，迁移代码/测试已删除。本文中与 R36/旧库字段映射相关的内容仅保留为历史资料，不构成当前交付要求；外部 LG 工程与 Pi 会话兼容也已取消，R04/G3 仅负责原生存储与恢复。

## 固定输入

2026-10-22 用户已授权开始修改。本轮先保存实施前源码，不修改 LG。

本机快照目录：`~/.local/share/lnt-rebuild-baselines/2026-10-22-r00/`。
归档校验和见 [SNAPSHOT_RECEIPT.json](SNAPSHOT_RECEIPT.json)。LNT 252 个文件，LG 1612 个文件。

每份归档含 `source/` 下的工作区实际内容（包含范围内未跟踪文件）、
`tracked.patch`（HEAD 到工作区，包含暂存修改）、`manifest.json`（提交、分支、文件模式、逐文件 SHA-256、删除清单）。
使用独立空目录恢复源码，不向现有工作区解包。归档不包含 Git 历史和范围外文件，不能称为整个仓库备份；
恢复完整仓库需对应 HEAD 的独立 checkout，再依据清单恢复文件并处理删除项。

快照只选择源码、构建输入、内置资源和文档，不选择用户工程、运行日志、配置目录、签名或本地环境文件。
脚本拒绝符号链接和已知敏感文件名；这不等于任意源码的秘密扫描证明。归档保存在私有目录，不提交或上传。
重新捕获必须使用新目录，不能覆盖本次基线：

```sh
python tools/rebuild/capture_sources.py --lg /absolute/path/LinguaGacha-Termux --output /outside/both/repos/new-baseline
```

脚本实际重新读取了归档中的每个源码文件并比对摘要；未做构建、测试或恢复安装。
本轮新建的 tools 和后续模块不属于实施前快照。后续捕获脚本已扩展到 core、重构工具、docs 和更多根构建输入；不会改变旧归档和收据。

## 行为发现清单

[BEHAVIOR_INDEX.json](BEHAVIOR_INDEX.json) 从 LG 实际源码提取路由、公开设置字段、页面、格式、工作区外壳 API、预装包和技能入口。
每条带来源及候选功能 ID；行号仅适用于此次基线。脚本不执行 TypeScript，不能证明动态注册无遗漏。

`candidate_features` 是分组候选，不是精确语义映射。`consumer_review: pending` 明确表示尚未逐条追踪消费者、参数、测试与可见操作。
设置中的模型、技能、人格等非快照配置需另查对应领域 Schema，不能把这 21 个公开字段当完整配置。
14 个 LG 路由与 15 个原生 UI 条目也不是一一对应，原生增加了系统交付与日志等页面职责。
R00 在完成这些人工核对前保持进行中；本文件不把自动提取登记为全面功能审计完成。

第二轮已补充逐条文本引用和测试线索，移除未知路由自动归 OP01 的兜底；技能路由按真实 `/api/skills/` 分类，翻译产物不再默认归 TXT。部分消费者人工核对见 [STATIC_REVIEW](STATIC_REVIEW.md)。索引中 pending 仍表示逐条完整审计未完成。

第四轮新增 `tools/rebuild/audit_consumers.py`，按同一索引解析路由处理器、后端定义、前端调用与测试证据，结果写入 [CONSUMER_AUDIT.json](CONSUMER_AUDIT.json)。它是定位工具，不修改索引中的 pending 状态，也不执行 LG 或证明运行时行为。
`tools/rebuild/source_bindings.py` 为发现范围之外的 ID 补一条已审阅的 `(LG 文件, 标记)` 绑定，原生项记录“无 LG 源码”的理由；`tools/rebuild/coverage_report.py` 把发现条目、绑定与原生项合并输出 [FEATURE_COVERAGE.json](FEATURE_COVERAGE.json) 与 [SOURCE_BINDINGS.json](SOURCE_BINDINGS.json)。当前 98 个 ID 都有可追踪证据（75 发现 + 20 绑定 + 3 原生项），但“有证据”不等于已实现或已验证。

R36 的早期字段样本见 [旧库字段映射样本](LEGACY_FIELD_SAMPLE.md)：只读旧 Room v6 实体/schema/文件管理器，把字段归入新事实、转换、档案或“R07 前需扩展”，不读取真实用户数据。

## Koog 解耦清单

路径前缀：`app/src/main/java/com/breakyuna/noveltranslator/core/agent/koog/`。

| 入口 | 当前旧依赖 | 新边界 / 后续验证 |
|---|---|---|
| AgentSession | AgentToolHost、LlmGateway、SystemLogger、TranslationControlSignal | 模型/工具/事件端口；R02 验证取消和恢复 |
| AgentTurnStrategy | 旧工具宿主、旧翻译控制信号 | 类型化工具和执行身份；不迁入旧宿主 |
| LntPromptExecutor | LlmGateway、LlmRequest/Result、旧工具描述 | 新模型端口及 typed history；不继承旧请求旁路 |
| AgentModels | ApiProviderEntity、LlmResult | 新领域配置与事件；不跨模块传旧 Entity |
| AgentPrompts / AgentToolProtocol | AgentToolSpec | 同源工具契约；文本协议按能力分支验证 |
| AssetsAgentPromptLoader | SystemLogger | 新资源/事件端口；旧小说提示词不移入 |
| RoomAgentSessionStore | 旧 Agent DAO/Entity | 新应用记录和公开工程历史分离 |
| AgentSessionStore / AgentProtocolModels | 存储/协议类型须与上述入口一起复核 | 不把只存消息当检查点恢复 |

这是静态 import 核对，不证明 Koog 1.3.0 在 Android 上的检查点可用。

## 旧用户资料读取来源

路径前缀：`app/src/main/java/com/breakyuna/noveltranslator/`。

| 来源 | 读取入口 | 迁移要求 |
|---|---|---|
| Room v6 与历史 schema | data/db/AppDatabase.kt、app/schemas/ | 一致副本读取；不得清库；逐版本核对 |
| Book、Edition、章节、段落与人工修订 | data/model/ReaderPlatformEntities.kt、data/db/ReaderPlatformDao.kt | 稳定来源映射；多个 Edition 分开保留 |
| 原稿、正文、封面、共享图片、工作区、导出 | core/book/BookFileManager.kt | 由唯一文件拥有者定位；先保护 books/book_ID 目录及 exports |
| 术语、记忆、提示词和运行记录 | ReaderPlatformEntities.kt、ReaderPlatformDao.kt、data/repository/BookPlatformRepository.kt | 仅作历史参考；完整字段映射不适用（R36 已取消） |
| 保护/替换规则 | data/model/QualityRuleEntities.kt、data/db/QualityRuleDao.kt | 原配置、顺序与启用状态保留 |
| 旧会话 | data/model/AgentEntities.kt、data/db/AgentDao.kt | 顺序/角色与归属核对；不明确的归属不能默认绑定首工程 |
| 模型与凭据 | data/model/ApiProviderEntity.kt、data/db/ApiProviderDao.kt、core/security/ApiKeyCipher.kt | 只通过迁移端口解密；失败保留旧密文，不输出 Key |
| 请求节流偏好 | data/prefs/RequestPacingPreferences.kt | 核对新调度语义后转换，不直接复用旧运行状态 |

此轮只保护源码；没有访问、备份或迁移任何真实用户数据库/小说/凭据。
