package io.legado.app.ui.book.agent

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.data.entities.AiModelThinking
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
import android.app.Application
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** 使用本地合成供应商验证选择、持久化与界面同步，不发送 AI 请求。 */
@RunWith(AndroidJUnit4::class)
class AgentSupplierModelSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun independentThinkingSavesAndStaleModelSelectionPreserveLatestSettings() {
        val context = instrumentation.targetContext
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val source = AiSource(id = System.currentTimeMillis() + 120000, name = "思考配置测试", model = "model-a")
        val edit = AiSourceEditViewModel(context.applicationContext as Application)
        val manage = AiSourceManageViewModel(context.applicationContext as Application)
        val completed = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        val high = AiModelThinking("enabled", "high", "thinking")
        val low = AiModelThinking("enabled", "low", "qwen")
        try {
            appDb.aiSourceDao.insert(source)
            instrumentation.runOnMainSync {
                listOf("model-a" to high, "model-b" to low).forEach { (model, config) ->
                    edit.saveModelThinking(source.id, model, config,
                        success = { completed.countDown() },
                        error = { failure.set(it); completed.countDown() })
                }
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(high, appDb.aiSourceDao.get(source.id)!!.getModelThinking("model-a"))
            assertEquals(low, appDb.aiSourceDao.get(source.id)!!.getModelThinking("model-b"))
            appDb.aiSourceDao.update(appDb.aiSourceDao.get(source.id)!!.copy(lastUpdateTime = 1))
            // 选择列表中的旧对象仍须保留数据库中刚保存的模型思考配置。
            instrumentation.runOnMainSync { manage.select(source, "model-b") }
            awaitMain { appDb.aiSourceDao.get(source.id)!!.lastUpdateTime != 1L }
            val selected = appDb.aiSourceDao.get(source.id)!!
            assertEquals("model-b", selected.model)
            assertEquals(high, selected.getModelThinking("model-a"))
            assertEquals(low, selected.getModelThinking("model-b"))
        } finally {
            appDb.aiSourceDao.delete(source)
            context.putPrefLong(PreferKey.aiSupplierId, previousSupplier)
        }
    }

