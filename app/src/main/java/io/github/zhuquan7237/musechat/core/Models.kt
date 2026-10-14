package io.github.zhuquan7237.musechat.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class ModelConfig(
    val id: String = newId(), val kind: String = "chat", val name: String = "",
    val baseUrl: String = "", val model: String = "", val streaming: Boolean = true,
    val tools: Boolean = true, val temperature: Double? = null, val maxTokens: Int? = null,
    val imagePath: String = "/images/generations", val sizes: List<String> = listOf("1024x1024"),
    val defaultSize: String = "1024x1024", val maxImages: Int = 1,
    val qualities: List<String> = emptyList(), val extraJson: String = "{}",
    val imageAdapter: String = "openai", val customArrayField: String = "images",
    val customUrlField: String = "url", val customBase64Field: String = "base64"
)
data class ToolCall(val id: String, val name: String, val arguments: String) {
    fun wire() = buildJsonObject {
        put("id", id); put("type", "function"); putJsonObject("function") { put("name", name); put("arguments", arguments) }
    }
}
data class ChatReply(val text: String, val calls: List<ToolCall>) {
    fun wire() = buildJsonObject {
        put("role", "assistant"); put("content", if (text.isBlank()) JsonNull else JsonPrimitive(text))
        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls.map { it.wire() }))
    }
}
fun userMessage(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
fun toolMessage(id: String, result: JsonObject) = buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", result.toString()) }
fun failure(message: String) = buildJsonObject { put("success", false); put("error", message) }
enum class AgentState(val label: String) {
    IDLE("就绪"), MODEL_REQUESTING("正在理解需求"), TOOL_CALL_RECEIVED("正在准备提示词"),
    TOOL_EXECUTING("正在调用图片生成接口"), DOWNLOADING("正在获取图片"),
    TOOL_COMPLETED("图片已保存"), MODEL_CONTINUING("正在整理回复"), COMPLETED("已完成"),
    FAILED("失败"), CANCELLED("已取消；远程服务商仍可能计费")
}
data class AgentOptions(val mode: String = "auto", val maxCalls: Int = 2, val maxImages: Int = 1, val optimize: Boolean = true)
data class GeneratedImage(val id: String, val path: String, val prompt: String, val model: String, val parameters: String, val width: Int, val height: Int)
