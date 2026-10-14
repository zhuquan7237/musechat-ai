package io.github.zhuquan7237.musechat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zhuquan7237.musechat.data.UserPreferences

@Composable fun AppSettingsScreen(vm: AppViewModel, prefs: UserPreferences) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("工具与费用保护", style = MaterialTheme.typography.titleLarge)
        ChoiceRow("工具执行模式", listOf("auto" to "自动执行（默认）", "confirm" to "执行前展示参数并确认", "disabled" to "禁用工具，只进行文字聊天"), prefs.mode) { vm.stringPref("mode", it) }
        Text("每轮最多调用 ${prefs.maxCalls} 次")
        Slider(prefs.maxCalls.toFloat(), { vm.numberPref("maxCalls", it.toInt().coerceIn(1,8)) }, valueRange = 1f..8f, steps = 6)
        Text("每次最多生成 ${prefs.maxImages} 张（还受服务商能力限制）")
        Slider(prefs.maxImages.toFloat(), { vm.numberPref("maxImages", it.toInt().coerceIn(1,8)) }, valueRange = 1f..8f, steps = 6)
        ToggleRow("自动优化生图提示词", prefs.optimize) { vm.boolPref("optimize", it) }
        Text("生图失败后不会自动再次生图。停止任务只取消本地连接，不能保证远程停止计费。切换到后台不重复执行；系统终止进程后不会自动重试。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("外观", style = MaterialTheme.typography.titleLarge)
        ChoiceRow("主题", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), prefs.theme) { vm.stringPref("theme", it) }
        ToggleRow("系统动态配色（Android 12+）", prefs.dynamic) { vm.boolPref("dynamic", it) }
        HorizontalDivider()
        Text("数据与隐私", style = MaterialTheme.typography.titleLarge)
        Text("无需平台账号，没有开发者代理、广告或遥测。消息发送到你指定的聊天服务商，生图提示词发送到独立的生图服务商。图片 URL 会直接下载到应用私有存储。")
        Text("单图上限 16 MB、6400 万像素，私有图库上限 512 MB。可在图库删除图片，在历史列表删除会话及其图片。系统相册副本独立保留。卸载会删除私有数据。")
        Text("当前版本 0.1.0 · 仅文生图", style = MaterialTheme.typography.labelLarge)
        Text("图片编辑、参考图上传、多参考图、MCP、Responses 和数据导入导出尚未实现。第三方模型是否支持工具调用须通过设置页实测。", style = MaterialTheme.typography.bodySmall)
    }
}
