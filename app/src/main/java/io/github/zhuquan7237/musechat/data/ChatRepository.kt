package io.github.zhuquan7237.musechat.data

import androidx.room.withTransaction
import io.github.zhuquan7237.musechat.agent.*
import io.github.zhuquan7237.musechat.core.*
import kotlinx.serialization.json.*

class ChatRepository(private val db: MuseDatabase) {
    fun store(sessionId: String): AgentStore = object: AgentStore {
        override suspend fun history() = db.dao().history(sessionId).filter { it.kind == "wire" }.map { json.parseToJsonElement(it.wire).jsonObject }
        override suspend fun append(message: JsonObject) { db.dao().insertMessage(MessageEntity(sessionId = sessionId, wire = message.toString())) }
        override suspend fun completeTool(call: ToolCall, output: ToolOutput) {
            db.withTransaction {
                db.dao().insertImages(output.images.map { i -> ImageEntity(i.id, sessionId, call.id, i.path, i.prompt, i.model, i.parameters, i.width, i.height) })
                append(toolMessage(call.id, output.result))
            }
        }
        override suspend fun error(message: String) { db.dao().insertMessage(MessageEntity(sessionId = sessionId, kind = "error", text = message)) }
    }
    suspend fun recoverInterruptedTools() {
        for (session in db.dao().allSessions()) {
            val store = store(session.id)
            val pending = linkedMapOf<String, ToolCall>()
            for (message in store.history()) {
                message["tool_calls"]?.jsonArray?.forEach { value ->
                    val c = value.jsonObject; val f = c["function"]!!.jsonObject
                    val id = c["id"]!!.jsonPrimitive.content
                    pending[id] = ToolCall(id, f["name"]!!.jsonPrimitive.content, f["arguments"]!!.jsonPrimitive.content)
                }
                message["tool_call_id"]?.jsonPrimitive?.content?.let { pending.remove(it) }
            }
            for (call in pending.values) store.completeTool(call, ToolOutput(failure("应用进程中断，远程请求可能已计费；未自动重复执行")))
            if (pending.isNotEmpty()) store.error("上次任务被系统中断，未自动重试。请检查服务商任务记录后决定是否再次生成。")
        }
    }
}
