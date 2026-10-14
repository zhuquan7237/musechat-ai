package io.github.zhuquan7237.musechat.network

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import io.github.zhuquan7237.musechat.core.json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, message: String): IOException(message)
class HttpTransport(val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val allowHttpForTests: Boolean = false
) {
    fun endpoint(base: String, path: String): String {
        val url = try { base.trim().trimEnd('/').toHttpUrl() } catch (e: Exception) { throw IllegalArgumentException("API 地址无效") }
        require(url.scheme == "https" || allowHttpForTests && url.scheme == "http") { "API 地址必须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "API 地址不能包含凭据、查询参数或片段" }
        val target = path.trim().trim('/')
        require(target.isNotEmpty() && !target.contains(":") && !target.contains("..") && !target.contains('?') && !target.contains('#')) { "无效的接口路径" }
        val existing = url.encodedPath.trim('/')
        if (existing == target || existing.endsWith("/$target")) return url.toString().trimEnd('/')
        val parts = existing.split('/').filter { it.isNotBlank() }
        val requested = target.split('/')
        val overlap = (minOf(parts.size, requested.size) downTo 1).firstOrNull { parts.takeLast(it) == requested.take(it) } ?: 0
        return url.newBuilder().encodedPath("/" + (parts + requested.drop(overlap)).joinToString("/")).build().toString()
    }
    fun request(url: String, key: String, payload: JsonObject? = null): Request {
        require(key.all { it.code in 33..126 }) { "API Key 含有空白、换行或无效字符，请重新填写" }
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (key.isNotBlank()) b.header("Authorization", "Bearer $key")
        if (payload != null) b.post(payload.toString().toRequestBody("application/json".toMediaType()))
        return b.build()
    }
    suspend fun <T> consume(request: Request, block: (Response) -> T): T = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        val worker = CoroutineScope(cont.context).launch(Dispatchers.IO) {
            try {
                val value = call.execute().use { response ->
                    if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                        401, 403 -> "鉴权失败（HTTP ${response.code}），请检查此服务商的密钥和权限"
                        429 -> "请求受限或余额不足（HTTP 429）"
                        else -> "服务商请求失败（HTTP ${response.code}）"
                    })
                    block(response)
                }
                if (cont.isActive) cont.resume(value)
            } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
        }
        cont.invokeOnCancellation { call.cancel(); worker.cancel() }
    }
    suspend fun objectResponse(request: Request): JsonObject = consume(request) { r ->
        val body = r.body ?: throw IOException("接口返回空响应")
        if (body.contentLength() > 48L * 1024 * 1024) throw IOException("接口响应超过 48 MB 限制")
        val bytes = body.source().readByteArrayLimited(48L * 1024 * 1024)
        try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject } catch (e: Exception) { throw IOException("接口返回无效 JSON") }
    }
}
fun okio.BufferedSource.readByteArrayLimited(limit: Long): ByteArray {
    val buffer = okio.Buffer()
    while (true) { val read = read(buffer, 8192); if (read == -1L) break; if (buffer.size > limit) throw IOException("响应超过文件大小限制") }
    return buffer.readByteArray()
}