    @Test
    fun inlineGroupsSwitchModelsAndTrackConfigurationAndConversationChanges() {
        val context = instrumentation.targetContext
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val previousConversation = context.getPrefString(PreferKey.aiConversationId)
        val now = System.currentTimeMillis() + 60000
        val first = AiSource(id = now, name = "供应商甲", model = "model-a",
            models = GSON.toJson(listOf("model-a", "model-b", "model-b", "")), lastUpdateTime = now,
            modelThinking = GSON.toJson(mapOf(
                "model-a" to AiModelThinking("enabled", "", "thinking"),
                "model-b" to AiModelThinking("enabled", "low", "qwen")
            )))
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
                val heading = showItem(fragment) { it is AgentModelItem.Supplier && it.source.id == first.id }
                instrumentation.runOnMainSync {
                    val name = heading.findViewById<TextView>(R.id.tv_name)
                    assertTrue(name.typeface.isBold)
                    assertEquals(0, name.paddingStart)
                    assertTrue(heading.findViewById<View>(R.id.iv_expand).isVisible)
                }
                val model = showItem(fragment) { it is AgentModelItem.Model && it.source.id == first.id && it.name == "model-a" }
                instrumentation.runOnMainSync {
                    val name = model.findViewById<TextView>(R.id.tv_name)
                    assertFalse(name.typeface?.isBold ?: false)
                    assertEquals(name.paint.measureText("\u3000\u3000").roundToInt(), name.paddingStart)
                    assertEquals("model-a", name.text.toString())
                    assertTrue(model.findViewById<View>(R.id.iv_selected).isVisible)
                    assertNull(model.findViewById<View>(R.id.btn_thinking))
                    val button = fragment.requireView().findViewById<TextView>(R.id.btn_thinking)
                    assertTrue(button.isVisible)
                    assertEquals(context.getString(R.string.ai_thinking_level_short,
                        context.getString(R.string.ai_thinking_default_short)), button.text.toString())
                    val nameBounds = android.graphics.Rect()
                    val buttonBounds = android.graphics.Rect()
                    label.getGlobalVisibleRect(nameBounds)
                    button.getGlobalVisibleRect(buttonBounds)
                    assertTrue(nameBounds.right <= buttonBounds.left)
                }
                val highLabel = context.resources.getStringArray(R.array.ai_thinking_efforts)[4]
                chooseThinking(fragment, first.id, "model-a", highLabel)
                awaitMain { appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-a").effort == "high" }
                assertEquals(AiModelThinking("enabled", "high", "thinking"), appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-a"))
                assertEquals(AiModelThinking("enabled", "low", "qwen"), appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-b"))
                chooseThinking(fragment, first.id, "model-a", context.resources.getStringArray(R.array.ai_thinking_modes)[2])
                awaitMain { appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-a").mode == "disabled" }
                chooseThinking(fragment, first.id, "model-a", context.getString(R.string.ai_thinking_default_short))
                awaitMain { appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-a").mode == "default" }
                chooseThinking(fragment, first.id, "model-a", highLabel)
                awaitMain { rows(fragment).filterIsInstance<AgentModelItem.Model>().any {
                    it.source.id == first.id && it.name == "model-a" && it.source.getModelThinking(it.name).mode == "enabled"
                } }
                selectModel(fragment, second.id, "model-c")
                chooseThinking(fragment, second.id, "model-c", context.resources.getStringArray(R.array.ai_thinking_efforts)[6])
                awaitMain { appDb.aiSourceDao.get(second.id)!!.getModelThinking("model-c").effort == "max" }
                assertEquals("high", appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-a").effort)
                assertEquals("model-a", appDb.aiSourceDao.get(first.id)!!.model)
                selectModel(fragment, first.id, "model-a")
                instrumentation.runOnMainSync {
                    val button = fragment.requireView().findViewById<TextView>(R.id.btn_thinking)
                    assertEquals(context.getString(R.string.ai_thinking_level_short, highLabel), button.text.toString())
                    assertFalse(modelList(fragment).isVisible)
                    assertEquals(first.id, agent.currentSupplierId.value)
                    label.performClick()
                }
                toggleGroup(fragment, first.id)
                awaitMain { rows(fragment).none { it is AgentModelItem.Model && it.source.id == first.id } }
                instrumentation.runOnMainSync {
                    assertTrue(modelList(fragment).isVisible)
                    assertEquals(first.id, agent.currentSupplierId.value)
                    assertTrue(rows(fragment).any { it is AgentModelItem.Model && it.source.id == second.id })
                }
                toggleGroup(fragment, second.id)
                awaitMain { rows(fragment).none { it is AgentModelItem.Model && it.source.id == second.id } }
                // 配置刷新、关闭和重开列表均应保留各分组的收起状态。
                appDb.aiSourceDao.update(appDb.aiSourceDao.get(second.id)!!.copy(lastUpdateTime = now + 3))
                awaitMain {
                    rows(fragment).filterIsInstance<AgentModelItem.Supplier>()
                        .any { it.source.id == second.id && it.source.lastUpdateTime == now + 3 }
                }
                instrumentation.runOnMainSync {
                    label.performClick()
                    label.performClick()
                    assertTrue(rows(fragment).none { it is AgentModelItem.Model && it.source.id in listOf(first.id, second.id) })
                }
                toggleGroup(fragment, first.id)
                awaitMain { rows(fragment).count { it is AgentModelItem.Model && it.source.id == first.id } == 2 }
                instrumentation.runOnMainSync {
                    assertTrue(rows(fragment).none { it is AgentModelItem.Model && it.source.id == second.id })
                }
                toggleGroup(fragment, second.id)
                awaitMain { rows(fragment).any { it is AgentModelItem.Model && it.source.id == second.id } }
                choose(fragment, first.id, "model-b")
                awaitMain { label.text.toString() == "供应商甲 · model-b" }
                assertEquals("model-b", appDb.aiSourceDao.get(first.id)!!.model)
                assertEquals(first.id, context.getPrefLong(PreferKey.aiSupplierId))
                awaitMain {
                    fragment.requireView().findViewById<TextView>(R.id.btn_thinking).text.toString() ==
                        context.getString(R.string.ai_thinking_level_short, context.resources.getStringArray(R.array.ai_thinking_efforts)[2])
                }
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
                instrumentation.runOnMainSync {
                    assertFalse(fragment.requireView().findViewById<View>(R.id.btn_thinking).isEnabled)
                }
                instrumentation.runOnMainSync { waiting.value = false }
                awaitMain { label.isEnabled }
                instrumentation.runOnMainSync {
                    fragment.requireView().findViewById<View>(R.id.btn_thinking).performClick()
                    assertTrue(fragment.requireView().findViewById<View>(R.id.thinking_panel).isVisible)
                    assertFalse(modelList(fragment).isVisible)
                    waiting.value = true
                }
                awaitMain { !fragment.requireView().findViewById<View>(R.id.thinking_panel).isVisible }
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
                assertEquals(AiModelThinking("enabled", "high", "thinking"), appDb.aiSourceDao.get(first.id)!!.getModelThinking("model-a"))
                instrumentation.runOnMainSync {
                    fragment.parentFragmentManager.beginTransaction().detach(fragment).commitNow()
                }
                // 视图绑定在主线程队列中释放，等待释放后再重建视图。
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
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
                awaitMain(details = {
                    val list = modelList(fragment)
                    "list=${list.visibility}/${list.width}x${list.height}, root=${fragment.requireView().width}x${fragment.requireView().height}, label=${label.isEnabled}/${label.width}x${label.height}, rows=${list.adapter?.itemCount}"
                }) { modelList(fragment).height > 0 }
                instrumentation.runOnMainSync {
                    label.performClick()
                    assertFalse(modelList(fragment).isVisible)
                    File(context.cacheDir, "agent_supplier_model_preview.png").outputStream().use {
                        fragment.requireActivity().window.decorView.drawToBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
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
        val row = showItem(fragment) {
            it is AgentModelItem.Model && it.source.id == supplierId && it.name == model
        }
        instrumentation.runOnMainSync { assertTrue(row.performClick()) }
    }

    private fun selectModel(fragment: AgentFragment, supplierId: Long, model: String) {
        instrumentation.runOnMainSync {
            if (!modelList(fragment).isVisible) fragment.requireView().findViewById<View>(R.id.tv_supplier_model).performClick()
        }
        choose(fragment, supplierId, model)
        awaitMain {
            val agent = ViewModelProvider(fragment)[AgentViewModel::class.java]
            val source = appDb.aiSourceDao.get(supplierId)!!
            agent.currentSupplierId.value == supplierId && source.model == model &&
                fragment.requireView().findViewById<TextView>(R.id.tv_supplier_model).text.toString() == "${source.name} · $model"
        }
    }

    private fun chooseThinking(fragment: AgentFragment, supplierId: Long, model: String, label: String) {
        lateinit var options: RadioGroup
        lateinit var panel: NestedScrollView
        lateinit var target: RadioButton
        instrumentation.runOnMainSync {
            assertEquals(supplierId, ViewModelProvider(fragment)[AgentViewModel::class.java].currentSupplierId.value)
            val button = fragment.requireView().findViewById<View>(R.id.btn_thinking)
            assertTrue(button.contentDescription.toString().contains(model))
            button.performClick()
            assertFalse(modelList(fragment).isVisible)
            panel = fragment.requireView().findViewById(R.id.thinking_panel)
            assertTrue(panel.isVisible)
            options = fragment.requireView().findViewById(R.id.thinking_options)
            val buttons = options.children.filterIsInstance<RadioButton>().toList()
            assertEquals(RadioGroup.VERTICAL, options.orientation)
            assertEquals(listOf(
                fragment.resources.getStringArray(R.array.ai_thinking_modes)[2],
                fragment.getString(R.string.ai_thinking_default_short)
            ) + fragment.resources.getStringArray(R.array.ai_thinking_efforts).drop(1), buttons.map { it.text.toString() })
            assertEquals(1, buttons.count { it.isChecked })
            target = buttons.first { it.text.toString() == label }
        }
        awaitMain { options.children.all { it.width > 0 && it.height > 0 } }
        instrumentation.runOnMainSync {
            val buttons = options.children.toList()
            assertTrue(buttons.zipWithNext().all { (first, next) ->
                first.left == next.left && first.right == next.right && first.bottom <= next.top
            })
            panel.scrollTo(0, target.top)
        }
        awaitMain {
            val bounds = android.graphics.Rect()
            target.getGlobalVisibleRect(bounds) && bounds.height() == target.height
        }
        instrumentation.runOnMainSync {
            assertTrue(target.performClick())
            assertFalse(panel.isVisible)
            assertEquals(supplierId, ViewModelProvider(fragment)[AgentViewModel::class.java].currentSupplierId.value)
        }
    }

    private fun toggleGroup(fragment: AgentFragment, supplierId: Long) {
        val row = showItem(fragment) { it is AgentModelItem.Supplier && it.source.id == supplierId }
        instrumentation.runOnMainSync { assertTrue(row.performClick()) }
    }

    private fun rows(fragment: AgentFragment): List<AgentModelItem?> {
        val adapter = modelList(fragment).adapter as AgentModelAdapter
        return (0 until adapter.itemCount).map { adapter.getItem(it) }
    }

    private fun showItem(fragment: AgentFragment, matches: (AgentModelItem?) -> Boolean): View {
        instrumentation.waitForIdleSync()
        var position = -1
        instrumentation.runOnMainSync {
            val list = modelList(fragment)
            val adapter = list.adapter as AgentModelAdapter
            position = (0 until adapter.itemCount).first {
                matches(adapter.getItem(it))
            }
            list.scrollToPosition(position)
        }
        awaitMain { modelList(fragment).findViewHolderForAdapterPosition(position) != null }
        lateinit var row: View
        instrumentation.runOnMainSync { row = modelList(fragment).findViewHolderForAdapterPosition(position)!!.itemView }
        return row
    }

    private fun awaitMain(details: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        var diagnostic = ""
        instrumentation.runOnMainSync { diagnostic = details() }
        throw AssertionError("等待供应商及模型界面更新超时 $diagnostic")
    }
}
