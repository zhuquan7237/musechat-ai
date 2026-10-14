# API 与工具配置

## 聊天配置

仅支持 OpenAI 风格 Chat Completions。Base URL 为 API 前缀，例如 https://chat.example.com/v1，模型 ID 由服务商提供。客户端请求该前缀下的 /chat/completions，不要填网页首页。

可保存多份配置，在聊天页切换。Streaming 控制 SSE，Tool Calling 控制是否提供工具。Temperature / Max Tokens 可留空；某些模型不接受这些字段，留空即不发送。

“获取模型列表”请求 /models，不能证明工具能力。“检查工具能力”仅发送原生工具定义并检查 tool_calls，不执行生图；未返回工具调用表示此次测试未确认能力。聊天测试可能产生聊天费用。

不支持工具的模型可关闭 Tool Calling 正常聊天，不用关键词或 JSON 文本模拟工具。

## 生图配置

独立填写生图 Base URL、模型 ID 与 API Key，例如 https://image.example.com/v1。默认 /images/generations 可改为服务商明确支持的路径。拼接消除重复前缀；推荐 Base URL 填 API 根前缀。

填写实际支持的 size 枚举，例如 1024x1024,1024x1536,1536x1024。默认值须在列表中。不接受 size 的服务商可将支持列表留空，Schema 和请求都不发送 size。不要填任意比例标签如 9:16。

数量取应用限额与服务商配置的较小值，范围 1–8，请求使用整数 n。不接受 n 的服务商需专用适配器，当前不兼容。quality 列表留空则不提供此参数。

高级 JSON 可以添加接口允许的参数，例如 {"response_format":"b64_json"}。禁止覆盖 model、prompt、size、n、quality、stream；不要给不接受该字段的服务商发送它。

### 响应适配器

- 标准：data[].url / data[].b64_json。
- 明确字段：填写顶层数组名、数组内 URL/Base64 字段名。{"outputs":[{"encoded":"..."}]} 对应 outputs / url / encoded。

仅支持明确的顶层数组映射，不猜测嵌套结构，不兼容任意聊天式生图。请求仍为 Images Generations JSON POST。

临时图片 URL 必须是 HTTPS；下载不携带任何 API Key，不支持需要额外 Authorization 的下载地址。Base64 应为原始编码，不能含 data:image 前缀。支持 PNG、JPEG、WebP。

“测试真实生图”需要用户确认，生成一张简单蓝色圆形图片并保存到当前聊天，可能收费。网络任务互斥，失败不自动重试。

## Agent 设置

默认自动执行，每轮最多 2 次、每次 1 张。执行前确认展示参数；禁用工具只文字聊天。模型工具能力、生图配置和当前模式共同决定工具可用性。

提示词优化改变模型指令，模型仍可能不完全遵从，确认模式可检查提示词。重新发送和图片“再生成”发起新请求，可能再次收费，也不保证角色一致性。

## 安全与签名

密钥通过 Keystore 中的 AES 密钥进行 AES-GCM 加密。删除配置删除对应凭据；不能在另一台设备直接解密，系统备份已禁用。不要把密钥写入 Base URL、聊天或高级 JSON。错误只显示 HTTP 状态，不展示可能回显凭据的服务商原始错误体。

构建要求：JDK 17、SDK 36、AGP 8.11.1、Gradle 8.14、Kotlin 2.1.20。使用 ANDROID_HOME 或不入库的 local.properties 设置 SDK。Windows 推荐英文路径，中文路径可能导致测试类加载失败。

正式签名环境变量：

| 名称 | 用途 |
| --- | --- |
| SIGNING_STORE_FILE | keystore 绝对路径 |
| SIGNING_STORE_PASSWORD | keystore 密码 |
| SIGNING_KEY_ALIAS | 签名别名 |
| SIGNING_KEY_PASSWORD | 私钥密码 |

运行 ./gradlew :app:assembleRelease。后续覆盖安装需要同一证书。

GitHub 手动签名工作流需 Secrets：SIGNING_STORE_BASE64（keystore 的 Base64）、SIGNING_STORE_PASSWORD、SIGNING_KEY_ALIAS、SIGNING_KEY_PASSWORD。临时目录解码，只上传 APK 和校验和，不打印密钥、不自动发布商店。

Debug APK 可安装，使用调试证书；未配置密钥的 Release APK 不可安装。
