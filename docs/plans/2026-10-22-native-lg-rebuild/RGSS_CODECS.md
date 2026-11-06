# RGSS 归档、Marshal 与脚本写回

本文件记录 R28 当前源码边界，不是完整 RGSS 验收结论。进度与构建证据统一见 [HANDOFF §3](HANDOFF.md)；§72 源码已由 #158 覆盖，R28 仍未关闭。

## 1. 已实现核心

`core/formats/.../formats/rgss/` 提供纯 JDK 实现，无新增依赖：

- **RGSSAD v1**：XP `.rgssad` 与 VX `.rgss2a`（version byte 1），滚动 key、名称/大小/数据逐字节或逐 dword 解密；写入可读回。
- **RGSSAD v3**：VX Ace `.rgss3a`，基础 key `headerKey * 9 + 3`，offset/size/dataKey/nameLength 表、四字节循环名称 XOR、每文件 data key、16 字节终止块；规范布局写入可读回。
- **v3 压缩条目**：检测 `0x78 0x9C`，`java.util.zip.Inflater` 解压；写回保持条目的压缩/未压缩形态。
- **Ruby Marshal 4.8**：nil/bool/Fixnum/Bignum/Float/String/Symbol/Array/Hash/对象与 ivar/符号与对象 link；`u` user-defined opaque 节点可遍历/link且 payload 原样保留；容器先注册以支持共享引用和环；未知类型显式失败；String encoding ivar 可往返。
- **Scripts 数据**：`[id, name, zlib-deflated code]` 数组读写，支持 UTF-8/BOM/Shift_JIS，未修改脚本保留原始字节。
- **Data 数据文件**：`Data/<name>.rxdata/.rvdata/.rvdata2` 的 Marshal 字符串经 `RgssDataCodec` 白名单扫描（RPG 对象 `@name/@nickname/@description/@note` 与事件命令文本参数），以 distinct 节点 preorder + 父路径 + 原字节摘要定位，只替换目标字符串的长度前缀/正文；其他节点、对象 link、编码 ivar 和归档成员逐字节保留。
- **Ruby 源码定位**：普通单双引号字面量按 UTF-16 边界扫描，支持常见转义、注释、`__END__`、quoted symbol；插值、heredoc、百分号/正则字面量等不确定结构整脚本拒绝，不做全局替换。
- **适配器与服务**：`RgssFormatAdapter` 实现 `BinaryFormatAdapter`（归档或裸 Scripts/Data 读取脚本字面量与数据字符串；写回仅替换 `PROCESSED` 译文，其他归档成员逐字节保留）；`RgssProjectService` 提供归档/目录与内存文件列表互转，并新增目录级 `scanData`/`replaceData`。

## 2. 边界与限制

- Data 文件只覆盖白名单字段；符号、路径、资源名、数字、格式串、插件 note、不可解码字符串和无法证明编码的旧文件会跳过或整文件告警，不能宣称 R28 全量数据闭环完成。
- v3 zlib 只按 `78 9C` 头识别；其他合法 zlib 头或恰好以该字节开头的未压缩数据存在固有限制。
- 归档名称/脚本编码支持 UTF-8 与 Shift_JIS；Data 写回同样只支持 UTF-8/Shift_JIS，不支持 GB18030/UTF-16 等。
- Marshal 不支持 `U/e/C/S/c/m/d///M` 等类型；Float 二进制尾数不支持。
- Ruby 扫描器不是完整 AST；不支持结构整脚本跳过，可能误拒极端写法。
- 目录服务使用 `java.io.File`，未直接接 SAF；导出不是跨文件原子事务，调用方需在后台线程和 Android 存储边界内使用。
- 当前只注册格式与适配器；尚未做真实 RPG Maker 工程/设备往返验收，也没有 UI 格式标签与目录选择器。

## 3. 装配

- `NativeFormatAdapters` 注册 `RgssFormatAdapter`，`ProjectFormatService` 通过 `FormatAdapterRegistry.binary` 读取/写回。
- `ProjectAssetFormat.detect` 将 `.rgssad/.rgss2a/.rgss3a`、`Scripts.rxdata/.rvdata/.rvdata2` 以及 `Data/` 目录下的 `.rxdata/.rvdata/.rvdata2` 识别为 `RPGMAKER_RGSS`；其他位置的泛用数据文件仍为 `BINARY`，避免抢占非 RGSS 内容。
- 目录级导入/导出需应用层把 SAF 目录树桥接到 `RgssProjectService` 的 `java.io.File` 接口或先复制到应用私有目录。

## 4. 验证边界

新增 53 个既有 JUnit4 用例，覆盖 v1/v3 往返、压缩、损坏/穿越拒绝、Marshal 往返与 link、Scripts 编解码、Ruby 字面量定位/拒绝、Adapter/Registry 识别。§110 又新增 RGSS Data codec/adapter 测试源码（字符串白名单、节点定位、编码、写回、归档共存、预算/拒绝）。全部未实际运行；未做真实 RPG Maker 运行时/设备验收。
