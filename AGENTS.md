# LightNovelTranslator 协作规则

适用于整个仓库，用户当前指令优先。只加载与本次任务有关的资料；本文件已在对话提供时不重复读取。

## 项目与范围

以 LG Termux 完整业务能力为目标重做原生 Android 产品，仅保留并改造 Koog Agent 框架。以 Project / Asset / Item 或 PDFPage / Rules / AgentChat / Runs 为中心；新业务不接回旧 AppViewModel、Book/Edition、书架或阅读器主链路。

原生工程使用 `.lnt`，应用运行记录使用独立 Room；保留各自升级与备份恢复。旧 LNT 数据迁移、`.lg` 交换、Pi 历史转换已取消，不新增对应实现或验收要求。旧用户数据不得删除。

## 操作与验证

- 开始先看 `git status --short --branch`，保护既有修改；“检查/审核/仅确认”保持只读，只要求计划时不实现。
- **本机是 Android/Termux，禁止任何 Gradle、构建、测试、Lint，以及单文件 Kotlin 编译替代验证。** 本地只做静态检查；构建测试交由 PC/已授权 CI。发现真实 GradleDaemon 时按本机约定停止，不杀其他进程。
- 未明确授权，不提交、推送、建 PR、合并、触发 Actions、发布或真实付费调用；推送目标分支须已明确，不默认 main。禁止 reset --hard、checkout --、clean 等破坏性操作。
- 修改后执行 `git diff --check`、工作区状态和冲突标记检查；改动范围保持聚焦。
- 汇报实际变化、验证及限制，注明“静态审查，未实际构建”；引用 CI 必须核对其提交。CI 全绿不替代设备、视觉、供应商和完整格式验收；截图必须实际查看。

## 实现底线

- 新链路为 UI → 应用服务 → 核心/存储；Agent 与手动操作共用 ProjectWriteStore，写入核对工程身份、修订与对象指纹，审批绑定实际差异；参数无损验证类型、整数性和范围。
- 所有模型调用/重试走统一网关与调度器。暂停/取消后不发起新付费请求，恢复不重放旧授权；逐批保留已提交事实，用量按回执记录，非瞬时错误不盲重试。
- 凭据只经凭据端口读取，禁止写入日志、提示词、异常、源码或测试。外部路径/归档/链接需边界校验；Debug 默认关闭。
- 新 UI 使用五语 Android 资源；状态 key 包含实际工程/会话/资产/运行身份；大列表用稳定 key，主线程不做重 IO。
- 依赖使用 version catalog，不在模块添加 repositories；存储变更同步版本、非破坏性迁移及权威 schema 导出。

## 按任务读取

文档目录：[`docs/DOCUMENTATION.md`](docs/DOCUMENTATION.md)，只查目录后选择相关资料，不批量通读。

- 查进度/继续重构：先定位 [`HANDOFF.md`](docs/plans/2026-10-22-native-lg-rebuild/HANDOFF.md) §2—§4；历史细节仅按需查 HANDOFF_HISTORY.md，不通读归档。唯一进度记录是 HANDOFF，不把计划或测试数量当完成证明。
- 改产品范围/架构：按需读同目录 README、ARCHITECTURE、FEATURE_MATRIX、IMPLEMENTATION_PLAN；做 UI 时读 UI_SPEC。
- 改代码：读 [`ENGINEERING_GUIDE.md`](docs/ENGINEERING_GUIDE.md) 相关小节；Kotlin/Android/构建问题再查 `docs/plans/2026-10-19-build-and-ci-playbook.md`。API 24 命名分组、API 34 正则及 GenAI API 26 守卫需特别核对。
- 专题契约按文件名定位；旧根 README、REFACTOR_REPORT、2026-08/10-19 设计只作历史参考，不约束新产品定位。
- 仅更新文档或回答局部问题，无需读取全部计划、工程手册或交接历史。实际进度变化只更新 HANDOFF；根规则不重复维护提交、CI、schema 版本和功能清单。
