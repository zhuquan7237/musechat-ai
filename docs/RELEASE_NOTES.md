# MuseChat AI 0.1.0 预览版

原生 Android AI Agent 客户端：聊天模型通过真正的 tool_calls 调用独立配置的生图 API，原图保存到手机并显示在同一聊天中，工具结果继续回传模型。

包含中文 Compose 界面、流式聊天、多模型配置、自动/确认/禁用工具、费用限额、图库、缩放、分享、相册保存、Room 持久化与 Keystore 加密凭据。

验证：29 项单元测试、7 项模拟器仪器测试通过；Lint 0 错误；APK 签名、16 KB ZIP / 原生库对齐检查通过。

下载 MuseChat-AI-0.1.0-debug.apk 安装，最低 Android 8.0。SHA256SUMS.txt 提供校验和。使用说明见仓库 README 和 CONFIGURATION.md。

这是 Debug 签名预览版。真实手机和真实付费服务商未验证，图片编辑/参考图尚未实现。第三方工具能力和尺寸由服务商决定，取消本地请求不保证停止远程计费。正式长期更新应使用自己的固定签名证书。
