package io.legado.app.ui.book.agent

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPersona
import io.legado.app.data.entities.AiSource
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 验证标题快捷切换及请求中的人格提示词，不发送真实 AI 请求。 */
@RunWith(AndroidJUnit4::class)
class AgentPersonaTitleSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun titleSwitchesPersonasAndDefaultAndTracksEditsAndDeletion() {
        val context = instrumentation.targetContext
        val previousPersona = context.getPrefLong(PreferKey.aiPersonaId)
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val previousConversation = context.getPrefString(PreferKey.aiConversationId)
        val now = System.currentTimeMillis() + 180000
        val first = AiPersona(now, "测试人格甲", "合成人格甲的独特提示词", now)
        val second = AiPersona(now + 1, "测试人格乙", "合成人格乙的独特提示词", now + 1)
        lateinit var fragment: AgentFragment
        lateinit var agent: AgentViewModel
        lateinit var title: TextView
        lateinit var list: ListView
        try {
            appDb.aiPersonaDao.insert(first, second)
            context.putPrefLong(PreferKey.aiPersonaId, first.id)
            context.putPrefString(PreferKey.aiConversationId, "")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val container = FrameLayout(activity).apply {
                        id = View.generateViewId()
                        setBackgroundColor(activity.backgroundColor)
                    }
                    activity.setContentView(container)
                    fragment = AgentFragment()
                    activity.supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
                    agent = ViewModelProvider(fragment)[AgentViewModel::class.java]
                    title = fragment.requireView().findViewById(R.id.tv_agent_persona)
                    list = fragment.requireView().findViewById(R.id.persona_list)
                    fragment.requireView().findViewById<TextView>(R.id.et_input).text = "保留输入草稿"
                }
                awaitMain { title.text.toString() == first.name }
                instrumentation.runOnMainSync { assertTrue(title.performClick()) }
                awaitMain { list.isVisible && list.height > 0 }
                instrumentation.runOnMainSync {
                    assertEquals(context.getString(R.string.agent_persona_default), list.adapter.getItem(0))
                    assertEquals(first.name, list.adapter.getItem(list.checkedItemPosition))
                    val titleBounds = android.graphics.Rect()
                    val listBounds = android.graphics.Rect()
                    title.getGlobalVisibleRect(titleBounds)
                    list.getGlobalVisibleRect(listBounds)
                    assertTrue(listBounds.top >= titleBounds.bottom)
                    choose(list, second.name)
                }
                awaitMain { title.text.toString() == second.name && agent.currentPersonaId.value == second.id }
                assertEquals(second.id, context.getPrefLong(PreferKey.aiPersonaId))
                instrumentation.runOnMainSync {
                    assertFalse(list.isVisible)
                    assertTrue(systemPrompt(agent).contains(second.prompt))
                    assertFalse(systemPrompt(agent).contains(first.prompt))
                    assertEquals("保留输入草稿", fragment.requireView().findViewById<TextView>(R.id.et_input).text.toString())
                    title.performClick()
                    choose(list, context.getString(R.string.agent_persona_default))
                }
                awaitMain { agent.currentPersonaId.value == 0L && title.text.toString() == context.getString(R.string.agent_persona_default) }
                assertEquals(0L, context.getPrefLong(PreferKey.aiPersonaId))
                instrumentation.runOnMainSync {
                    assertTrue(systemPrompt(agent).contains(DEFAULT_AGENT_PERSONA_PROMPT))
                    assertFalse(systemPrompt(agent).contains(second.prompt))
                    title.performClick()
                    fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
                    assertFalse(list.isVisible)
                    title.performClick()
                    fragment.requireView().findViewById<View>(R.id.tv_supplier_model).performClick()
                    assertFalse(list.isVisible)
                    assertTrue(fragment.requireView().findViewById<View>(R.id.model_list).isVisible)
                    title.performClick()
                    assertTrue(list.isVisible)
                    assertFalse(fragment.requireView().findViewById<View>(R.id.model_list).isVisible)
                }
                @Suppress("UNCHECKED_CAST")
                val waiting = AgentViewModel::class.java.getDeclaredField("_waiting")
                    .apply { isAccessible = true }.get(agent) as MutableStateFlow<Boolean>
                instrumentation.runOnMainSync { waiting.value = true }
                awaitMain { !title.isEnabled && !list.isVisible }
                instrumentation.runOnMainSync { waiting.value = false }
                awaitMain { title.isEnabled }
                instrumentation.runOnMainSync {
                    title.performClick()
                    choose(list, first.name)
                }
                awaitMain { title.text.toString() == first.name }
                val edited = first.copy(name = "测试人格甲改名", prompt = "合成人格甲更新后的独特提示词")
                appDb.aiPersonaDao.update(edited)
                awaitMain { title.text.toString() == edited.name }
                instrumentation.runOnMainSync { assertTrue(systemPrompt(agent).contains(edited.prompt)) }
                appDb.aiPersonaDao.delete(first.id)
                awaitMain { title.text.toString() == context.getString(R.string.agent_persona_default) && agent.currentPersonaId.value == 0L }
                instrumentation.runOnMainSync { fragment.parentFragmentManager.beginTransaction().detach(fragment).commitNow() }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    fragment.parentFragmentManager.beginTransaction().attach(fragment).commitNow()
                    title = fragment.requireView().findViewById(R.id.tv_agent_persona)
                    list = fragment.requireView().findViewById(R.id.persona_list)
                }
                awaitMain { title.text.toString() == context.getString(R.string.agent_persona_default) && list.adapter?.count?.let { it > 0 } == true }
                instrumentation.runOnMainSync { title.performClick() }
                awaitMain { list.isVisible && list.height > 0 }
                instrumentation.runOnMainSync {
                    assertEquals(0, list.checkedItemPosition)
                    File(context.cacheDir, "agent_persona_title_preview.png").outputStream().use {
                        fragment.requireActivity().window.decorView.drawToBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        } finally {
            appDb.aiPersonaDao.delete(first.id)
            appDb.aiPersonaDao.delete(second.id)
            context.putPrefLong(PreferKey.aiPersonaId, previousPersona)
            context.putPrefLong(PreferKey.aiSupplierId, previousSupplier)
            context.putPrefString(PreferKey.aiConversationId, previousConversation)
        }
    }

    private fun choose(list: ListView, name: String) {
        val index = (0 until list.count).first { list.adapter.getItem(it).toString() == name }
        assertTrue(list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)))
    }

    private fun systemPrompt(agent: AgentViewModel): String {
        val build = AgentViewModel::class.java.getDeclaredMethod("buildChatRequest", AiSource::class.java, List::class.java)
            .apply { isAccessible = true }
        val body = build.invoke(agent, AiSource(model = "synthetic"), emptyList<AgentChatTurn>()) as JsonObject
        return body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("等待标题人格切换超时")
    }
}
