package io.legado.app.help.voice

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class VoiceAiIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun disablingAiDuringRequestClosesConnectionAndPreventsLateNavigation() {
        val enabled = context.getPrefBoolean(VoiceAssistant.ENABLED)
        val aiEnabled = context.getPrefBoolean(VoiceAssistant.AI_ENABLED)
        val selectedId = context.getPrefLong(VoiceAiInterpreter.SOURCE_ID)
        val selectedModel = context.getPrefString(VoiceAiInterpreter.MODEL).orEmpty()
        val received = AtomicBoolean()
        val disconnected = AtomicBoolean()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val source = AiSource(id = Long.MAX_VALUE - 123, name = "Voice AI integration fixture",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "test-model")
        assertNull(appDb.aiSourceDao.get(source.id))
        Thread({
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read(); if (byte < 0) return@Thread
                        headers.append(byte.toChar())
                    }
                    val size = Regex("(?i)content-length: (\\d+)").find(headers)!!.groupValues[1].toInt()
                    java.io.DataInputStream(input).readFully(ByteArray(size))
                    received.set(true)
                    // Deliver headers, then hold the body until cancellation closes the socket.
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 500\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                    }
                    disconnected.set(input.read() == -1)
                }
            } catch (_: Exception) { disconnected.set(received.get()) }
        }, "voice-ai-device-fixture").apply { isDaemon = true; start() }
        appDb.aiSourceDao.insert(source)
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}").close()
        try {
            instrumentation.runOnMainSync {
                context.putPrefBoolean(VoiceAssistant.ENABLED, false)
                context.putPrefLong(VoiceAiInterpreter.SOURCE_ID, source.id)
                context.putPrefString(VoiceAiInterpreter.MODEL, source.model)
                context.putPrefBoolean(VoiceAssistant.AI_ENABLED, true)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { (VoiceAssistant.activity as? MainActivity)?.voiceAssistantReady == true }
                scenario.onActivity {
                    it.navigateByVoice(false)
                    context.putPrefBoolean(VoiceAssistant.ENABLED, true)
                    VoiceAssistant.acceptRecognizedText("请带我去发现新书的那个页面")
                }
                await { received.get() }
                scenario.onActivity { context.putPrefBoolean(VoiceAssistant.AI_ENABLED, false) }
                await { disconnected.get() }
                scenario.onActivity { assertEquals(0, it.findViewById<ViewPager>(R.id.view_pager_main).currentItem) }
            }
        } finally {
            server.close()
            instrumentation.runOnMainSync {
                context.putPrefBoolean(VoiceAssistant.ENABLED, false)
                context.putPrefBoolean(VoiceAssistant.AI_ENABLED, aiEnabled)
                context.putPrefLong(VoiceAiInterpreter.SOURCE_ID, selectedId)
                context.putPrefString(VoiceAiInterpreter.MODEL, selectedModel)
                context.putPrefBoolean(VoiceAssistant.ENABLED, enabled)
            }
            appDb.aiSourceDao.delete(source)
        }
    }

    /** Explicit opt-in for a real provider; sends synthetic context only, never the user's shelf. */
    @Test fun configuredProviderUnderstandsRecentBookAndFollowingAction() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveVoiceAi") == "true")
        val source = VoiceAiInterpreter.source()
        val synthetic = VoiceAiContext(listOf(
            VoiceAiBook("b0", "示例旧书", "示例作者", System.currentTimeMillis() - 86_400_000, "fixture://old"),
            VoiceAiBook("b1", "示例新书", "示例作者", System.currentTimeMillis() - 60_000, "fixture://recent")
        ), emptyList(), "bookshelf")
        val answer = VoiceAiInterpreter.request(source,
            VoiceAiInterpreter.requestBody(source, "打开我上次看的那本书，然后开启眼动翻页", synthetic))
        assertEquals(VoiceAiDecision.Execute(listOf(
            VoiceCommand.OpenShelfBook("fixture://recent"), VoiceCommand.EyePage(true)
        )), VoiceAiProtocol.decode(answer, synthetic))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for voice AI state")
    }
}
