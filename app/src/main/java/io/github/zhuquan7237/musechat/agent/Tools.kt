package io.github.zhuquan7237.musechat.agent

import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

interface ImageStorage {
    suspend fun save(source: ImageSource, prompt: String, config: ModelConfig, parameters: JsonObject): GeneratedImage
    suspend fun remove(images: List<GeneratedImage>)
}
data class ToolOutput(val result: JsonObject, val images: List<GeneratedImage> = emptyList())
interface AgentTool {
    val name: String
    fun definition(): JsonObject
    fun validate(raw: String): JsonObject
    suspend fun execute(parameters: JsonObject): ToolOutput
}
class ToolRegistry(private val tools: List<AgentTool>) {
    fun definitions() = JsonArray(tools.map { it.definition() })
    fun get(name: String): AgentTool = tools.firstOrNull { it.name == name } ?: throw IllegalArgumentException("未注册工具：$name")
}
class GenerateImageTool(
    private val config: ModelConfig, private val key: String, private val maxImages: Int,
    private val api: ImageApiClient, private val storage: ImageStorage, private val onState: (AgentState) -> Unit = {}
): AgentTool {
    override val name = "generate_image"
    override fun definition() = buildJsonObject {
        put("type", "function"); putJsonObject("function") {
            put("name", name); put("description", "仅当用户明确要求创建、绘制或生成新图片时调用。讨论创意或技巧时不要调用。仅支持文生图，不支持参考图编辑或人物一致性保证。")
            putJsonObject("parameters") {
                put("type", "object"); put("additionalProperties", false)
                putJsonArray("required") { add("prompt") }
                putJsonObject("properties") {
                    putJsonObject("prompt") { put("type", "string"); put("minLength", 1); put("maxLength", 16000) }
                    if (config.sizes.isNotEmpty()) putJsonObject("size") { put("type", "string"); put("enum", JsonArray(config.sizes.map(::JsonPrimitive))) }
                    putJsonObject("n") { put("type", "integer"); put("minimum", 1); put("maximum", minOf(maxImages, config.maxImages)) }
                    if (config.qualities.isNotEmpty()) putJsonObject("quality") { put("type", "string"); put("enum", JsonArray(config.qualities.map(::JsonPrimitive))) }
                }
            }
        }
    }
    override fun validate(raw: String): JsonObject {
        require(raw.length <= 64 * 1024) { "工具参数过大" }
        val p = try { json.parseToJsonElement(raw).jsonObject } catch (e: Exception) { throw IllegalArgumentException("工具参数不是完整 JSON 对象") }
        require(p.keys.all { it in setOf("prompt", "size", "n", "quality") }) { "工具包含不支持的参数" }
        val prompt = p["prompt"] as? JsonPrimitive
        require(prompt?.isString == true && prompt.content.isNotBlank() && prompt.content.length <= 16000) { "prompt 必须是非空字符串（不超过 16000 字）" }
        p["size"]?.let { require(it is JsonPrimitive && it.isString && it.content in config.sizes) { "此模型不支持该尺寸" } }
        p["quality"]?.let { require(it is JsonPrimitive && it.isString && it.content in config.qualities) { "此模型不支持该 quality" } }
        val count = p["n"]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "n 必须是整数" }; it.jsonPrimitive.int } ?: 1
        require(count in 1..minOf(maxImages, config.maxImages)) { "图片数量超出限制" }
        return buildJsonObject {
            p.forEach { (k,v) -> put(k,v) }; put("n", count)
            if (p["size"] == null && config.sizes.isNotEmpty()) {
                require(config.defaultSize in config.sizes) { "默认尺寸不在支持列表中" }; put("size", config.defaultSize)
            }
        }
    }
    override suspend fun execute(parameters: JsonObject): ToolOutput {
        onState(AgentState.TOOL_EXECUTING)
        val sources = try { api.generate(config, key, parameters) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw java.io.IOException(safeError(e, key)) }
        onState(AgentState.DOWNLOADING)
        val saved = mutableListOf<GeneratedImage>()
        try {
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                saved += storage.save(source, parameters["prompt"]!!.jsonPrimitive.content, config, parameters)
            }
        } catch (e: Exception) { withContext(NonCancellable) { storage.remove(saved) }; throw e }
        onState(AgentState.TOOL_COMPLETED)
        return ToolOutput(buildJsonObject {
            put("success", true); put("count", saved.size); put("task_id", newId()); put("saved_locally", true)
            put("description", "图片已生成并在聊天中展示；未进行视觉分析。仅为文生图，不保证与前图人物一致。")
            putJsonArray("images") { saved.forEach { image -> add(buildJsonObject { put("id", image.id); put("width", image.width); put("height", image.height) }) } }
        }, saved)
    }
}
