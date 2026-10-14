package io.github.zhuquan7237.musechat.data

import android.content.Context
import android.graphics.BitmapFactory
import io.github.zhuquan7237.musechat.agent.ImageStorage
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.util.Base64

class ImageRepository(context: Context, private val http: HttpTransport): ImageStorage {
    private val directory = File(context.filesDir, "images").apply { mkdirs() }
    private val limit = 16L * 1024 * 1024
    override suspend fun save(source: ImageSource, prompt: String, config: ModelConfig, parameters: JsonObject): GeneratedImage = withContext(Dispatchers.IO) {
        require((directory.listFiles()?.sumOf { it.length() } ?: 0) + limit <= 512L * 1024 * 1024) { "图片存储达到 512 MB 限制，请先清理图库" }
        val id = newId(); val temporary = File(directory, "$id.part")
        try {
            when (source) {
                is ImageSource.Base64 -> {
                    require(source.value.length <= (limit * 4 / 3 + 4)) { "Base64 图片超过 16 MB" }
                    val bytes = try { Base64.getDecoder().decode(source.value) } catch (e: Exception) { throw IOException("无效 Base64 图片") }
                    require(bytes.size <= limit) { "图片超过 16 MB" }; temporary.writeBytes(bytes)
                }
                is ImageSource.Url -> {
                    val url = source.value.toHttpUrl()
                    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty()) { "图片 URL 必须为无嵌入凭据的 HTTPS 地址" }
                    // A temporary image host must never receive the API credential.
                    http.consume(Request.Builder().url(url).build()) { response ->
                        val body = response.body ?: throw IOException("图片下载为空")
                        require(body.contentLength() <= limit) { "图片超过 16 MB" }
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192); var count = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= limit) { "图片超过 16 MB" }; output.write(buffer, 0, n) }
                        } }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "响应不是有效图片或像素数量过大" }
            val extension = when (bounds.outMimeType) { "image/png" -> "png"; "image/webp" -> "webp"; "image/jpeg" -> "jpg"; else -> throw IOException("仅支持 PNG、JPEG、WebP 图片") }
            val final = File(directory, "$id.$extension")
            check(temporary.renameTo(final)) { "图片本地保存失败" }
            GeneratedImage(id, final.path, prompt, config.model, parameters.toString(), bounds.outWidth, bounds.outHeight)
        } finally { temporary.delete() }
    }
    override suspend fun remove(images: List<GeneratedImage>) { withContext(Dispatchers.IO) { images.forEach { File(it.path).delete() } } }
    fun cleanupOrphans(knownPaths: Set<String>) { directory.listFiles()?.filter { it.path !in knownPaths }?.forEach { it.delete() } }
}
