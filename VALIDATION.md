# 验证记录

验证日期：2026-10-14。此记录区分实际执行与尚未执行的验收，不能代替真实手机和具体付费服务商验证。

## 实际执行

| 检查 | 结果 |
| --- | --- |
| Gradle Debug APK 构建 | 通过 |
| 单元 / MockWebServer 测试 | 29 项通过，0 失败 |
| Android 仪器测试 | 7 项通过，0 失败 |
| Android Lint | 0 错误，19 警告；主要是固定依赖版本和 KTX 建议 |
| 模拟器 APK 安装与冷启动 | 通过 |
| 原生聊天、图库、模型、设置与配置表单 | UI 冒烟测试通过 |
| 图片在当前聊天与图库显示 | UI 仪器测试通过（明确标注的本地测试 fixture） |
| HTTPS 工具完整链路 | 模拟 TLS 服务 → 生图 URL → 真正下载与原图落盘 → Room → tool_call_id 回传，通过 |
| 原图、工具记录持久化 | 数据库关闭后重新打开，原图与关联记录保留 |
| Keystore 凭据加密与删除 | 加密值不含明文、重新读取与删除测试通过 |
| 中断恢复 | 补充失败结果，不重复执行工具，测试通过 |
| APK v2 签名 | apksigner verify 通过（Debug 证书） |
| APK ZIP 对齐 | zipalign -c -P 16 4 通过 |
| 原生库 ELF LOAD 对齐 | 8 个库、四种 ABI 均至少 16384 字节 |

测试环境：Windows、JDK 17、Android SDK 36、Gradle 8.14；模拟器 API 37、x86_64、16 KB 内存页。项目路径含中文时 Gradle 单元测试类加载失败，使用同一源码的英文路径构建副本完成验证。

本机 Gradle 的 connectedDebugAndroidTest 遇到 UTP gRPC 监听服务启动失败，因此以原生 AndroidJUnitRunner 直接执行同一仪器测试 APK：

~~~powershell
adb install -r app-debug.apk
adb install -r app-debug-androidTest.apk
adb shell am instrument -w io.github.zhuquan7237.musechat.test/androidx.test.runner.AndroidJUnitRunner
~~~

输出：OK (7 tests)。旧 Espresso 在 API 37 上的 InputManager 兼容问题已通过使用 Espresso 3.7.0 修复。

## 单元测试范围

普通文字响应、SSE 文本、拆分 ID/工具名/arguments、多工具交错、截断和无效流、重复 ID、非法参数、动态 Schema、自动执行、确认拒绝、禁用工具、调用限额、连续生成不覆盖旧图、独立服务商/凭据、标准 URL/Base64 和明确字段适配器、401/429/500、超时、取消、空/非法响应、高级参数覆盖保护和无自动付费重试。

## APK

- 文件：MuseChat-AI-0.1.0-debug.apk。
- 包名：io.github.zhuquan7237.musechat。
- 版本：0.1.0 / versionCode 1。
- 最低 SDK 26，目标 SDK 36。
- ABI：arm64-v8a、armeabi-v7a、x86、x86_64。
- 大小：19,709,646 字节。
- SHA-256：830048384e54025de64a9d6bb927433fc1e4561747b6922c11abd1c9765a4b18。

Release 附件为本机验证过的 Debug APK；Actions 产物独立构建，证书和哈希可能不同。正式签名工作流已提供，但未执行，因为未配置用户的正式签名密钥。

## 尚未执行 / 尚未实现

- 真实手机安装、不同 Android 版本和实际网络环境测试未执行。
- 未使用真实聊天/生图密钥请求付费服务商；模拟测试不保证每个第三方 API 兼容。
- 不保证远程请求取消后停止计费，不提供无进程存活条件的后台任务保证。
- 图片编辑、参考图上传、角色一致性、多参考图、Responses、聊天式生图、MCP、任务队列和数据导入导出未实现。
- Android 8–9 的相册权限流程、系统分享目标和各机型缩放体验尚需真实设备验证。

GitHub 云端构建状态和报告以仓库 Actions 为准；该工作流运行单元测试、Lint 和 Debug APK 构建，不运行设备仪器测试。
