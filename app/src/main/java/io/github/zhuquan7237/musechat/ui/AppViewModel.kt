package io.github.zhuquan7237.musechat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhuquan7237.musechat.MuseApplication
import io.github.zhuquan7237.musechat.agent.*
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

data class Confirmation(val call: ToolCall, val params: JsonObject, val answer: CompletableDeferred<Boolean>)
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application): AndroidViewModel(application) {
    val app = application as MuseApplication
    val prefs = app.preferences.flow.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    val configs = app.db.dao().configs().map { rows -> rows.map { json.decodeFromString<ModelConfig>(it.json) } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions = app.db.dao().sessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val images = app.db.dao().images().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selected = MutableStateFlow("")
    val messages = selected.flatMapLatest { app.db.dao().messages(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow(false)
    val testing = MutableStateFlow(false)
    val testResult = MutableStateFlow("")
    val state = MutableStateFlow(AgentState.IDLE)
    val partial = MutableStateFlow("")
    val notice = MutableStateFlow("")
    val confirmation = MutableStateFlow<Confirmation?>(null)
    val ready = MutableStateFlow(false)
    private var task: Job? = null
    init {
        action {
            app.chats.recoverInterruptedTools()
            app.images.cleanupOrphans(app.db.dao().images().first().map { it.path }.toSet())
            val settings = app.preferences.flow.first()
            val history = app.db.dao().allSessions()
            if (history.any { it.id == settings.sessionId }) selected.value = settings.sessionId
            else if (history.isNotEmpty()) selected.value = history.maxBy { it.createdAt }.id
            else createSession()
            ready.value = true
        }
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice.value = safeError(e) } } }
    private suspend fun createSession() {
        val id = newId(); app.db.dao().insertSession(SessionEntity(id, "新对话")); selected.value = id; app.preferences.string("sessionId", id)
    }
    fun newSession() { if (!busy.value) action { createSession(); state.value = AgentState.IDLE } }
    fun selectSession(id: String) { if (!busy.value) { selected.value = id; state.value = AgentState.IDLE; action { app.preferences.string("sessionId", id) } } }
    fun renameSession(id: String, title: String) { action { require(title.isNotBlank()) { "标题不能为空" }; app.db.dao().rename(id, title.take(80)) } }
    fun deleteSession(id: String) { if (!busy.value) action {
        val pictures = app.db.dao().sessionImages(id)
        app.db.dao().deleteSession(id); pictures.forEach { File(it.path).delete() }
        if (selected.value == id) { val others = app.db.dao().allSessions(); if (others.isEmpty()) createSession() else selectSession(others.first().id) }
    } }
    fun send(text: String): Boolean {
        if (busy.value || !ready.value || text.isBlank()) return false
        val settings = prefs.value
        val chat = configs.value.firstOrNull { it.id == settings.chatId && it.kind == "chat" }
            ?: configs.value.firstOrNull { it.kind == "chat" }
        if (chat == null) { notice.value = "请先在“模型”页面添加聊天 API 配置"; return false }
        val image = configs.value.firstOrNull { it.id == settings.imageId && it.kind == "image" }
            ?: configs.value.firstOrNull { it.kind == "image" }
        val session = selected.value
        busy.value = true
        task = viewModelScope.launch {
            try {
                val store = app.chats.store(session)
                if (store.history().isEmpty()) app.db.dao().rename(session, text.take(28))
                store.append(userMessage(text))
                val chatKey = app.credentials.get(chat.id)
                val registry = image?.let { ToolRegistry(listOf(GenerateImageTool(it, app.credentials.get(it.id), settings.maxImages, app.imageApi, app.images) { value -> state.value = value })) }
                AgentOrchestrator(app.chatApi).run(chat, chatKey, store, registry, AgentOptions(settings.mode, settings.maxCalls, settings.maxImages, settings.optimize),
                    onState = { state.value = it }, onText = { partial.value = it }, confirm = { call, params ->
                        val answer = CompletableDeferred<Boolean>()
                        confirmation.value = Confirmation(call, params, answer)
                        try { answer.await() } finally { confirmation.value = null }
                    })
            } catch (e: CancellationException) { state.value = AgentState.CANCELLED; throw e }
            catch (e: Exception) { state.value = AgentState.FAILED; app.chats.store(session).error(safeError(e)) }
            finally { busy.value = false; partial.value = ""; confirmation.value = null }
        }
        return true
    }
    fun stop() { task?.cancel() }
    fun regenerate() {
        val last = messages.value.lastOrNull { it.kind == "wire" && json.parseToJsonElement(it.wire).jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val text = last?.let { json.parseToJsonElement(it.wire).jsonObject["content"]?.jsonPrimitive?.contentOrNull }
        if (text != null) send(text)
    }
    fun stringPref(name: String, value: String) { action { app.preferences.string(name, value) } }
    fun boolPref(name: String, value: Boolean) { action { app.preferences.boolean(name, value) } }
    fun numberPref(name: String, value: Int) { action { require(value in 1..8); app.preferences.number(name, value) } }
    fun saveConfig(config: ModelConfig, key: String, onSaved: () -> Unit) { action {
        validateConfig(config)
        app.credentials.save(config.id, key)
        app.db.dao().saveConfig(ConfigEntity(config.id, json.encodeToString(config)))
        app.preferences.string(if (config.kind == "chat") "chatId" else "imageId", config.id)
        onSaved()
    } }
    fun deleteConfig(config: ModelConfig) { if (!busy.value) action { app.credentials.delete(config.id); app.db.dao().deleteConfig(config.id) } }
    fun key(config: ModelConfig): String = try { app.credentials.get(config.id) } catch (e: Exception) { notice.value = safeError(e); "" }
    fun deleteImage(image: ImageEntity) { if (!busy.value) action { app.db.dao().deleteImage(image.id); File(image.path).delete() } }
    fun test(config: ModelConfig, key: String, type: String, result: (String) -> Unit) {
        if (busy.value || !ready.value) { result("已有任务进行中，请稍后再试"); return }
        busy.value = true; testing.value = true; testResult.value = "正在请求服务商…"
        task = viewModelScope.launch {
        fun finish(text: String) { testResult.value = text; result(text) }
        try {
            validateConfig(config)
            finish(when (type) {
                "models" -> app.chatApi.models(config, key).joinToString(", ").take(4000)
                "chat" -> app.chatApi.complete(config, key, listOf(userMessage("请回复：连接成功")), null).text
                "tools" -> {
                    val image = configs.value.firstOrNull { it.kind == "image" } ?: ModelConfig(kind = "image")
                    val registry = ToolRegistry(listOf(GenerateImageTool(image, "", 1, app.imageApi, app.images)))
                    val r = app.chatApi.complete(config.copy(tools = true), key, listOf(userMessage("请调用 generate_image 生成一张蓝色圆形图片，这是工具能力测试。")), registry.definitions())
                    if (r.calls.any { it.name == "generate_image" }) "已收到原生 generate_image tool_calls；测试没有执行生图。" else "此次请求未返回工具调用，无法确认模型支持；请检查模型能力。"
                }
                "image" -> {
                    val tool = GenerateImageTool(config, key, 1, app.imageApi, app.images)
                    val output = tool.execute(tool.validate(buildJsonObject { put("prompt", "A simple blue circle on a white background") }.toString()))
                    val store = app.chats.store(selected.value)
                    val call = ToolCall(newId(), "generate_image", "{}")
                    withContext(NonCancellable) { store.append(ChatReply("用户确认的生图接口测试", listOf(call)).wire()); store.completeTool(call, output) }
                    "真实生图成功，已保存到当前聊天与图库。"
                }
                else -> "未知测试"
            })
        } catch (e: CancellationException) { finish("测试已取消；远程服务商仍可能计费"); throw e }
        catch (e: Exception) { finish(safeError(e, key)) }
        finally { busy.value = false; testing.value = false }
    } }
    private fun validateConfig(config: ModelConfig) {
        require(config.name.isNotBlank() && config.model.isNotBlank()) { "请填写配置名称和模型 ID" }
        app.http.endpoint(config.baseUrl, if (config.kind == "chat") "chat/completions" else config.imagePath)
        require(config.temperature == null || config.temperature in 0.0..2.0) { "Temperature 必须在 0–2 之间" }
        require(config.maxTokens == null || config.maxTokens > 0) { "Max Tokens 必须大于 0" }
        require(config.maxImages in 1..8) { "模型图片数量上限必须在 1–8 之间" }
        if (config.kind == "image") {
            require(config.sizes.isEmpty() || config.defaultSize in config.sizes) { "默认尺寸必须在支持列表中" }
            val extras = json.parseToJsonElement(config.extraJson).jsonObject
            require(extras.keys.intersect(setOf("model", "prompt", "size", "n", "quality", "stream")).isEmpty()) { "高级参数不能覆盖 model/prompt/size/n/quality/stream" }
        }
    }
}
