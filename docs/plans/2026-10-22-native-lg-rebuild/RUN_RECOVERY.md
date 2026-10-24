# 持久执行记录与中断核对切片

当前进度与验证边界统一见 [HANDOFF §3、§49](HANDOFF.md)。本专题相关源码已纳入 `2643b2c` / Actions #146 的 test/lint/release 成功基线；新增设备/视觉、真实供应商与 Room schema 导出仍待补齐。

实际进度、当前 CI 和验证边界只记入 [HANDOFF §3、§49](HANDOFF.md)，原开发轮见 §28。本文定义持久记录契约；本地模拟前台 Service 已有 CI，同一 Run 的跨进程执行接续尚未实现。

## 1. 接线与拥有者

`nativeapp → DurableRunExecutor → RuntimePort / RunJournalPort → NativeRunStore`。
Agent 对话通过 `RunProgressRecorder` 记录请求阶段；本地模拟翻译记录请求、批次提交阶段和稳定命令编号。
`NativeRunStore` 属于应用级 `NativeAppGraph`，每个进程只装配一个实例；它的随机拥有者编号不能作为跨进程并行执行协议。§28 原切片由页面协程发起；§34—35 将本地模拟接到应用级前台执行器与 Service，并加固宿主退出/任务切换，§41 已取得 CI 证据，§39 扩展为当前页/整工程范围，见 [FOREGROUND_TRANSLATION](FOREGROUND_TRANSLATION.md)。Agent 对话仍由页面协程发起。

执行记录写入独立应用 Room v3 的 `native_runs`，通过显式 `MIGRATION_2_3` 从已交付 v2 增加该表；v1 经 v1→v2→v3 升级。旧阅读库和 `.lnt` v1 均不变。保留已生成的 `1.json`，`2.json` 与 `3.json` 均须 PC/CI 的 KSP 生成并审阅，禁止手写。

每次执行有独立 `executionId`，绑定本机 ProjectInstance / Generation / Project / Chat / Run / Command 和拥有者。准备审批和允许一次可以沿用逻辑 RunId，但后者产生新执行记录并保留实际审批命令身份。记录仅含元数据、计数、请求和提交命令编号，不存原稿、译文、凭据或异常正文。

## 2. 生命周期

先同步取得真实运行租约，再以不可取消收尾方式登记执行；登记失败时不进入模型/工具业务。停止仍由 RuntimePort 禁止新操作并取消实际协程，数据库 IO 继续在 IO dispatcher 上进行。

状态为 `RUNNING / WAITING_APPROVAL / SUCCEEDED / CANCELLED / FAILED / INTERRUPTED`；阶段为 `ACCEPTED / REQUESTING / RESPONSE_RECORDED / COMMITTING / COMMITTED`。子协程及日志收尾完成后才释放运行租约。审批等待记录在读取时依据同一工程/会话/逻辑 Run 的真实审批终态核对，不因新建执行自动改绑审批。

终态日志写入失败仍释放已结束的运行租约：成功业务会报告日志失败；已有失败/取消不会被收尾异常替换。未结算记录在下次刷新或启动时保守登记中断，不把它显示为继续运行或已成功。

## 3. 重启核对

- 本进程仍持有的执行不被刷新中断；遗留运行登记为 `INTERRUPTED`，不恢复进程锁、不发起网络请求、不写回译文。
- Agent 请求检查点绑定实际 attempt 与 branch，并使用原有 requestKey 编码核对请求回执。尚未准备或只有 PREPARED 的请求不视为已发送；COMPLETED 只证明响应已记录，不自动补历史或执行模型返回工具；DISPATCHED 无回执仍是不确定状态。
- 模拟翻译在提交前记录最多 20 个固定命令编号，跨库窗口按 `.lnt` 的同实例/代际回执核对。全部提交才补记该批保存条数和修订；未提交不执行，部分回执或读失败保留需核对提示。
- 不确定记录不会自动重试。Agent 页展示当前工程最近五次执行与中断说明；发送新请求前需核对工程事实及供应商用量。记录读取失败时阻止新 Agent 操作和模拟翻译，刷新成功后解除限制。

## 4. 本轮明确未覆盖

没有原 Run 的继续按钮、输入队列、持久工具策略检查点、自动补历史、完整用量账本或真实翻译供应商。§34—40 的本地模拟前台 Service、通知、超时停止及整工程范围已有 §41 CI 证据，不代表完整后台系统已验收。用户可在核对事实后显式启动新运行；模拟翻译按显式选择重新读取当前页或整工程，沿用原有“仅处理未处理条目”的规则。它不是完整断点恢复，也不能保证后台保活。

v3 运行迁移、持久记录与回执核对用例已有指定提交 CI 证据，见 HANDOFF §41；2.json/3.json 导出仍缺，最近运行界面、中断核对与真实设备升级仍需设备验证。既有 TXT 手工版的真机验收不自动覆盖这些增量。

## 批翻失败阶段增量（未构建）

HANDOFF §56 增加 REQUEST_FAILED 与 MARKING_FAILURE。前者结算失败尝试的执行阶段，允许已确认额度内的新 attempt；审计仍 DISPATCHED 的旧请求保持 needsReview。后者核对失败状态事务的命令回执，恢复时不把 ERROR 状态写入算作成功翻译。两者都不提供跨进程自动执行授权，也不更改 Room v4 结构。本增量尚无 CI/设备证据。
