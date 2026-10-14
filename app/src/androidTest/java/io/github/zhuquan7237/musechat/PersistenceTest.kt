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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun keystoreCredentialsAreEncryptedAndRemovable() {
        val repo = CredentialRepository(context); val id = "test-" + newId(); val secret = "fixture-secret-" + newId()
        try {
            repo.save(id, secret)
            assertEquals(secret, CredentialRepository(context).get(id))
            val ciphertext = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE).getString(id, "")!!
            assertFalse(ciphertext.contains(secret)); assertTrue(ciphertext.contains(":"))
            repo.delete(id); assertEquals("", repo.get(id))
        } finally { repo.delete(id) }
    }
    @Test fun realImageAndToolHistorySurviveDatabaseReopen() = runBlocking {
        val name = "test-" + newId() + ".db"
        var db = Room.databaseBuilder(context, MuseDatabase::class.java, name).build()
        val repository = ImageRepository(context, HttpTransport())
        var saved: GeneratedImage? = null
        try {
            val session = newId(); db.dao().insertSession(SessionEntity(session, "测试"))
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
            val parameters = buildJsonObject { put("prompt", "test") }
            saved = repository.save(ImageSource.Base64(Base64.getEncoder().encodeToString(png)), "test", ModelConfig(model = "test-image"), parameters)
            assertEquals(4, saved.width); assertTrue(File(saved.path).exists()); assertArrayEquals(png, File(saved.path).readBytes())
            val call = ToolCall("test-call", "generate_image", parameters.toString())
            val store = ChatRepository(db).store(session)
            store.append(userMessage("画图")); store.append(ChatReply("", listOf(call)).wire())
            store.completeTool(call, ToolOutput(buildJsonObject { put("success", true) }, listOf(saved)))
            db.close(); db = Room.databaseBuilder(context, MuseDatabase::class.java, name).build()
            assertEquals(3, ChatRepository(db).store(session).history().size)
            val images = db.dao().sessionImages(session)
            assertEquals(1, images.size); assertEquals("test-call", images.single().toolCallId); assertEquals(saved.path, images.single().path)
            assertTrue(File(images.single().path).exists())
        } finally { db.close(); context.deleteDatabase(name); saved?.let { repository.remove(listOf(it)) } }
    }
    @Test fun interruptedToolIsRecoveredWithoutReexecution() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDatabase::class.java).build()
        try {
            val id = newId(); db.dao().insertSession(SessionEntity(id, "中断"))
            val repo = ChatRepository(db); val store = repo.store(id)
            store.append(ChatReply("", listOf(ToolCall("pending", "generate_image", "{}"))).wire())
            repo.recoverInterruptedTools(); repo.recoverInterruptedTools()
            val history = store.history()
            assertEquals(2, history.size); assertEquals("pending", history.last()["tool_call_id"]!!.jsonPrimitive.content)
            assertFalse(json.parseToJsonElement(history.last()["content"]!!.jsonPrimitive.content).jsonObject["success"]!!.jsonPrimitive.boolean)
            assertTrue(db.dao().sessionImages(id).isEmpty())
        } finally { db.close() }
    }
    @Test fun invalidImageNeverPersistsAFile() = runBlocking {
        val repo = ImageRepository(context, HttpTransport())
        val params = buildJsonObject { put("prompt", "test") }
        try { repo.save(ImageSource.Base64(Base64.getEncoder().encodeToString("not-an-image".toByteArray())), "test", ModelConfig(), params); fail("expected error") }
        catch (_: IllegalArgumentException) {}
    }
}
