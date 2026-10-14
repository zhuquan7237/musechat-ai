# 贡献指南

反馈请记录 App / Android 版本、服务商协议、HTTP 状态与复现步骤，移除真实密钥、私人聊天和带访问签名的 URL。

开发环境：JDK 17、Android SDK 36。运行：

~~~bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
~~~

修改图片落盘、凭据或 UI 时还应运行 :app:connectedDebugAndroidTest。

工具与服务商功能必须使用真实协议，不添加关键词触发或假图片。新增适配器须说明格式，测试取消、错误、不自动重试付费请求与凭据隔离。

PR 说明具体行为、实际验证与未测试限制。不要提交 API Key、keystore、local.properties 或私人数据。
