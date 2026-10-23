# Agent 端点、模型发现与对话界面契约

实际进度、构建和设备证据只记入 [HANDOFF §33](HANDOFF.md)（原开发轮见 §29）。本文描述当前工作区源码契约；没有真实供应商联调或新 APK 设备验收。

## 1. 配置方式

Agent 页顶部的模型按钮进入设置。支持显式 HTTP / HTTPS API 基址、手动模型 ID、可选 API Key，以及用户主动获取模型列表。Ollama、LM Studio 快捷按钮只填写地址，不自动连接。

| 场景 | 示例基址 | 说明 |
|---|---|---|
| Android 本机 Ollama | `http://127.0.0.1:11434/v1` | localhost/127.0.0.1 指当前手机，不是另一台电脑 |
| 本机 LM Studio 兼容服务 | `http://127.0.0.1:1234/v1` | 只是地址预设，不代表 Android 上安装了 LM Studio |
| 电脑或局域网服务 | `http://192.168.1.10:1234/v1` | 使用用户实际提供的可达地址；不扫描网络 |
| IPv6 本机服务 | `http://[::1]:8080/v1` | 端口与服务的实际监听保持一致 |
| HTTPS / 反向代理 | `https://example.com/proxy/v1` | 保留前缀，不猜测版本或探测其他路径 |

可粘贴 `/chat/completions` 或 `/models` 操作 URL，仅对用户输入去除一次操作后缀；已保存基址的校验、凭据绑定比较和聊天/列表路径构建均保留完整路径。例如输入 `/api/models/chat/completions` 后基址是 `/api/models`，聊天仍请求 `/api/models/chat/completions`，列表请求 `/api/models/models`。重新打开设置以完整聊天操作 URL 回填，避免再次保存时把基址尾部误作操作后缀。需要 `/v1` 的服务须在地址中包含它；空路径不自动加版本。禁止 URL 内嵌账号密码、查询参数、fragment、无效端口和控制字符。HTTP 适用于用户选定的受信任本机/局域网服务，界面提示未加密；HTTPS 仍用系统 TLS 校验，不禁用证书检查。应用原 Manifest 已允许 cleartext，本轮未扩大 Manifest 权限或关闭 TLS 校验。

## 2. 凭据

- 非空 Key 延用独立 Keystore AES-GCM，按不可变配置修订绑定；不会进入 SavedStateHandle、Bundle、模型消息、调试或错误正文。
- 空 Key 是显式无认证模式：不读取/创建 Keystore 密钥，不发送空的 `Authorization: Bearer`。
- 同一标准化端点可显式勾选复用已保存 Key；UI 只保存该选择，不把旧 Key 解密回表单。新增 Key 替换当前配置；取消勾选且留空表示新配置不使用 Key，不删除旧修订的密文。
- 修改端点后不能隐式转发旧 Key。UI、模型发现端口和配置存储都核对标准化端点；用户如需迁移凭据须重新输入。旧的配置修订仍可按其原身份解析。

无认证模式以既有 encryptedKey 字段的空字符串表示，不变更表字段、Room v3 或 `.lnt` v1。v3 运行迁移已有指定提交 CI 证据，见 HANDOFF §33；导出 schema 和设备升级仍需补齐。

## 3. 获取模型列表

`NativeModelDialog → NativeProjectViewModel → NativeModelGateway → CompatibleModelCatalog → CompatibleHttpTransport`。

仅在点击后 `GET <base>/models`，不发送聊天、小说正文或推理测试，不创建 Agent 付费请求回执。认证占用阻止它与活动模型运行/配置修改竞争。列表请求最多 20 秒，继承 HTTP 取消和 2 MiB 响应预算；不重试、不跟随重定向。

解析兼容的 `data: [{id: string}]`，最多 1000 项，ID 最长 200 字符，拒绝控制字符/非法形状，去重并排序。列表可搜索选择；获取失败或空列表仍允许手填模型 ID。修改端点、临时 Key 或已存凭据选择会清空旧列表，避免把上一端点结果当作当前结果。错误在表单内呈现，不重复显示全局错误 snackbar；关闭正在加载的表单会取消该请求。

列表只证明服务器公布了模型 ID，不推断模型支持工具、视觉或思考，不加载/下载模型，也不保证生成时可用。仍只有兼容 Chat Completions 文本/工具传输；原生 Ollama `/api/tags`、Anthropic/Responses、OAuth 和完整模型能力探测不在该切片内。

协议来源：[Ollama 本地兼容接口](https://docs.ollama.com/api/openai-compatibility)、[LM Studio 模型列表](https://lmstudio.ai/docs/developer/openai-compat/models)、[Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)。这些材料说明接口/平台规则，不是本项目的联调证据。

## 4. 对话页面

对话时间线使用稳定消息 key，区分用户/助手/工具/系统，可选择预览文字。输入栏固定在底部并处理 IME，显示当前模型、新建/选择会话、发送与停止；发送/审批/恢复可用性继续来自应用状态。

列表仍只取最近 50 条轻量预览。完整消息、思考文本、工具参数/结果按消息 ID 单条加载，核对实例/代际/Chat，不在 Composable 中读库；详情可选择和复制。助手/工具消息落盘后刷新时间线；§30 后续工作区增加真实 SSE 增量预览，契约见 [STREAMING](STREAMING.md)。显示刷新失败不会额外请求模型。

本地查询与手工差异演练默认折叠；真实待审批卡始终独立展示，允许一次/拒绝和工程写入口不变。运行记录可展开，中断自动展开提示。切工程清空上一工程的消息详情、预览和运行列表，读取失败保持错误状态。

§29 模型发现/对话、§30 SSE 与后续单窗口表单已有指定提交 CI 证据，见 HANDOFF §33；消息编辑/分支、附件、输入队列、上下文压缩、完整模型档案/能力矩阵及后台 Service 仍未实现。新增原生组件截图用例必须在 CI/PC 录制并实际查看，不能用源码布局推断设备视觉通过。
