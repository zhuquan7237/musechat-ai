package io.github.zhuquan7237.musechat.agent

import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.network.ChatProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

interface AgentStore {
    suspend fun history(): List<JsonObject>
    suspend fun append(message: JsonObject)
    suspend fun completeTool(call: ToolCall, output: ToolOutput)
    suspend fun error(message: String)
}
class AgentOrchestrator(private val chat: ChatProvider) {
    suspend fun run(config: ModelConfig, key: String, store: AgentStore, registry: ToolRegistry?, options: AgentOptions,
        onState: (AgentState) -> Unit = {}, onText: (String) -> Unit = {}, confirm: suspend (ToolCall, JsonObject) -> Boolean = { _,_ -> true }
    ) {
        val pending = linkedMapOf<String, ToolCall>()
        var streamed = ""
        var executions = 0
        var hadFailure = false
        val completedIds = store.history().mapNotNull { it["tool_call_id"]?.jsonPrimitive?.contentOrNull }.toMutableSet()
        try {
            for (round in 0..options.maxCalls + 1) {
                onState(if (round == 0) AgentState.MODEL_REQUESTING else AgentState.MODEL_CONTINUING)
                streamed = ""; onText("")
                val enabled = config.tools && options.mode != "disabled" && registry != null && executions < options.maxCalls
                val system = buildJsonObject {
                    put("role", "system")
                    put("content", "你是 MuseChat AI 助手。只有工具成功返回才可以说图片已生成；没有生成工具时说明用户需配置生图服务或开启工具。讨论创意时只回复文字。当前只支持文生图，不支持参考图编辑，不保证与前图人物一致性。" +
                        if (options.optimize) "生图时可补充构图、光线与风格，但保留用户明确要求。" else "生图提示词尽量忠实使用用户原话，不擅自扩写。")
                }
                val reply = chat.complete(config, key, listOf(system) + store.history(), if (enabled) registry!!.definitions() else null) { streamed = it; onText(it) }
                pending.putAll(reply.calls.associateBy { it.id })
                withContext(NonCancellable) { store.append(reply.wire()) }
                streamed = ""; onText("")
                if (reply.calls.isEmpty()) { onState(if (hadFailure) AgentState.FAILED else AgentState.COMPLETED); return }
                for (call in reply.calls) {
                    currentCoroutineContext().ensureActive()
                    onState(AgentState.TOOL_CALL_RECEIVED)
                    val output = try {
                        require(enabled && executions < options.maxCalls) { "工具禁用、未配置生图 API 或本轮调用次数已达上限" }
                        require(call.id !in completedIds) { "工具调用 ID 已处理，拒绝重复执行" }
                        val tool = registry!!.get(call.name)
                        val params = tool.validate(call.arguments)
                        if (options.mode == "confirm" && !confirm(call, params)) ToolOutput(failure("用户拒绝此次工具调用"))
                        else { executions++; tool.execute(params) }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        hadFailure = true
                        val message = safeError(e, key)
                        store.error(message)
                        ToolOutput(failure(message))
                    }
                    withContext(NonCancellable) { store.completeTool(call, output); pending.remove(call.id); completedIds += call.id }
                }
                if (hadFailure) executions = options.maxCalls // Never retry a failed paid request automatically.
            }
            throw IllegalStateException("模型持续请求工具，已停止以保护调用额度")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                pending.values.forEach { store.completeTool(it, ToolOutput(failure("本地任务已取消；远程服务仍可能计费，未自动重试"))) }
                if (streamed.isNotBlank() && pending.isEmpty()) store.append(ChatReply(streamed + "（已停止）", emptyList()).wire())
            }
            onText(""); onState(AgentState.CANCELLED); throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                pending.values.forEach { store.completeTool(it, ToolOutput(failure("本地任务中断，未自动重试"))) }
                store.error(safeError(e, key))
            }
            onText(""); onState(AgentState.FAILED)
        }
    }
}
fun safeError(e: Exception, vararg keys: String): String {
    var text = when (e) {
        is java.net.SocketTimeoutException -> "请求超时；不会自动重试生图。请先检查服务商记录"
        is java.io.IOException -> e.message ?: "网络失败，请检查网络和服务商"
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "参数或响应无效"
        else -> "请求处理失败（${e.javaClass.simpleName}），请检查配置和接口兼容性"
    }
    keys.filter { it.isNotBlank() }.forEach { text = text.replace(it, "[已隐藏]") }
    return text.take(600)
}
