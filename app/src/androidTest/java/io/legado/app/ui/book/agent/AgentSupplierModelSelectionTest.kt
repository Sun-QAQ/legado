package io.legado.app.ui.book.agent

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.ui.main.MainActivity
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用本地合成供应商验证选择、持久化与界面同步，不发送 AI 请求。 */
@RunWith(AndroidJUnit4::class)
class AgentSupplierModelSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun selectorSwitchesProviderAndModelAndTracksConfigurationAndConversationChanges() {
        val context = instrumentation.targetContext
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val previousConversation = context.getPrefString(PreferKey.aiConversationId)
        val now = System.currentTimeMillis() + 60000
        val first = AiSource(id = now, name = "供应商甲", model = "model-a",
            models = GSON.toJson(listOf("model-a", "model-b", "model-b", "")), lastUpdateTime = now)
        val second = AiSource(id = now + 1, name = "供应商乙", model = "model-c", lastUpdateTime = now + 1)
        val disabled = AiSource(id = now + 2, name = "停用供应商", model = "hidden", enabled = false)
        val unconfigured = AiSource(id = now + 3, name = "未配置供应商", lastUpdateTime = now + 2)
        lateinit var fragment: AgentFragment
        lateinit var agent: AgentViewModel
        lateinit var label: TextView
        try {
            appDb.aiSourceDao.insert(first, second, disabled, unconfigured)
            context.putPrefLong(PreferKey.aiSupplierId, first.id)
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
                    label = fragment.requireView().findViewById(R.id.tv_supplier_model)
                }
                awaitMain { label.text.toString() == "供应商甲 · model-a" }
                instrumentation.runOnMainSync {
                    assertTrue(label.isEnabled)
                    val input = fragment.requireView().findViewById<View>(R.id.et_input)
                    val labelBounds = android.graphics.Rect()
                    val inputBounds = android.graphics.Rect()
                    assertTrue(label.getGlobalVisibleRect(labelBounds))
                    assertTrue(input.getGlobalVisibleRect(inputBounds))
                    assertTrue(labelBounds.bottom <= inputBounds.top)
                    label.performClick()
                }
                awaitMain { modelList(fragment).height > 0 }
                instrumentation.runOnMainSync {
                    val list = modelList(fragment)
                    val adapter = list.adapter as AgentModelAdapter
                    val rows = (0 until adapter.itemCount).map { adapter.getItem(it) }
                    assertFalse(rows.filterIsInstance<AgentModelItem.Supplier>().any { it.source.id == disabled.id })
                    val firstIndex = rows.indexOfFirst { it is AgentModelItem.Supplier && it.source.id == first.id }
                    assertEquals("model-a", (rows[firstIndex + 1] as AgentModelItem.Model).name)
                    assertEquals("model-b", (rows[firstIndex + 2] as AgentModelItem.Model).name)
                    assertEquals(2, rows.filterIsInstance<AgentModelItem.Model>().count { it.source.id == first.id })
                    assertTrue(rows.any { it is AgentModelItem.Hint && it.text == context.getString(R.string.ai_source_no_models) })
                    val panelBounds = android.graphics.Rect()
                    val labelBounds = android.graphics.Rect()
                    assertTrue(list.getGlobalVisibleRect(panelBounds))
                    assertTrue(label.getGlobalVisibleRect(labelBounds))
                    assertTrue(panelBounds.bottom <= labelBounds.top)
                    assertTrue(list.height <= (320 * context.resources.displayMetrics.density).toInt())
                }
                choose(fragment, first.id, "model-b")
                awaitMain { label.text.toString() == "供应商甲 · model-b" }
                assertEquals("model-b", appDb.aiSourceDao.get(first.id)!!.model)
                assertEquals(first.id, context.getPrefLong(PreferKey.aiSupplierId))
                instrumentation.runOnMainSync {
                    assertFalse(modelList(fragment).isVisible)
                    label.performClick()
                    assertTrue(modelList(fragment).isVisible)
                    label.performClick()
                    assertFalse(modelList(fragment).isVisible)
                    assertEquals("供应商甲 · model-b", label.text.toString())
                    label.performClick()
                }
                choose(fragment, second.id, "model-c")
                awaitMain { label.text.toString() == "供应商乙 · model-c" }
                assertEquals(second.id, context.getPrefLong(PreferKey.aiSupplierId))
                @Suppress("UNCHECKED_CAST")
                val waiting = AgentViewModel::class.java.getDeclaredField("_waiting")
                    .apply { isAccessible = true }.get(agent) as MutableStateFlow<Boolean>
                instrumentation.runOnMainSync {
                    label.performClick()
                    waiting.value = true
                }
                awaitMain { !label.isEnabled && !modelList(fragment).isVisible }
                instrumentation.runOnMainSync { waiting.value = false }
                awaitMain { label.isEnabled }
                instrumentation.runOnMainSync {
                    label.performClick()
                    assertTrue(modelList(fragment).isVisible)
                    fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
                    assertFalse(modelList(fragment).isVisible)
                    assertEquals(second.id, agent.currentSupplierId.value)
                    // 历史对话恢复供应商时，仅 ID 变化也必须刷新显示。
                    agent.selectSupplier(first.id, first.name)
                }
                awaitMain { label.text.toString() == "供应商甲 · model-b" }
                appDb.aiSourceDao.update(appDb.aiSourceDao.get(first.id)!!.copy(model = "model-updated"))
                awaitMain { label.text.toString() == "供应商甲 · model-updated" }
                instrumentation.runOnMainSync {
                    fragment.parentFragmentManager.beginTransaction().detach(fragment).commitNow()
                    fragment.parentFragmentManager.beginTransaction().attach(fragment).commitNow()
                    label = fragment.requireView().findViewById(R.id.tv_supplier_model)
                }
                awaitMain { label.text.toString() == "供应商甲 · model-updated" }
                appDb.aiSourceDao.update(appDb.aiSourceDao.get(first.id)!!.copy(enabled = false))
                awaitMain { agent.currentSupplierId.value != first.id && label.text.toString() != "供应商甲 · model-updated" }
                instrumentation.runOnMainSync {
                    agent.selectSupplier(second.id, second.name)
                }
                awaitMain { label.text.toString() == "供应商乙 · model-c" }
                instrumentation.runOnMainSync {
                    label.performClick()
                }
                awaitMain { modelList(fragment).height > 0 }
                instrumentation.runOnMainSync {
                    File(context.cacheDir, "agent_supplier_model_preview.png").outputStream().use {
                        fragment.requireView().drawToBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        } finally {
            appDb.aiSourceDao.delete(first, second, disabled, unconfigured)
            context.putPrefLong(PreferKey.aiSupplierId, previousSupplier)
            context.putPrefString(PreferKey.aiConversationId, previousConversation)
        }
    }

    private fun modelList(fragment: AgentFragment): RecyclerView =
        fragment.requireView().findViewById(R.id.model_list)

    private fun choose(fragment: AgentFragment, supplierId: Long, model: String) {
        var position = -1
        instrumentation.runOnMainSync {
            val list = modelList(fragment)
            val adapter = list.adapter as AgentModelAdapter
            position = (0 until adapter.itemCount).first {
                val item = adapter.getItem(it)
                item is AgentModelItem.Model && item.source.id == supplierId && item.name == model
            }
            list.scrollToPosition(position)
        }
        awaitMain { modelList(fragment).findViewHolderForAdapterPosition(position) != null }
        instrumentation.runOnMainSync {
            val row = modelList(fragment).findViewHolderForAdapterPosition(position)!!.itemView
            assertTrue(row.performClick())
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("等待供应商及模型界面更新超时")
    }
}
