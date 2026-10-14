package io.github.zhuquan7237.musechat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.agent.safeError
import io.github.zhuquan7237.musechat.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MuseApp(vm: AppViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dark = when (prefs.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (prefs.dynamic && Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if (dark) darkColorScheme(primary = Color(0xFFBEBBFF)) else lightColorScheme(primary = Color(0xFF5E5CE6))
    MaterialTheme(colorScheme = colors) {
        val configs by vm.configs.collectAsStateWithLifecycle()
        val sessions by vm.sessions.collectAsStateWithLifecycle()
        val images by vm.images.collectAsStateWithLifecycle()
        val selected by vm.selected.collectAsStateWithLifecycle()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val ready by vm.ready.collectAsStateWithLifecycle()
        val notice by vm.notice.collectAsStateWithLifecycle()
        val confirmation by vm.confirmation.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var history by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf<ImageEntity?>(null) }
        var rename by remember { mutableStateOf<SessionEntity?>(null) }
        var deleteSession by remember { mutableStateOf<SessionEntity?>(null) }
        var deletePicture by remember { mutableStateOf<ImageEntity?>(null) }
        var pendingSave by remember { mutableStateOf<ImageEntity?>(null) }
        fun performSave(image: ImageEntity) { scope.launch {
            try { withContext(Dispatchers.IO) { exportImage(context, image) }; snackbar.showSnackbar("已保存到系统相册") }
            catch (e: Exception) { snackbar.showSnackbar(safeError(e)) }
        } }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingSave?.let { if (granted) performSave(it) else vm.notice.value = "需要相册写入权限才能保存图片" }; pendingSave = null
        }
        val save: (ImageEntity) -> Unit = { image ->
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { pendingSave = image; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            else performSave(image)
        }
        val share: (ImageEntity) -> Unit = { image -> try { shareImage(context, image) } catch (e: Exception) { vm.notice.value = safeError(e) } }
        LaunchedEffect(notice) { if (notice.isNotBlank()) { snackbar.showSnackbar(notice); vm.notice.value = "" } }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Text(if (page == 0) sessions.firstOrNull { it.id == selected }?.title ?: "MuseChat AI" else listOf("聊天", "创作图库", "模型与服务商", "设置")[page]) },
                navigationIcon = { if (page == 0) IconButton(onClick = { history = true }, enabled = !busy) { Icon(Icons.Default.History, "历史对话") } },
                actions = { if (page == 0) IconButton(onClick = vm::newSession, enabled = !busy && ready) { Icon(Icons.Default.Add, "新建聊天") } }) },
            bottomBar = { NavigationBar {
                val icons = listOf(Icons.AutoMirrored.Filled.Chat, Icons.Default.PhotoLibrary, Icons.Default.Tune, Icons.Default.Settings)
                listOf("聊天", "图库", "模型", "设置").forEachIndexed { i, title -> NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Icon(icons[i], title) }, label = { Text(title) }) }
            } }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (page) {
                    0 -> ChatScreen(vm, configs, prefs, images.filter { it.sessionId == selected }, { preview = it }, save, share)
                    1 -> GalleryScreen(images, { preview = it }, { vm.selectSession(it.sessionId); page = 0 }, { deletePicture = it })
                    2 -> ModelSettingsScreen(vm, configs, busy)
                    3 -> AppSettingsScreen(vm, prefs)
                }
            }
        }
        if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("历史对话") }, text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) { items(sessions, key = { it.id }) { session ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.selectSession(session.id); history = false }, modifier = Modifier.weight(1f)) { Text(session.title) }
                    IconButton(onClick = { rename = session }) { Icon(Icons.Default.Edit, "修改标题") }
                    IconButton(onClick = { deleteSession = session }) { Icon(Icons.Default.DeleteOutline, "删除聊天") }
                }
            } }
        }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
        rename?.let { session ->
            var title by remember(session.id) { mutableStateOf(session.title) }
            AlertDialog(onDismissRequest = { rename = null }, title = { Text("修改标题") }, text = { OutlinedTextField(title, { title = it }, label = { Text("标题") }) },
                confirmButton = { TextButton(onClick = { vm.renameSession(session.id, title); rename = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
        }
        deleteSession?.let { session -> ConfirmDialog("删除对话？", "将删除该对话、工具记录及私有存储中的图片。相册副本会保留。", { deleteSession = null }) { vm.deleteSession(session.id); deleteSession = null } }
        deletePicture?.let { picture -> ConfirmDialog("删除本地图片？", "将从图库和聊天中移除此图片，相册副本会保留。", { deletePicture = null }) { vm.deleteImage(picture); if (preview?.id == picture.id) preview = null; deletePicture = null } }
        confirmation?.let { c -> ConfirmDialog("确认生图（可能产生费用）", c.params.toString(), { c.answer.complete(false) }) { c.answer.complete(true) } }
        preview?.let { image -> ImagePreviewScreen(image, { preview = null }, save, share, { deletePicture = it }) }
    }
}
@Composable fun ConfirmDialog(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)) } },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable fun GalleryScreen(images: List<ImageEntity>, preview: (ImageEntity) -> Unit, chat: (ImageEntity) -> Unit, delete: (ImageEntity) -> Unit) {
    if (images.isEmpty()) EmptyState("让灵感成为画面", "聊天模型调用生图工具后，作品会自动出现在这里。")
    else LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(images, key = { it.id }) { image -> Card {
            AsyncImage(File(image.path), image.prompt, Modifier.fillMaxWidth().aspectRatio(1f).clickable { preview(image) }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Text(image.model, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
            Text(image.prompt, maxLines = 2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
            Row { TextButton(onClick = { chat(image) }) { Text("原对话") }; IconButton(onClick = { delete(image) }) { Icon(Icons.Default.DeleteOutline, "删除图片") } }
        } }
    }
}
@Composable fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.AutoAwesome, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp)); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun ImagePreviewScreen(image: ImageEntity, close: () -> Unit, save: (ImageEntity) -> Unit, share: (ImageEntity) -> Unit, delete: (ImageEntity) -> Unit) {
    var scale by remember(image.id) { mutableFloatStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Column {
        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = close) { Icon(Icons.Default.Close, "关闭") }; Text("${image.width} × ${image.height}", modifier = Modifier.weight(1f)); TextButton(onClick = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) { Text("重置缩放") } }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().transformable(transform), contentAlignment = Alignment.Center) {
            AsyncImage(File(image.path), image.prompt, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
        }
        Column(Modifier.padding(16.dp).navigationBarsPadding().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            Text(image.model, style = MaterialTheme.typography.labelLarge); SelectionContainer { Text(image.prompt) }; Text(image.parameters, style = MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick = { save(image) }) { Text("保存") }; TextButton(onClick = { share(image) }) { Text("分享") }; TextButton(onClick = { clipboard.setText(AnnotatedString(image.prompt)) }) { Text("复制提示词") }; IconButton(onClick = { delete(image) }) { Icon(Icons.Default.DeleteOutline, "删除") } }
        }
    } } }
}
