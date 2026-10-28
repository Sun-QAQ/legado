package io.legado.app.ui.book.agent

import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.ui.main.MainActivity
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
                    val container = FrameLayout(activity).apply { id = View.generateViewId() }
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
                    val providers = dialog(fragment)
                    val names = (0 until providers.listView.count).map { providers.listView.adapter.getItem(it).toString() }
                    assertFalse(names.contains(disabled.name))
                    choose(providers, first.name)
                    val models = dialog(fragment)
                    assertEquals(2, models.listView.count)
                    assertEquals(0, models.listView.checkedItemPosition)
                    choose(models, "model-b")
                }
                awaitMain { label.text.toString() == "供应商甲 · model-b" }
                assertEquals("model-b", appDb.aiSourceDao.get(first.id)!!.model)
                assertEquals(first.id, context.getPrefLong(PreferKey.aiSupplierId))
                instrumentation.runOnMainSync {
                    label.performClick()
                    choose(dialog(fragment), second.name)
                    dialog(fragment).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                    assertEquals("供应商甲 · model-b", label.text.toString())
                    label.performClick()
                    choose(dialog(fragment), second.name)
                    choose(dialog(fragment), "model-c")
                }
                awaitMain { label.text.toString() == "供应商乙 · model-c" }
                assertEquals(second.id, context.getPrefLong(PreferKey.aiSupplierId))
                @Suppress("UNCHECKED_CAST")
                val waiting = AgentViewModel::class.java.getDeclaredField("_waiting")
                    .apply { isAccessible = true }.get(agent) as MutableStateFlow<Boolean>
                instrumentation.runOnMainSync {
                    label.performClick()
                    waiting.value = true
                }
                awaitMain { !label.isEnabled && !dialog(fragment).isShowing }
                instrumentation.runOnMainSync { waiting.value = false }
                awaitMain { label.isEnabled }
                instrumentation.runOnMainSync {
                    label.performClick()
                    choose(dialog(fragment), unconfigured.name)
                    assertEquals(context.getString(R.string.ai_source_no_models),
                        dialog(fragment).findViewById<TextView>(android.R.id.message)!!.text.toString())
                    dialog(fragment).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
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

    private fun dialog(fragment: AgentFragment): AlertDialog =
        AgentFragment::class.java.getDeclaredField("supplierDialog")
            .apply { isAccessible = true }.get(fragment) as AlertDialog

    private fun choose(dialog: AlertDialog, text: String) {
        val list = dialog.listView
        val index = (0 until list.count).first { list.adapter.getItem(it).toString() == text }
        list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
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
