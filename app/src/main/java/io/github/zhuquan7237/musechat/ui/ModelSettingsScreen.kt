package io.github.zhuquan7237.musechat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.agent.safeError
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun ModelSettingsScreen(vm: AppViewModel, configs: List<ModelConfig>, busy: Boolean) {
    var editing by remember { mutableStateOf<ModelConfig?>(null) }
    var deleting by remember { mutableStateOf<ModelConfig?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("连接你自己的 AI", style = MaterialTheme.typography.headlineSmall)
        Text("聊天与生图分别配置，应用直接访问你指定的服务商。API Key 使用 Android Keystore 加密保存在手机。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = ModelConfig(kind = "chat") }, enabled = !busy) { Text("添加聊天模型") }
            OutlinedButton(onClick = { editing = ModelConfig(kind = "image") }, enabled = !busy) { Text("添加生图模型") }
        }
        configs.forEach { config -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(config.name, style = MaterialTheme.typography.titleMedium)
            Text((if (config.kind == "chat") "聊天 · " else "生图 · ") + config.model)
            Text(config.baseUrl, style = MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick = { editing = config }, enabled = !busy) { Text("编辑与测试") }; TextButton(onClick = { deleting = config }, enabled = !busy) { Text("删除") } }
        } } }
        Text("当前支持 OpenAI Chat Completions 与 Images Generations。Responses、图片编辑与聊天式生图尚未实现。", style = MaterialTheme.typography.bodySmall)
    }
    editing?.let { config -> ModelEditor(vm, config) { editing = null } }
    deleting?.let { config -> ConfirmDialog("删除配置？", "将删除 ${config.name} 及其加密 API Key。", { deleting = null }) { vm.deleteConfig(config); deleting = null } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ModelEditor(vm: AppViewModel, initial: ModelConfig, close: () -> Unit) {
    var config by remember(initial.id) { mutableStateOf(initial) }
    var key by remember(initial.id) { mutableStateOf(vm.key(initial)) }
    var showKey by remember { mutableStateOf(false) }
    var sizes by remember { mutableStateOf(initial.sizes.joinToString(",")) }
    var qualities by remember { mutableStateOf(initial.qualities.joinToString(",")) }
    var temp by remember { mutableStateOf(initial.temperature?.toString().orEmpty()) }
    var tokens by remember { mutableStateOf(initial.maxTokens?.toString().orEmpty()) }
    var count by remember { mutableStateOf(initial.maxImages.toString()) }
    val persistedResult by vm.testResult.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf(persistedResult) }
    val testing by vm.testing.collectAsStateWithLifecycle()
    var cost by remember { mutableStateOf(false) }
    fun current(): ModelConfig {
        require(temp.isBlank() || temp.toDoubleOrNull() != null) { "Temperature 必须是数字" }
        require(tokens.isBlank() || tokens.toIntOrNull() != null) { "Max Tokens 必须是整数" }
        require(count.toIntOrNull() != null) { "图片数量必须是整数" }
        return config.copy(sizes = sizes.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct(), qualities = qualities.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct(), temperature = temp.toDoubleOrNull(), maxTokens = tokens.toIntOrNull(), maxImages = count.toInt())
    }
    fun test(type: String) {
        if (testing) return
        try { val c = current(); result = "正在请求服务商…"; vm.test(c, key, type) { result = it } } catch (e: Exception) { result = safeError(e, key) }
    }
    Dialog(onDismissRequest = { if (!testing) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column {
            TopAppBar(title = { Text(if (config.kind == "chat") "聊天 API" else "生图 API") }, navigationIcon = { IconButton(onClick = close, enabled = !testing) { Icon(Icons.Default.Close, "关闭") } }, actions = {
                TextButton(onClick = { try { vm.saveConfig(current(), key, close) } catch (e: Exception) { result = safeError(e, key) } }, enabled = !testing) { Text("保存") }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField("配置 / 服务商名称", config.name) { config = config.copy(name = it) }
                FormField("API Base URL（包含 /v1 等前缀）", config.baseUrl) { config = config.copy(baseUrl = it) }
                OutlinedTextField(key, { key = it }, label = { Text("API Key（可留空）") }, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示或隐藏密钥") } })
                FormField("模型 ID（可手动填写）", config.model) { config = config.copy(model = it) }
                if (config.kind == "chat") {
                    ToggleRow("流式输出（SSE）", config.streaming) { config = config.copy(streaming = it) }
                    ToggleRow("启用原生 Tool Calling", config.tools) { config = config.copy(tools = it) }
                    Text("模型须原生支持 tools/tool_calls。不支持时关闭此项，仍可正常聊天。", style = MaterialTheme.typography.bodySmall)
                    FormField("Temperature（可留空）", temp) { temp = it }
                    FormField("Max Tokens（可留空）", tokens) { tokens = it }
                    Row { OutlinedButton(onClick = { test("chat") }, enabled = !testing) { Text("测试聊天") }; Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { test("tools") }, enabled = !testing) { Text("检查工具能力") } }
                    Text("工具能力测试仅请求聊天模型，不执行生图；聊天服务商可能计费。", style = MaterialTheme.typography.bodySmall)
                } else {
                    FormField("生图接口路径", config.imagePath) { config = config.copy(imagePath = it) }
                    FormField("支持的 size（逗号分隔；留空则不发送）", sizes) { sizes = it }
                    FormField("默认 size", config.defaultSize) { config = config.copy(defaultSize = it) }
                    Text("请填写服务商实际支持的尺寸，例如 1024x1024、1024x1536、1536x1024。9:16 等创作比例不能替代接口的 size 枚举。", style = MaterialTheme.typography.bodySmall)
                    FormField("服务商允许的单次图片数量（1–8）", count) { count = it }
                    FormField("支持的 quality（逗号分隔；留空则不提供）", qualities) { qualities = it }
                    OutlinedTextField(config.extraJson, { config = config.copy(extraJson = it) }, label = { Text("高级请求参数（JSON 对象）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    ChoiceRow("响应适配器", listOf("openai" to "标准 data[]", "configured" to "自定义明确字段"), config.imageAdapter) { config = config.copy(imageAdapter = it) }
                    if (config.imageAdapter == "configured") {
                        FormField("顶层数组字段", config.customArrayField) { config = config.copy(customArrayField = it) }
                        FormField("图片 URL 字段", config.customUrlField) { config = config.copy(customUrlField = it) }
                        FormField("Base64 字段", config.customBase64Field) { config = config.copy(customBase64Field = it) }
                        Text("只支持该数组内的明确字段映射，不猜测嵌套 JSON 或聊天式生图协议。", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { cost = true }, enabled = !testing) { Text("测试真实生图（可能收费）") }
                }
                OutlinedButton(onClick = { test("models") }, enabled = !testing) { Text("获取模型列表") }
                if (testing) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::stop) { Text("取消测试（远程仍可能计费）") } }
                if (result.isNotBlank()) SelectionContainer { Text(result, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                Text("保存后可在聊天页选择配置。密钥不会进入模型配置 JSON、普通日志或仓库。", style = MaterialTheme.typography.bodySmall)
            }
        } }
        if (cost) ConfirmDialog("发起真实付费请求？", "此测试会请求你的生图服务商生成一张图片，可能产生费用，结果保存到当前聊天。不自动重试。", { cost = false }) { cost = false; test("image") }
    }
}
@Composable fun FormField(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
@Composable fun ToggleRow(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, update) }
}
@Composable fun ChoiceRow(label: String, choices: List<Pair<String,String>>, selected: String, update: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    choices.forEach { (id, title) -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(selected == id, { update(id) }); TextButton(onClick = { update(id) }) { Text(title) } } }
}
