package io.github.zhuquan7237.musechat

import io.github.zhuquan7237.musechat.agent.*
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MemoryStore: AgentStore {
    val messages = mutableListOf<JsonObject>()
    val images = mutableListOf<GeneratedImage>()
    val errors = mutableListOf<String>()
    override suspend fun history() = messages.toList()
    override suspend fun append(message: JsonObject) { messages += message }
    override suspend fun completeTool(call: ToolCall, output: ToolOutput) { images += output.images; messages += toolMessage(call.id, output.result) }
    override suspend fun error(message: String) { errors += message }
}
class TestStorage: ImageStorage {
    val saved = mutableListOf<GeneratedImage>()
    override suspend fun save(source: ImageSource, prompt: String, config: ModelConfig, parameters: JsonObject): GeneratedImage {
        // Only fixtures: never used by the production app.
        if (source is ImageSource.Base64) require(Base64.getDecoder().decode(source.value).isNotEmpty())
        return GeneratedImage(newId(), "fixture.png", prompt, config.model, parameters.toString(), 1, 1).also { saved += it }
    }
    override suspend fun remove(images: List<GeneratedImage>) { saved.removeAll(images.toSet()) }
}
class AgentTest {
    private val http = HttpTransport(allowHttpForTests = true)
    private val params = """{"prompt":"a moon"}"""
    private fun imageConfig(s: MockWebServer) = ModelConfig(kind = "image", name = "image B", baseUrl = s.url("/v1").toString(), model = "image-model")
    private fun chatConfig(s: MockWebServer, stream: Boolean = false) = ModelConfig(name = "chat A", baseUrl = s.url("/v1").toString(), model = "chat-model", streaming = stream)
    private fun toolResponse(vararg ids: String) = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { put("finish_reason", "tool_calls"); putJsonObject("message") {
            put("role", "assistant"); put("content", JsonNull); put("tool_calls", JsonArray(ids.map { ToolCall(it, "generate_image", params).wire() }))
        } }) }
    }.toString()
    private val finalResponse = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"已完成"}}]}"""
    private val imageResponse = """{"data":[{"b64_json":"aGVsbG8="}]}"""
    private fun withServers(block: suspend (MockWebServer, MockWebServer) -> Unit) = runBlocking {
        MockWebServer().use { chat -> MockWebServer().use { image -> chat.start(); image.start(); block(chat, image) } }
    }
    @Test fun automaticImageRoundTripAndCredentialIsolation() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(toolResponse("call-a"))); c.enqueue(MockResponse().setBody(finalResponse))
        i.enqueue(MockResponse().setBody(imageResponse))
        val store = MemoryStore(); store.append(userMessage("生成白发蓝瞳少女"))
        val registry = ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image-secret", 1, ImageApiClient(http), TestStorage())))
        val states = mutableListOf<AgentState>()
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat-secret", store, registry, AgentOptions(), onState = { states += it })
        assertEquals(1, store.images.size); assertTrue(store.errors.isEmpty()); assertEquals(AgentState.COMPLETED, states.last())
        val first = c.takeRequest(); val second = c.takeRequest(); val image = i.takeRequest()
        assertEquals("Bearer chat-secret", first.getHeader("Authorization")); assertEquals("Bearer chat-secret", second.getHeader("Authorization"))
        assertEquals("Bearer image-secret", image.getHeader("Authorization")); assertEquals("/v1/images/generations", image.path)
        assertTrue(json.parseToJsonElement(first.body.readUtf8()).jsonObject.containsKey("tools"))
        val continuation = json.parseToJsonElement(second.body.readUtf8()).jsonObject["messages"]!!.jsonArray
        val tool = continuation.first { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }.jsonObject
        assertEquals("call-a", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse(tool["content"]!!.jsonPrimitive.content.contains("b64_json"))
        assertTrue(json.parseToJsonElement(tool["content"]!!.jsonPrimitive.content).jsonObject["success"]!!.jsonPrimitive.boolean)
    }
    @Test fun ordinaryConversationNeverCallsImageApi() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(finalResponse))
        val store = MemoryStore(); store.append(userMessage("怎样的构图适合孤独感？"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions())
        assertEquals(0, i.requestCount); assertTrue(store.images.isEmpty()); assertEquals(2, store.messages.size)
    }
    @Test fun continuedGenerationKeepsContextAndBothImages() = withServers { c, i ->
        val store = MemoryStore(); val engine = AgentOrchestrator(ChatApiClient(http))
        val registry = ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage())))
        repeat(2) { round ->
            c.enqueue(MockResponse().setBody(toolResponse("call-$round"))); c.enqueue(MockResponse().setBody(finalResponse)); i.enqueue(MockResponse().setBody(imageResponse))
            store.append(userMessage(if (round == 0) "生成少女" else "再生成一张，换成夕阳"))
            engine.run(chatConfig(c), "chat", store, registry, AgentOptions())
        }
        assertEquals(2, store.images.size); assertNotEquals(store.images[0].id, store.images[1].id)
        c.takeRequest(); c.takeRequest()
        val later = c.takeRequest().body.readUtf8()
        assertTrue(later.contains("生成少女")); assertTrue(later.contains("夕阳")); assertEquals(2, i.requestCount)
    }
    @Test fun image401ReturnsFailureAndNeverRetriesPaidRequest() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(toolResponse("fail"))); c.enqueue(MockResponse().setBody(finalResponse)); i.enqueue(MockResponse().setResponseCode(401))
        val store = MemoryStore(); store.append(userMessage("画图"))
        val states = mutableListOf<AgentState>()
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions(), onState = { states += it })
        assertTrue(store.images.isEmpty()); assertEquals(1, i.requestCount); assertTrue(store.errors.single().contains("401")); assertEquals(AgentState.FAILED, states.last())
        val tool = store.messages.first { it["role"]!!.jsonPrimitive.content == "tool" }
        assertFalse(json.parseToJsonElement(tool["content"]!!.jsonPrimitive.content).jsonObject["success"]!!.jsonPrimitive.boolean)
        c.takeRequest(); assertFalse(json.parseToJsonElement(c.takeRequest().body.readUtf8()).jsonObject.containsKey("tools"))
    }
    @Test fun streamingToolArgumentsExecuteExactlyOnce() = withServers { c, i ->
        val helper = NetworkTest()
        val events = listOf(helper.callDelta(0, "stream-call", "generate_image", "{\"pro"), helper.callDelta(0, arguments = "mpt\":\"moon\"}"), """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""", "[DONE]")
        c.enqueue(MockResponse().setBody(events.joinToString("\n\n", postfix = "\n\n") { "data: $it" }))
        c.enqueue(MockResponse().setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        i.enqueue(MockResponse().setBody(imageResponse))
        val store = MemoryStore(); store.append(userMessage("画月亮"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c, true), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions())
        assertEquals(1, i.requestCount); assertEquals(1, store.images.size); assertTrue(store.errors.isEmpty())
        assertEquals("stream-call", store.messages.first { it["role"]!!.jsonPrimitive.content == "tool" }["tool_call_id"]!!.jsonPrimitive.content)
    }
    @Test fun multipleCallsRespectBudgetAndReturnAllIds() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(toolResponse("one", "two", "three"))); c.enqueue(MockResponse().setBody(finalResponse))
        repeat(2) { i.enqueue(MockResponse().setBody(imageResponse)) }
        val store = MemoryStore(); store.append(userMessage("生成三个"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions(maxCalls = 2))
        assertEquals(2, i.requestCount); assertEquals(2, store.images.size)
        assertEquals(listOf("one","two","three"), store.messages.filter { it["role"]!!.jsonPrimitive.content == "tool" }.map { it["tool_call_id"]!!.jsonPrimitive.content })
    }
    @Test fun confirmationRejectedNeverChargesImageApi() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(toolResponse("confirm"))); c.enqueue(MockResponse().setBody(finalResponse))
        val store = MemoryStore(); store.append(userMessage("画图"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions(mode = "confirm"), confirm = { _,_ -> false })
        assertEquals(0, i.requestCount); assertTrue(store.messages.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("用户拒绝") == true })
    }
    @Test fun disabledToolsAreNotSentToModel() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(finalResponse)); val store = MemoryStore(); store.append(userMessage("你好"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions(mode = "disabled"))
        assertFalse(json.parseToJsonElement(c.takeRequest().body.readUtf8()).jsonObject.containsKey("tools")); assertEquals(0, i.requestCount)
    }
    @Test fun cancellationCompletesPendingToolProtocolWithoutRetry() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(toolResponse("cancel"))); i.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val store = MemoryStore(); store.append(userMessage("画图"))
        val worker = CoroutineScope(currentCoroutineContext()).launch {
            AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions())
        }
        withContext(Dispatchers.IO) { i.takeRequest() }
        withTimeout(2000) { worker.cancelAndJoin() }
        assertEquals(1, i.requestCount); assertTrue(store.images.isEmpty())
        assertEquals("cancel", store.messages.last()["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse(json.parseToJsonElement(store.messages.last()["content"]!!.jsonPrimitive.content).jsonObject["success"]!!.jsonPrimitive.boolean)
    }
    @Test fun dynamicSchemaAndStrictParameterValidation() {
        val cfg = ModelConfig(kind = "image", sizes = listOf("1024x1024"), maxImages = 2, qualities = emptyList())
        val tool = GenerateImageTool(cfg, "secret", 1, ImageApiClient(http), TestStorage())
        val props = tool.definition()["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertFalse(props.containsKey("quality")); assertEquals(1, props["n"]!!.jsonObject["maximum"]!!.jsonPrimitive.int)
        assertEquals(1, tool.validate(params)["n"]!!.jsonPrimitive.int)
        for (bad in listOf("{}", "{", """{"prompt":1}""", """{"prompt":" "}""", """{"prompt":"x","n":"1"}""", """{"prompt":"x","n":2}""", """{"prompt":"x","n":1.5}""", """{"prompt":"x","size":"invalid"}""", """{"prompt":"x","quality":"high"}""", """{"prompt":"x","other":1}""")) {
            assertThrows(IllegalArgumentException::class.java) { tool.validate(bad) }
        }
    }
    @Test fun unsupportedSizeOmittedFromSchemaAndRequest() {
        val tool = GenerateImageTool(ModelConfig(kind = "image", sizes = emptyList()), "", 1, ImageApiClient(http), TestStorage())
        assertFalse(tool.definition()["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject.containsKey("size"))
        assertFalse(tool.validate(params).containsKey("size"))
    }
    @Test fun extraParametersCannotOverridePromptOrModel() = withServers { _, i ->
        val cfg = imageConfig(i).copy(extraJson = """{"model":"wrong"}""")
        try { ImageApiClient(http).generate(cfg, "key", json.parseToJsonElement(params).jsonObject); fail("expected error") } catch (_: IllegalArgumentException) {}
        assertEquals(0, i.requestCount)
    }
    @Test fun repeatedCallIdNeverGeneratesTwice() = withServers { c, i ->
        c.enqueue(MockResponse().setBody(toolResponse("same"))); c.enqueue(MockResponse().setBody(toolResponse("same"))); c.enqueue(MockResponse().setBody(finalResponse))
        i.enqueue(MockResponse().setBody(imageResponse))
        val store = MemoryStore(); store.append(userMessage("生成图片"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions())
        assertEquals(1, i.requestCount); assertEquals(1, store.images.size); assertTrue(store.errors.single().contains("ID 已处理"))
    }
    @Test fun invalidArgumentsNeverReachPaidApi() = withServers { c, i ->
        val invalid = ToolCall("invalid", "generate_image", """{"prompt":"moon","n":99}""")
        c.enqueue(MockResponse().setBody(buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("message", ChatReply("", listOf(invalid)).wire()); put("finish_reason", "tool_calls") }) } }.toString()))
        c.enqueue(MockResponse().setBody(finalResponse))
        val store = MemoryStore(); store.append(userMessage("生成图片"))
        AgentOrchestrator(ChatApiClient(http)).run(chatConfig(c), "chat", store, ToolRegistry(listOf(GenerateImageTool(imageConfig(i), "image", 1, ImageApiClient(http), TestStorage()))), AgentOptions())
        assertEquals(0, i.requestCount); assertTrue(store.images.isEmpty()); assertTrue(store.errors.single().contains("数量"))
    }
    @Test fun invalidImageCredentialIsNotIncludedInError() = withServers { _, i ->
        val secret = "image-secret\ninvalid"
        val tool = GenerateImageTool(imageConfig(i), secret, 1, ImageApiClient(http), TestStorage())
        try { tool.execute(tool.validate(params)); fail("expected header error") }
        catch (e: java.io.IOException) { assertFalse(e.message!!.contains("image-secret")); assertTrue(e.message!!.contains("API Key")) }
        assertEquals(0, i.requestCount)
    }
}
