package io.github.zhuquan7237.musechat

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zhuquan7237.musechat.agent.*
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.data.*
import io.github.zhuquan7237.musechat.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class HttpsImageTest {
    @Test fun agentSavesRealHttpsImageAndReturnsPersistedToolResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).retryOnConnectionFailure(false).build()
        val transport = HttpTransport(client)
        val db = Room.inMemoryDatabaseBuilder(context, MuseDatabase::class.java).build()
        MockWebServer().use { chat -> MockWebServer().use { image -> MockWebServer().use { download ->
            listOf(chat, image, download).forEach { it.useHttps(serverTls.sslSocketFactory(), false); it.start() }
            val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray(); bitmap.recycle()
            download.enqueue(MockResponse().setBody(Buffer().write(png)).setHeader("Content-Type", "image/png"))
            image.enqueue(MockResponse().setBody(buildJsonObject { putJsonArray("data") { add(buildJsonObject { put("url", download.url("/original.png").toString()) }) } }.toString()))
            val call = ToolCall("https-call", "generate_image", """{"prompt":"blue moon"}""")
            chat.enqueue(MockResponse().setBody(buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("message", ChatReply("", listOf(call)).wire()); put("finish_reason", "tool_calls") }) } }.toString()))
            chat.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"完成"},"finish_reason":"stop"}]}"""))
            val session = newId(); db.dao().insertSession(SessionEntity(session, "HTTPS 集成"))
            val store = ChatRepository(db).store(session); store.append(userMessage("生成月亮"))
            val imageConfig = ModelConfig(kind = "image", name = "image", baseUrl = image.url("/v1").toString(), model = "image-model")
            val storage = ImageRepository(context, transport)
            try {
                AgentOrchestrator(ChatApiClient(transport)).run(ModelConfig(name = "chat", baseUrl = chat.url("/v1").toString(), model = "chat-model", streaming = false), "chat-fixture-key", store,
                    ToolRegistry(listOf(GenerateImageTool(imageConfig, "image-fixture-key", 1, ImageApiClient(transport), storage))), AgentOptions())
                val pictures = db.dao().sessionImages(session)
                assertEquals(1, pictures.size); assertEquals(8, pictures.single().width)
                assertArrayEquals(png, File(pictures.single().path).readBytes())
                assertNull(download.takeRequest().getHeader("Authorization"))
                assertEquals("Bearer image-fixture-key", image.takeRequest().getHeader("Authorization"))
                assertEquals("Bearer chat-fixture-key", chat.takeRequest().getHeader("Authorization"))
                val next = json.parseToJsonElement(chat.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
                assertTrue(next.any { it.jsonObject["tool_call_id"]?.jsonPrimitive?.contentOrNull == "https-call" })
                assertEquals(4, store.history().size)
            } finally { db.dao().sessionImages(session).forEach { File(it.path).delete() }; db.close() }
        } } }
    }
}
