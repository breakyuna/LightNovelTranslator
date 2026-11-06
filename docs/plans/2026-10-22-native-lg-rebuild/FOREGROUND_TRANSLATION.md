# 模拟翻译的前台执行切片

当前进度与验证边界统一见 [HANDOFF §3](HANDOFF.md)。当前代码基线 `1d3dd1c` 的 [Actions run 40329494778](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40329494778) 已通过 test（2268 项，0 失败）、lint、release 与两组 API 35 设备测试（app 2/2、workspace 13/13）；新增设备/视觉、真实供应商、ARM/多版本与 Room 2—18 schema 导出仍待补齐。

开发记录见 HANDOFF §34—36，早期证据见 [HANDOFF §41](HANDOFF.md)：提交 `8e74fff` 的 Actions #136 覆盖了当轮构建、测试与 Lint；当前总基线为 `77daf74` / #158，新增设备验收仍缺。范围扩展契约见 HANDOFF §39：用户显式选择本页或整工程本地模拟；Agent 对话已由 §78 接应用级前台执行器，远程 TXT 翻译已有 §43/§49 接线与 CI；队列、暂停和进程重启后的原任务自动接续仍未完成（§83—97 已补暂停与有证据边界的显式恢复）。

## 接线与任务身份

`Proofreading → NativeProjectViewModel → ForegroundTranslationRunner → NativeTranslationService`；服务成功进入前台后，执行器经 `DurableRunExecutor → TextTranslationService → NativeModelGateway → TranslationCommitPort/ProjectWriteStore` 完成既有模拟流水线。

应用级 NativeAppGraph 拥有执行作用域及唯一前台翻译执行器。接受请求时固定 Project/Instance/Generation、Run/Command、配置修订、预期工程修订和当前页 afterId/pageSize。同步取得真实 Runtime 运行租约后才异步启动；准备、运行和停止收尾期间不能接受其他工程写入或模型运行。原有直接 translatePage 接口继续供集成调用；前台入口交接已预留租约，不再次 beginRun，不建立第二条模型或写入口。

Intent 只含随机执行令牌，不携带正文、Key、项目路径或可重建任务的参数。只有同进程执行器中已存在的匹配令牌能打开工作门；旧令牌的停止或回调不能覆盖新执行。同一仍被保留的 commandId/完整请求返回原 handle，payload 变化拒绝；这不是跨进程/完整命令历史去重。执行器只保留最近一个执行及有限进度摘要。

## 前台生命周期

1. 请求同步预留，发布 WAITING_FOR_SERVICE；ATOMIC 启动把租约交给应用作用域，先保存持久执行记录。即使父作用域已取消也要完成登记/收尾。
2. 可见页面的显式用户动作调用 startForegroundService。Service 创建通知通道并成功 startForeground 后确认同一令牌；工作门未打开时不读翻译页、不请求模型、不提交条目。启动失败或等待超过 10 秒立即关闭确认入口，进入 STOPPING 收尾，最终报告 FAILED，持久记录收尾后才释放占用；失败已接受后再次停止不取消失败收尾、不改报 CANCELLED，不自动重试。前台确认与失败由同一状态转换仲裁，工作门与 Flow 的恢复在执行器锁外完成。
3. RUNNING 使用原有顺序分批、响应校验和事务提交；ViewModel 只等待结果/观察进度。取消界面等待者不取消任务；同一进程重建界面可绑定原 handle、工程和页范围。初始化跳过与活动任务冲突的目录恢复写占用，仍读取已有登记/回执；已有恢复问题数存入 SavedStateHandle，跳过恢复不清零。任务结束后补做目录恢复并刷新索引；恢复失败保留旧告警和待恢复标志，下次初始化/刷新再核对。
4. 页面或通知中的停止按令牌调用 Runtime Stop 并取消实际执行协程。STOPPING 保持占用，直到子工作、数据库事务及持久日志收尾；最后发布 SUCCEEDED/CANCELLED/FAILED，刷新已保存条目。迟到的进度不会覆盖已结束或新的执行。释放本次租约时，在 Runtime 锁内清除仍匹配本次 translation-workspace 的选中状态；延迟资源释放及旧租约清理不能清掉新任务的选择。
5. Service 为 non-exported、dataSync、stopWithTask=false、START_NOT_STICKY；不接开机启动或 Job/WorkManager 重试。NativeTranslationHost 在 Service 主线程处理平台生命周期：未拥有任务的空/未知/旧 Intent 退出当前 Service，不因全局另有待启动运行而空挂，也不确认/停止该运行；已拥有任务时旧通知不能停止它。终态被 StateFlow 合并、新令牌已准备而新 START 尚未到达时，撤掉旧通知并按最新 startId 退出，不取消新任务；新 START 先到则替换旧订阅。正常停止维持通知直到收尾，通知更新失败和系统超时则立即退出平台宿主。
6. onDestroy/onTimeout 只停止宿主实际拥有的任务；Android 15+ 的系统超时立即撤销前台状态并 stopSelf，应用作用域继续尽力完成受控收尾。进程被系统终止时仍由既有持久回执在下次启动核对，不自动重发或接续。

