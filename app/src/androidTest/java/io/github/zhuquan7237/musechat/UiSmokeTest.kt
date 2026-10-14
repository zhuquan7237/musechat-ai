package io.github.zhuquan7237.musechat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import io.github.zhuquan7237.musechat.agent.ToolOutput
import io.github.zhuquan7237.musechat.core.*
import io.github.zhuquan7237.musechat.data.SessionEntity
import io.github.zhuquan7237.musechat.network.ImageSource
import io.github.zhuquan7237.musechat.ui.AppViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun appLaunchesAndAllFourNativePagesOpen() {
        compose.onNodeWithText("从一句灵感开始").assertExists()
        compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
        compose.onNodeWithText("让灵感成为画面").assertExists()
        compose.onNodeWithText("模型", useUnmergedTree = true).performClick()
        compose.onNodeWithText("连接你自己的 AI").assertExists()
        compose.onNodeWithText("添加聊天模型").performClick()
        compose.onNodeWithText("API Base URL（包含 /v1 等前缀）").assertExists()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("工具与费用保护").assertExists()
    }
    @Test fun savedToolImageAppearsInCurrentChatAndGallery() {
        val app = compose.activity.application as MuseApplication
        val session = newId()
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.waitUntil(5000) { vm.ready.value }
        val previous = vm.selected.value
        var path = ""
        try {
            runBlocking {
                app.db.dao().insertSession(SessionEntity(session, "图片显示测试"))
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
                val p = buildJsonObject { put("prompt", "instrumentation-blue-square") }
                val image = app.images.save(ImageSource.Base64(Base64.getEncoder().encodeToString(png)), "instrumentation-blue-square", ModelConfig(model = "test-fixture"), p)
                path = image.path
                val call = ToolCall("ui-" + newId(), "generate_image", p.toString())
                val store = app.chats.store(session)
                store.append(userMessage("仅用于仪器测试的图片")); store.append(ChatReply("", listOf(call)).wire())
                store.completeTool(call, ToolOutput(buildJsonObject { put("success", true) }, listOf(image)))
            }
            compose.runOnUiThread { vm.selectSession(session) }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("instrumentation-blue-square").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("instrumentation-blue-square").assertIsDisplayed()
            compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("instrumentation-blue-square").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { vm.selectSession(previous) }
            runBlocking { app.db.dao().deleteSession(session) }
            if (path.isNotEmpty()) File(path).delete()
        }
    }
}
