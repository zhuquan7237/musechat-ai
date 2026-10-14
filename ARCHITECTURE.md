# 架构

## 分层

- ui：Compose 页面、AndroidViewModel、StateFlow、确认与媒体操作。
- network：HttpTransport、ChatApiClient、ImageApiClient，各自配置和凭据。
- agent：AgentOrchestrator、AgentTool、ToolRegistry、GenerateImageTool。
- data：Room 会话/消息/图片/配置、DataStore、Keystore、私有原图。
- core：配置模型、协议消息与 Agent 状态。

Application 容器直接注入，Agent 不依赖 Android UI；测试可替换 AgentStore / ImageStorage，真实 App 使用 Room 和真实文件。

## 工具调用链路

~~~mermaid
sequenceDiagram
    participant U as 原生聊天页
    participant A as Agent
    participant C as 聊天服务商
    participant I as 独立生图服务商
    participant D as Room / 私有文件
    U->>D: 保存用户消息
    U->>A: 启动唯一任务
    A->>C: messages + 动态 tools
    C-->>A: 文本 / tool_calls / SSE 分片
    A->>D: 保存 assistant tool_calls
    A->>A: 校验参数与限额
    opt 确认模式
        A->>U: 展示参数
        U-->>A: 允许 / 拒绝
    end
    A->>I: Images API + 独立凭据
    I-->>A: URL / Base64
    A->>D: 原图 + 图片元数据 + tool 结果
    D-->>U: 展示图片
    A->>C: 上下文 + 对应 tool_call_id 的结果
    C-->>U: 后续回复
~~~

仅结构化 tool_calls 驱动工具，Schema 根据尺寸、quality 与数量能力生成，执行前严格验证。多工具按索引排序，逐个执行，各自结果保留 ID；超限仍返回结构化失败，保持协议完整。

SSE 按空行分隔事件，多 data 行合并。ID、名称与 JSON arguments 按 index 缓冲，到模型响应结束才执行。流中断、非法 JSON、截断、缺失或重复 ID 不会触发生图。

## 状态与持久化

状态包含模型请求、工具接收、执行、下载、完成、模型继续、失败、取消，不虚构百分比。StateFlow 展示当前状态，Room 持久化完整 assistant 工具请求及每个 tool 成功/失败结果。

原图先写临时文件，验证格式和尺寸后保存；图片元数据与 tool 结果通过同一个 Room 事务提交。结果只含状态、图片 ID、尺寸、数量和保存信息，不含 Base64、API Key 或私有路径。

配置变化保留 ViewModel 任务，避免重建重复生图。协程取消绑定 OkHttp Call。发送与 API 测试共用互斥，避免快速重复点击和重复计费。

启动时为未匹配结果的 tool_calls 补充“进程中断”的失败结果，不重新执行，并清理孤立文件。没有前台服务，系统可能终止后台进程，不能保证后台必然完成。

## 网络与安全

生产 API 与下载仅 HTTPS；拒绝 API URL 嵌入凭据、查询参数和片段，禁止重定向降级。仅测试 HttpTransport 可允许本地 HTTP；生产不启用。下载不发送任何 API Key。

关闭 OkHttp 自动连接重试；付费请求失败不重试，失败后禁用本轮工具并向模型反馈。用户主动请求才可再次生图。每轮有限额，模型持续调用也会停止。

单图限 16 MB / 6400 万像素，图库限 512 MB；URL 流式落盘，JSON 限 48 MB，SSE 行/事件限 1 MB，参数限 64 KB。Keystore 密钥不可导出，配置与凭据分离，禁用备份。

## 扩展

新工具实现 AgentTool 定义、校验、执行，再加入 ToolRegistry。当前只实现 generate_image。ImageProviderAdapter 处理明确格式；图生图需要新请求适配器、能力声明、Photo Picker 与编辑工具，当前尚未实现。
