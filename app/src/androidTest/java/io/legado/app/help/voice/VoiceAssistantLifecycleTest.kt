package io.legado.app.help.voice

import android.Manifest
import android.media.AudioRecord
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceAssistantLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun microphoneStopsOnBackgroundAndDisableAndResumesOnlyInForeground() {
        val previous = context.getPrefBoolean(VoiceAssistant.ENABLED)
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}"
        ).close()
        instrumentation.runOnMainSync { context.putPrefBoolean(VoiceAssistant.ENABLED, false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { VoiceAssistant.activity is MainActivity &&
                    (VoiceAssistant.activity as MainActivity).voiceAssistantReady }
                scenario.onActivity { context.putPrefBoolean(VoiceAssistant.ENABLED, true) }
                await { recording() }
                scenario.moveToState(Lifecycle.State.CREATED)
                await { !recording() }
                assertTrue(context.getPrefBoolean(VoiceAssistant.ENABLED))
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { recording() }
                scenario.onActivity { VoiceAssistant.listenNow() }
                // The listening prompt plays with capture stopped.
                await { !recording() }
                await { recording() }
                scenario.onActivity { context.putPrefBoolean(VoiceAssistant.ENABLED, false) }
                await { !recording() }
                await { engineField.get(VoiceAssistant) == null }
            }
        } finally {
            instrumentation.runOnMainSync { context.putPrefBoolean(VoiceAssistant.ENABLED, previous) }
        }
    }

    @Test fun navigationUsesRealPageAndReaderCommandsReportWrongContext() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { (VoiceAssistant.activity as? MainActivity)?.voiceAssistantReady == true }
            runBlocking {
                withContext(Dispatchers.Main) {
                    assertEquals("已返回书架", VoiceActions.execute(VoiceCommand.OpenPage(false)).message)
                    assertEquals("请先打开一本书，再翻页", VoiceActions.execute(VoiceCommand.TurnPage(true)).message)
                    assertEquals("请先进入阅读页面，再设置眼动翻页", VoiceActions.execute(VoiceCommand.EyePage(true)).message)
                }
            }
            scenario.onActivity {
                assertEquals(0, it.findViewById<ViewPager>(R.id.view_pager_main).currentItem)
            }
        }
    }

    private val engineField = VoiceAssistant::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private val recorderField = OfflineVoiceEngine::class.java.getDeclaredField("recorder").apply { isAccessible = true }

    private fun recording(): Boolean {
        val engine = engineField.get(VoiceAssistant) ?: return false
        val recorder = recorderField.get(engine) as? AudioRecord ?: return false
        return recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for voice assistant state")
    }
}
