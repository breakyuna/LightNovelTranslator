# TXT 校对查询与统计契约

当前进度与验证边界统一见 [HANDOFF §3](HANDOFF.md)。提交 `77daf74` 的 [Actions #158 / 40085555783](https://github.com/breakyuna/LightNovelTranslator/actions/runs/40085555783) 已通过 test/lint/release 和签名校验，覆盖至 §101；§102—107 仅存在于工作区，未提交、未构建，§108 为姓名链路核对。新增设备/视觉、真实供应商与 Room 2—8 schema 导出仍待补齐。

本切片实现 R15/R32 的状态/文本查询基础，不代表完整校对台。唯一实施记录与验证边界见 [HANDOFF §3](HANDOFF.md)；完整目标仍见 [UI_SPEC U04](UI_SPEC.md)。

## 查询与快照

调用链为 `Proofreading → NativeProjectViewModel.queryProofreading → TextProjectPort.queryPage → NativeTextProjectStore`。查询固定完整 LocalProjectRef（Project/Instance/Generation），每页按稳定 ItemId 升序、afterId 排他游标读取，最多 100 条；UI 使用 40 条和一条前瞻，不使用列表位置当条目身份。

TextItemQuery 支持全部/未处理/已处理/已排除状态，搜索原文、译文或两者。状态与文本条件取交集；同一条目同时命中原文和译文也只计一次。文本是最多 256 个 UTF-16 代码单元的、区分大小写的字面子串，保留空白；拒绝 NUL 和不合法 Unicode。SQL 值全部使用参数绑定，不拼入用户文本，不解释正则或通配符。搜索依据 [SQLite instr](https://www.sqlite.org/lang_corefunc.html#instr)，不依赖新 Android 正则 API。查询对象的 toString 不包含搜索正文。

同一 SQLite 事务读取工程摘要、查询总命中数、当前有界条目和全工程三种状态计数；命中总数不受 afterId/页长影响。readPage 也提供全工程计数。统计额外提供 translatableUnprocessed，§60 源码（#158 覆盖）仅计启用资产中 NONE 且源文满足 Kotlin isNotBlank 的条目；普通未处理计数仍包括空白源文。SQL trim 的字符集按当前 Kotlin isWhitespace 派生，避免仅排除 ASCII 空格。整工程模拟资格使用该子集，不能把普通状态计数当作可翻译数。TextProjectPage 校验命中计数范围及状态计数总和与摘要一致。正文只为有界窗口读取；统计在数据库聚合，不把整本正文传入 UI，也不取得模型/写租约。既有 items 的预期修订检查保持不变，没有 schema、迁移或格式变更。

## 窗口与编辑

TextItemWindow 是 1—100 个严格递增、去重且自持快照的 ItemId。保存单条编辑仍走 ManualTextEdit/ProjectWriteStore 和原有修订/指纹检查；保存后通过 readItemWindow 重读同一批成员的最新值，不因状态或译文变化丢掉、替换或重排成员。缺失成员拒绝整个刷新，不返回部分窗口。

全工程统计和命中计数会反映最新提交，所以命中计数可以小于暂时保留的窗口成员数；再次应用、清除筛选、翻页或显式刷新时重新查询成员。若保存已提交而窗口刷新失败，保留已保存的预览/修订和稳定成员，清空失效统计并显示错误；不声称写入回滚，不显示虚假的零命中。

## 原生交互与恢复

校对页打开固定窗口的“筛选与统计”对话框（§41 修复）：全屏 Dialog 窗口承载居中限高卡片，表单在有界区域滚动，按钮固定在滚动区外，避免含文本框的 wrap-content 窗口持续重复测量。展示已应用快照的计数；控件是草稿，输入或更改下拉选项不访问工程。“应用筛选”才执行查询，“清除筛选”显式执行空查询，关闭/取消不改变已应用条件。没有变更的条件也可再次应用，以重查成员。查询失败保留上一条件、窗口和游标。

条件按完整工程身份写入 SavedStateHandle；重建 ViewModel 时恢复条件并重新查询第一页，不承诺跨进程固定成员/历史页接续。切换工程、实例或代际不继承原条件，回到首页清除查询状态。活动模拟重附着仍恢复其原始、无筛选的固定页，保留既有 handle，不创建任务。

当前本页模拟的请求只表达原始 afterId/pageSize，没有筛选请求契约。因此筛选生效时，组件及 ViewModel 均拒绝启动本页模拟，五语提示先清除筛选；不把屏幕上的少量命中条目误当成实际模拟范围。筛选统计不发模型请求、不产生审批或修改工程。

## 验证输入与尚未覆盖

§37 新增 17 个回归用例：domain 3、真实 SQLite/Room storage 7、ViewModel 3、原生组件 4。覆盖输入/窗口边界、字面特殊字符/Unicode/大小写、原译范围和状态交集、不重复计数、稀疏分页、编辑保留窗口、工程隔离、查询失败/状态恢复、提交后刷新失败、显式应用/取消/清除、忙碌禁用及筛选不扩大模拟范围。组件包含小横屏夹具及 `native-proofreading-query.png` 捕获入口；已取得 §41 的 CI 运行证据，尚未录制或查看截图。

#158 的全量构建、测试/Lint 已通过，见 HANDOFF §3。仍待 PC/获授权 CI 的截图录制与实际查看，以及设备的输入法、横屏、语言切换、返回和列表交互检查。SQL 全工程搜索/统计的目标设备耗时与取消延迟尚未测量，不登记性能结论；现有 TXT 字节/条数预算仍适用。

当前页批选、状态与清空译文的写入契约见 [MANUAL_BATCH](MANUAL_BATCH.md)，开发记录见 HANDOFF §38，当前 CI 与未验证边界见 §3。§46—47 另增警告/缺失术语筛选统计、上一条/下一条匹配定位与单条草稿替换源码，已由 #158 覆盖；§62/§63 已补普通查询与质量筛选集合的全量批写，§106/§107 工作区增量补全查询字面替换与正则/JavaScript 替换。仍须实现资产/完整术语筛选、姓名/PDF 页级事实、完整六类警告兼容与宽屏表格；本切片不关闭 R15/R32 或完整 S1/S2。

§45 另增完整编辑草稿的显式本地六类检查源码，已由 #158 覆盖，实际算法与局限见 [TEXT_QUALITY](TEXT_QUALITY.md)；后续 §46 的列表接线见 [QUALITY_SCAN](QUALITY_SCAN.md)，§47 草稿替换见 [EDITOR_TOOLS](EDITOR_TOOLS.md)。

## 资产范围增量（#158 覆盖）

HANDOFF §61 为 TextItemQuery 增加 assetId，查询、分页、相邻定位共用谓词。matchingStatistics 表示完整筛选范围的状态数，statistics 仍为全工程。资产筛选按工程身份恢复；已删除资产保持空结果，不能自动扩大范围。质量扫描按资产分页和预算，索引绑定完整查询。本增量已由 #158 覆盖；设备证据仍缺。