工程页通过 TextProjectPort.readPage 在同一事务内取得 summary 和有界 items，不再先读取 revision 再单独校验分页；模型/写入的预期修订检查保持不变。重附着在任何 IO 前取得 handle，即使首页/分页刷新失败仍等待并观察该任务，显示专用提示、保持忙碌和页面停止入口（工程摘要为空时也可停止）。任务终态后独立补做目录恢复、分页和持久记录刷新，刷新错误不替换任务本身的失败原因。保存的游标只在 token 匹配、从 0 严格递增且末尾匹配固定 afterId 时复用，否则回退到运行页；不从游标重建或重发任务。

执行器终态与持久核对状态须区分：第二批请求进行中停止，已提交第一批保留，执行器可以是 CANCELLED；没有已记录响应的 REQUESTING 在日志中仍为 INTERRUPTED/需核对。重新绑定或恢复核对不重发请求。持久日志最终写入失败时，执行器保留最后进度并报告 FAILED，不把已提交条目计数抹掉或宣称整次成功。

通知只显示本地模拟状态和已提交条数/请求次数，提供回到应用和停止操作，不显示工程名称、正文或凭据。Android 13+ 通知权限通过独立可选按钮申请，授权/拒绝回调不启动任务；拒绝权限仍可显式启动前台任务，通知展示受系统规则约束，页面停止入口保留。

平台依据：[前台服务启动](https://developer.android.com/develop/background-work/services/fgs/launch)、[dataSync 与本地文件处理](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)、[服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)、[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。这些是实现依据，不是本应用的设备验收证据。

## 验收与限制

§34—35 累计新增 23 个回归用例：JVM 执行器 10、宿主控制器 8、真实存储集成 2、平台接线 2、组件交互 1。覆盖启动门、同步占用、重复/冲突命令、旧令牌、界面等待者取消/重新绑定、启动失败/超时及失败后迟到确认、取消收尾、已取消作用域、迟到进度、日志最终写入失败、宿主错误和连续任务切换。真实存储还检查请求中停止后的已提交批次、未决状态与不重发；平台/组件用例检查 Manifest、无隐式恢复及可选通知授权不启动模拟。上述用例已纳入 §41 的 CI 全绿基线；宿主夹具不代替真实 Service/通知行为验证。

§36 另新增 8 个回归用例：游标纯函数 3、ViewModel/Robolectric 2、执行器 1、Runtime 1、存储 1。ViewModel 夹具使用真实存储及本地模型，在读取前后人为推进一个真实批次提交，再销毁并重建 ViewModel，检查同一 handle、页范围、进度、不重发与结束后目录恢复；另覆盖全部分页读取失败时仍可观察/停止及恢复失败保留告警。执行器检查启动失败后在异常处理前/日志收尾中再次停止均保持 FAILED；Runtime 检查资源释放前保留选择、释放后清理及旧租约隔离。上述用例已取得 §41 的 CI 运行证据；仍没有真实 Activity 重建的设备证据。

#158 已覆盖全量任务及新增用例的构建/测试证据；仍须在 PC/获授权 CI 录制并查看组件截图；设备须覆盖 API 24、26、33、35/36 的启动/停止、通知拒绝、切后台/锁屏/任务移除、Activity 重建、系统超时和进程死亡。前台 Service 不保证保活或屏幕关闭后的 CPU 进度，不申请 WakeLock，不增加自动恢复/付费调用。没有 Room/`.lnt` schema 变更；Room 为 v18，2—18.json 和已有视觉/供应商验收缺口继续保留。

## 整工程范围

`ForegroundTranslationRequest.scope` 冻结 PAGE / PROJECT 并进入重复命令的 payload 比较。PROJECT 只接受 afterId=0；同一命令更改范围拒绝，不复用旧运行授权。两种范围共享前台确认和同一个持久执行。

整工程分支在唯一租约内按当前修订和递增条目 ID 流式读取一页，沿用最多 20 条的逐批提交；空白/已处理/排除页不会截断后续扫描。请求、提交和用量跨页累计，不一次读取整本，也不为每页另建 Run。后续失败/停止保留前批，恢复核对不自动执行；显式新运行重新扫描仍未处理的条目。

UI 的范围选项不会启动工作，仍须确认。当前页没有待处理项但工程统计仍有未处理条目时可选择整工程；启用筛选、批选、草稿或未决批次时仍禁止启动，避免把筛选范围误当完整工程。整工程运行后页面回到首页，恢复视图使用请求绑定的同一范围/游标。Token 查看契约见 [MODEL_SCHEDULING](MODEL_SCHEDULING.md)。这不实现远程批翻、排队、暂停或同 Run 的跨进程接续。

预留使用 RuntimePort.beginRunSelectingChat，在运行时同一锁内校验身份、占用并同时更新选择和租约。认证写占用或身份校验失败时，原 selectedChat、快照修订和 activeRun 保持不变，不发布执行器会话、不启动 Service 或持久 Run；释放占用后只能由调用方显式重试。
