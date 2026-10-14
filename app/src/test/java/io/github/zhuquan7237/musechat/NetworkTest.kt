package io.github.zhuquan7237.musechat

import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkTest {
    private val http = HttpTransport(allowHttpForTests = true)
    private fun server() = MockWebServer().apply { start() }
    private fun config(s: MockWebServer, stream: Boolean = false) = ModelConfig(name = "test", baseUrl = s.url("/v1").toString(), model = "chat-model", streaming = stream)
    private fun textResponse(text: String = "你好") = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"$text"}}]}"""
    @Test fun endpointRemovesOverlappingPrefixesAndFullEndpoint() {
        assertEquals("https://example.com/v1/images/generations", http.endpoint("https://example.com/v1", "/v1/images/generations"))
        assertEquals("https://example.com/v1/images/generations", http.endpoint("https://example.com/v1/images/generations/", "/images/generations"))
        assertEquals("https://example.com/custom/v1/chat/completions", http.endpoint("https://example.com/custom/v1/", "/chat/completions"))
    }
    @Test fun productionRejectsHttpAndEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) { HttpTransport().endpoint("http://example.com/v1", "models") }
        assertThrows(IllegalArgumentException::class.java) { http.endpoint("https://secret@example.com/v1", "models") }
        assertThrows(IllegalArgumentException::class.java) { http.endpoint("https://example.com/v1?api_key=x", "models") }
    }
    @Test fun nonStreamingTextAndModelRequest() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setBody(textResponse()))
            val reply = ChatApiClient(http).complete(config(s), "chat-secret", listOf(userMessage("你好")), null)
            assertEquals("你好", reply.text); assertTrue(reply.calls.isEmpty())
            val req = s.takeRequest(); assertEquals("/v1/chat/completions", req.path); assertEquals("Bearer chat-secret", req.getHeader("Authorization"))
            assertEquals("chat-model", json.parseToJsonElement(req.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
        }
    }
    @Test fun modelListSupportsConfigurationDiscovery() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setBody("""{"data":[{"id":"model-a"}]}"""))
            assertEquals(listOf("model-a"), ChatApiClient(http).models(config(s), "key"))
            assertEquals("/v1/models", s.takeRequest().path)
        }
    }
    private fun delta(content: String) = """{"choices":[{"index":0,"delta":{"content":"$content"}}]}"""
    private fun finish(reason: String = "stop") = """{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}"""
    private fun sse(vararg chunks: String) = chunks.joinToString("\n\n", postfix = "\n\n") { "data: $it" }
    @Test fun streamsTextIncrementsOverSse() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(delta("你"), delta("好"), finish(), "[DONE]")))
            val chunks = mutableListOf<String>()
            val r = ChatApiClient(http).complete(config(s, true), "key", listOf(userMessage("hello")), null) { chunks += it }
            assertEquals(listOf("你", "你好"), chunks); assertEquals("你好", r.text)
        }
    }
    fun callDelta(index: Int, id: String? = null, name: String? = null, arguments: String? = null): String = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { put("index", 0); putJsonObject("delta") { putJsonArray("tool_calls") { add(buildJsonObject {
            put("index", index); id?.let { put("id", it) }; putJsonObject("function") { name?.let { put("name", it) }; arguments?.let { put("arguments", it) } }
        }) } } }) }
    }.toString()
    @Test fun mergesInterleavedToolsAndSplitNamesIdsAndArguments() {
        val a = StreamAccumulator()
        a.accept(callDelta(1, "call_", "generate_", "{"))
        a.accept(callDelta(0, "first", "generate_image", "{}"))
        a.accept(callDelta(1, "second", "image", "\"prompt\":\"moon\"}"))
        a.accept(finish("tool_calls"))
        val r = a.finish(true)
        assertEquals(listOf("first", "call_second"), r.calls.map { it.id })
        assertEquals("generate_image", r.calls[1].name)
        assertEquals("moon", json.parseToJsonElement(r.calls[1].arguments).jsonObject["prompt"]!!.jsonPrimitive.content)
    }
    @Test fun interruptedStreamIsNeverAccepted() {
        val a = StreamAccumulator(); a.accept(callDelta(0, "call", "generate_image", "{}"))
        assertThrows(IOException::class.java) { a.finish(false) }
    }
    @Test fun malformedSseIsReported() { assertThrows(IOException::class.java) { StreamAccumulator().accept("not-json") } }
    @Test fun truncatedToolOutputIsRejected() {
        val a = StreamAccumulator(); a.accept(callDelta(0, "call", "generate_image", "{}")); a.accept(finish("length"))
        assertThrows(IOException::class.java) { a.finish(true) }
    }
    @Test fun duplicateToolIdsRejected() {
        val a = StreamAccumulator(); a.accept(callDelta(0, "same", "generate_image", "{}")); a.accept(callDelta(1, "same", "generate_image", "{}"))
        assertThrows(IOException::class.java) { a.finish(true) }
    }
    @Test fun imagesParseBothStandardFormatsAndExplicitAdapter() {
        val root = json.parseToJsonElement("""{"data":[{"url":"https://example.com/a.png"},{"b64_json":"aGVsbG8="}]}""").jsonObject
        val r = OpenAIImageAdapter().parse(root, ModelConfig())
        assertTrue(r[0] is ImageSource.Url); assertTrue(r[1] is ImageSource.Base64)
        val custom = json.parseToJsonElement("""{"outputs":[{"encoded":"aGVsbG8="}]}""").jsonObject
        assertTrue(ConfiguredImageAdapter().parse(custom, ModelConfig(customArrayField = "outputs", customBase64Field = "encoded"))[0] is ImageSource.Base64)
        assertThrows(IOException::class.java) { OpenAIImageAdapter().parse(custom, ModelConfig()) }
    }
    @Test fun maps401429500WithoutResponseBodyCredentialLeak() = runBlocking {
        server().use { s ->
            for (status in listOf(401,429,500)) {
                s.enqueue(MockResponse().setResponseCode(status).setBody("SECRET echoed by server"))
                try { ChatApiClient(http).complete(config(s), "SECRET", listOf(userMessage("hello")), null); fail("expected error") }
                catch (e: ApiException) { assertEquals(status, e.status); assertFalse(e.message!!.contains("SECRET")) }
            }
            assertEquals(3, s.requestCount)
        }
    }
    @Test fun emptyAndInvalidJsonResponsesFail() = runBlocking {
        server().use { s ->
            for (body in listOf("", "oops", "{}")) {
                s.enqueue(MockResponse().setBody(body))
                try { ChatApiClient(http).complete(config(s), "key", listOf(userMessage("hello")), null); fail("expected error") } catch (_: IOException) {}
            }
        }
    }
    @Test fun timeoutAndCancelTerminateCalls() = runBlocking {
        server().use { s ->
            val timed = HttpTransport(OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).callTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build(), true)
            s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            try { ChatApiClient(timed).complete(config(s), "key", listOf(userMessage("hello")), null); fail("expected timeout") } catch (_: IOException) {}
            s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val worker = launch { ChatApiClient(http).complete(config(s), "key", listOf(userMessage("hello")), null) }
            withContext(Dispatchers.IO) { s.takeRequest(); s.takeRequest() }
            withTimeout(2000) { worker.cancelAndJoin() }
            assertTrue(worker.isCancelled)
        }
    }
}
