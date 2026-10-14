package io.github.zhuquan7237.musechat.network

import io.github.zhuquan7237.musechat.core.*
import kotlinx.serialization.json.*
import java.io.IOException

sealed interface ImageSource { data class Url(val value: String): ImageSource; data class Base64(val value: String): ImageSource }
interface ImageProviderAdapter { fun parse(root: JsonObject, config: ModelConfig): List<ImageSource> }
class OpenAIImageAdapter: ImageProviderAdapter {
    override fun parse(root: JsonObject, config: ModelConfig): List<ImageSource> = parseImageArray(root, "data", "url", "b64_json")
}
class ConfiguredImageAdapter: ImageProviderAdapter {
    override fun parse(root: JsonObject, config: ModelConfig): List<ImageSource> =
        parseImageArray(root, config.customArrayField, config.customUrlField, config.customBase64Field)
}
private fun parseImageArray(root: JsonObject, array: String, url: String, base64: String): List<ImageSource> {
    if (root["error"] != null) throw IOException("生图服务商返回错误")
    val values = root[array]?.jsonArray ?: throw IOException("生图响应缺少 $array 数组，请检查响应适配器")
    if (values.isEmpty()) throw IOException("生图接口未返回图片")
    return values.map { value ->
        val row = value.jsonObject
        val b64 = row[base64]?.jsonPrimitive?.contentOrNull
        val link = row[url]?.jsonPrimitive?.contentOrNull
        when {
            !b64.isNullOrBlank() -> ImageSource.Base64(b64)
            !link.isNullOrBlank() -> ImageSource.Url(link)
            else -> throw IOException("图片结果缺少 $url 或 $base64")
        }
    }
}
class ImageApiClient(private val http: HttpTransport) {
    suspend fun generate(config: ModelConfig, key: String, parameters: JsonObject): List<ImageSource> {
        val extras = json.parseToJsonElement(config.extraJson).jsonObject
        require(extras.keys.intersect(setOf("model", "prompt", "size", "n", "quality", "stream")).isEmpty()) { "高级参数不能覆盖工具或模型字段" }
        val payload = buildJsonObject { extras.forEach { (k, v) -> put(k, v) }; put("model", config.model); parameters.forEach { (k,v) -> put(k,v) } }
        val root = http.objectResponse(http.request(http.endpoint(config.baseUrl, config.imagePath), key, payload))
        val adapter = when (config.imageAdapter) { "openai" -> OpenAIImageAdapter(); "configured" -> ConfiguredImageAdapter(); else -> throw IOException("未知图片响应适配器") }
        val result = adapter.parse(root, config)
        require(result.size <= (parameters["n"]?.jsonPrimitive?.int ?: 1)) { "服务商返回的图片数量超出请求" }
        return result
    }
}
