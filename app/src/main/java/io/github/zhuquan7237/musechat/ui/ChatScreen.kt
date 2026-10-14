package io.github.zhuquan7237.musechat.ui

import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.data.*
import io.noties.markwon.Markwon
import kotlinx.serialization.json.*
import java.io.File

@Composable fun ChatScreen(vm: AppViewModel, configs: List<ModelConfig>, prefs: UserPreferences, images: List<ImageEntity>, preview: (ImageEntity) -> Unit, save: (ImageEntity) -> Unit, share: (ImageEntity) -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var input by rememberSaveable(selected) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(messages.size, partial.length, images.size) { if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ConfigSelector("聊天", configs.filter { it.kind == "chat" }, prefs.chatId, !busy) { vm.stringPref("chatId", it) }
            ConfigSelector("生图", configs.filter { it.kind == "image" }, prefs.imageId, !busy) { vm.stringPref("imageId", it) }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (prefs.mode == "confirm") "执行前确认" else "自动生图", style = MaterialTheme.typography.labelMedium)
            Switch(checked = prefs.mode != "disabled", onCheckedChange = { vm.stringPref("mode", if (it) "auto" else "disabled") }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp))
            Text("每轮 ≤ ${prefs.maxCalls} 次 · 每次 ≤ ${prefs.maxImages} 张", style = MaterialTheme.typography.labelSmall)
        }
        if (configs.none { it.kind == "image" }) Text("尚未配置生图 API，可以正常文字聊天。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (messages.isEmpty() && !busy) Box(Modifier.weight(1f)) { EmptyState("从一句灵感开始", "描述你想创作的画面，模型会主动调用你的生图 API。也可以只聊创意。请先在模型页配置服务商。") }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { it.id }) { message ->
                if (message.kind == "error") Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Text(message.text, Modifier.padding(12.dp)) }
                else {
                    val wire = remember(message.wire) { json.parseToJsonElement(message.wire).jsonObject }
                    val role = wire["role"]?.jsonPrimitive?.content
                    val text = wire["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    when (role) {
                        "user", "assistant" -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (role == "user") Arrangement.End else Arrangement.Start) {
                                Surface(Modifier.widthIn(max = 560.dp), color = if (role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(if (role == "user") "你" else "MuseChat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        if (text.isNotBlank()) { if (role == "assistant") MarkdownText(text) else SelectionContainer { Text(text) } }
                                        wire["tool_calls"]?.jsonArray?.forEach { call ->
                                            val f = call.jsonObject["function"]!!.jsonObject
                                            Text("工具：" + f["name"]!!.jsonPrimitive.content, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                            SelectionContainer { Text(f["arguments"]!!.jsonPrimitive.content, style = MaterialTheme.typography.bodySmall) }
                                        }
                                        if (text.isNotBlank()) TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, contentPadding = PaddingValues(0.dp)) { Text("复制", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                        }
                        "tool" -> {
                            val result = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                            val success = result?.get("success")?.jsonPrimitive?.booleanOrNull == true
                            Text(if (success) "✓ 工具完成 · 图片已保存本地" else "工具未完成：" + (result?.get("error")?.jsonPrimitive?.contentOrNull ?: text), color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            val id = wire["tool_call_id"]?.jsonPrimitive?.content
                            images.filter { it.toolCallId == id }.forEach { image ->
                                ImageCard(image, preview, save, share) { vm.send("请重新生成一张图片，使用以下提示词：" + image.prompt) }
                            }
                        }
                    }
                }
            }
            if (partial.isNotBlank()) item { MarkdownText(partial) }
            if (busy || state in listOf(AgentState.FAILED, AgentState.CANCELLED)) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (messages.isNotEmpty() && !busy) TextButton(onClick = vm::regenerate, modifier = Modifier.align(Alignment.End)) { Text("重新发送上一条请求（可能产生费用）", style = MaterialTheme.typography.labelSmall) }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { vm.notice.value = "当前版本仅支持文生图。参考图上传和图片编辑属于第二阶段，尚未实现。" }) { Icon(Icons.Default.AddPhotoAlternate, "参考图片能力说明") }
            OutlinedTextField(input, { if (it.length <= 16000) input = it }, modifier = Modifier.weight(1f), placeholder = { Text("聊创意，或描述想生成的画面…") }, maxLines = 6, shape = MaterialTheme.shapes.large, enabled = ready && !busy)
            if (busy) IconButton(onClick = vm::stop) { Icon(Icons.Default.StopCircle, "停止任务", tint = MaterialTheme.colorScheme.error) }
            else IconButton(onClick = { if (vm.send(input.trim())) input = "" }, enabled = ready && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}
@Composable fun ConfigSelector(label: String, configs: List<ModelConfig>, selected: String, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && configs.isNotEmpty()) { Text("$label：" + (configs.firstOrNull { it.id == selected } ?: configs.firstOrNull())?.name.orEmpty()); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded, { expanded = false }) { configs.forEach { config -> DropdownMenuItem(text = { Text(config.name + " · " + config.model) }, onClick = { select(config.id); expanded = false }) } }
    }
}
@Composable fun MarkdownText(text: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(factory = { TextView(it).apply { textSize = 16f; setTextIsSelectable(true) } }, modifier = Modifier.fillMaxWidth(), update = { it.setTextColor(color); markwon.setMarkdown(it, text) })
}
@Composable fun ImageCard(image: ImageEntity, preview: (ImageEntity) -> Unit, save: (ImageEntity) -> Unit, share: (ImageEntity) -> Unit, regenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        AsyncImage(File(image.path), image.prompt, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp).clickable { preview(image) })
        Text("${image.width} × ${image.height} · ${image.model}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { preview(image) }) { Text("原图") }; TextButton(onClick = { save(image) }) { Text("保存") }; TextButton(onClick = { share(image) }) { Text("分享") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(image.prompt)) }) { Text("提示词") }; TextButton(onClick = regenerate) { Text("再生成") }
        }
    }
}
