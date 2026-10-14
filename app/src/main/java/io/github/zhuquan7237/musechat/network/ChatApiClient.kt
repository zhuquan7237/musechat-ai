package io.github.zhuquan7237.musechat.network

import io.github.zhuquan7237.musechat.core.*
import kotlinx.serialization.json.*
import java.io.IOException

interface ChatProvider {
    suspend fun complete(config: ModelConfig, key: String, messages: List<JsonObject>, tools: JsonArray?, onText: (String) -> Unit = {}): ChatReply
}
class ChatApiClient(private val http: HttpTransport): ChatProvider {
    override suspend fun complete(config: ModelConfig, key: String, messages: List<JsonObject>, tools: JsonArray?, onText: (String) -> Unit): ChatReply {
        val payload = buildJsonObject {
            put("model", config.model); put("messages", JsonArray(messages)); put("stream", config.streaming)
            config.temperature?.let { put("temperature", it) }; config.maxTokens?.let { put("max_tokens", it) }
            if (tools != null) { put("tools", tools); put("tool_choice", "auto") }
        }
        val request = http.request(http.endpoint(config.baseUrl, "chat/completions"), key, payload)
        return if (config.streaming) http.consume(request) { response ->
            val source = response.body?.source() ?: throw IOException("空流式响应")
            val merger = StreamAccumulator(onText)
            val data = StringBuilder()
            var done = false
            fun consumeEvent() {
                if (data.isEmpty()) return
                val event = data.toString(); data.clear()
                if (event.trim() == "[DONE]") done = true else merger.accept(event)
            }
            while (!done) {
                val line = source.readUtf8LineBounded() ?: break
                if (line.isEmpty()) consumeEvent() else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append(10.toChar())
                    data.append(line.removePrefix("data:").trimStart())
                    if (data.length > 1024 * 1024) throw IOException("流式事件过大")
                }
            }
            consumeEvent()
            merger.finish(done)
        } else parseReply(http.objectResponse(request)).also { onText(it.text) }
    }
    suspend fun models(config: ModelConfig, key: String): List<String> =
        http.objectResponse(http.request(http.endpoint(config.baseUrl, "models"), key))["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?: throw IOException("模型列表格式不符合 data[].id")
}
private fun okio.BufferedSource.readUtf8LineBounded(): String? {
    if (exhausted()) return null
    return try { readUtf8LineStrict(1024L * 1024) } catch (e: java.io.EOFException) {
        if (buffer.size > 1024L * 1024) throw IOException("SSE 行过大")
        readUtf8Line()
    }
}
fun parseReply(root: JsonObject): ChatReply {
    if (root["error"] != null) throw IOException("服务商返回错误响应")
    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw IOException("聊天响应缺少 choices")
    val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
    if (finish in listOf("length", "content_filter")) throw IOException("模型响应被截断：$finish")
    val m = choice["message"]?.jsonObject ?: throw IOException("聊天响应缺少 message")
    val calls = m["tool_calls"]?.jsonArray?.map { value ->
        val c = value.jsonObject; val f = c["function"]!!.jsonObject
        require(c["type"]?.jsonPrimitive?.content == "function") { "不支持的工具类型" }
        ToolCall(c["id"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content, f["arguments"]!!.jsonPrimitive.content)
    } ?: emptyList()
    require(calls.map { it.id }.distinct().size == calls.size && calls.all { it.id.isNotBlank() }) { "重复或缺失 tool_call_id" }
    val text = m["content"]?.jsonPrimitive?.contentOrNull ?: ""
    if (text.isBlank() && calls.isEmpty()) throw IOException("模型返回空内容")
    return ChatReply(text, calls)
}
class StreamAccumulator(private val onText: (String) -> Unit = {}) {
    private data class Parts(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())
    private val text = StringBuilder()
    private val calls = sortedMapOf<Int, Parts>()
    private var finishReason: String? = null
    fun accept(raw: String) {
        val root = try { json.parseToJsonElement(raw).jsonObject } catch (e: Exception) { throw IOException("无效的 SSE JSON") }
        if (root["error"] != null) throw IOException("服务商在流中返回错误")
        val choices = root["choices"]?.jsonArray ?: return
        val choice = choices.firstOrNull { it.jsonObject["index"]?.jsonPrimitive?.intOrNull == 0 }?.jsonObject ?: return
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
        val delta = choice["delta"]?.jsonObject ?: return
        delta["content"]?.jsonPrimitive?.contentOrNull?.let { text.append(it); onText(text.toString()) }
        if (text.length > 1024 * 1024) throw IOException("文本输出超过限制")
        delta["tool_calls"]?.jsonArray?.forEach { value ->
            val c = value.jsonObject
            c["type"]?.jsonPrimitive?.contentOrNull?.let { require(it == "function") { "不支持的工具类型" } }
            val index = c["index"]?.jsonPrimitive?.intOrNull ?: throw IOException("工具分片缺少 index")
            require(index in 0..31) { "工具索引越界" }
            val p = calls.getOrPut(index) { Parts() }
            c["id"]?.jsonPrimitive?.contentOrNull?.let { p.id.append(it) }
            c["function"]?.jsonObject?.let { f ->
                f["name"]?.jsonPrimitive?.contentOrNull?.let { p.name.append(it) }
                f["arguments"]?.jsonPrimitive?.contentOrNull?.let { p.arguments.append(it) }
            }
            if (p.arguments.length > 64 * 1024) throw IOException("工具参数超过限制")
            if (p.id.length > 256 || p.name.length > 256) throw IOException("工具标识超过限制")
        }
    }
    fun finish(done: Boolean): ChatReply {
        if (!done && finishReason == null) throw IOException("流中断，未收到完成标记；工具未执行")
        if (finishReason in listOf("length", "content_filter")) throw IOException("模型响应被截断：$finishReason")
        val result = calls.values.map { ToolCall(it.id.toString(), it.name.toString(), it.arguments.toString()) }
        if (result.any { it.id.isBlank() || it.name.isBlank() || it.arguments.isBlank() } || result.map { it.id }.distinct().size != result.size) throw IOException("工具分片不完整或 ID 重复")
        if (result.isEmpty() && text.isBlank()) throw IOException("空流式响应")
        return ChatReply(text.toString(), result)
    }
}
