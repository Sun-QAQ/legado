package io.legado.app.ui.book.agent

import android.os.SystemClock
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComfyUiConfigurationTest {

    @Test
    fun workflowPlaceholdersCompileAndExpandOnAndroid() {
        val prompt = "猫\"\n{{width}}"
        val request = ComfyUiImageGenerationHelper.buildRequest(
            AiImageSource(customBody = WORKFLOW), prompt, "768x512", 123
        )
        val inputs = request.getAsJsonObject("prompt")["1"].asJsonObject.getAsJsonObject("inputs")
        assertEquals("风景，$prompt", inputs["text"].asString)
        assertEquals(768, inputs["width"].asInt)
        assertEquals(512, inputs["height"].asInt)
        assertEquals(123L, inputs["seed"].asLong)
        assertTrue(inputs["width"].asJsonPrimitive.isNumber)
        assertEquals("{{unknown}}", inputs["other"].asString)
    }

    @Test
    fun savingNodeWorkflowPersistsAndCanBeReopened() = saveAndReopen(WORKFLOW)

    @Test
    fun savingFullRequestWorkflowPersistsAndCanBeReopened() =
        saveAndReopen("""{"prompt":$WORKFLOW,"extra_data":{}}""")

    private fun saveAndReopen(workflow: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = "comfy-save-test-${UUID.randomUUID()}"
        try {
            ActivityScenario.launch(AiImageSourceManageActivity::class.java).use { scenario ->
                lateinit var dialog: AiImageSourceEditDialog
                scenario.onActivity { activity ->
                    dialog = AiImageSourceEditDialog()
                    dialog.showNow(activity.supportFragmentManager, "comfy-save-test")
                }
                instrumentation.waitForIdleSync()
                onView(withId(R.id.tv_api_type)).perform(click())
                onView(withText("ComfyUI")).perform(click())
                instrumentation.runOnMainSync {
                    val view = requireNotNull(dialog.view)
                    view.findViewById<EditText>(R.id.tv_name).setText(name)
                    view.findViewById<EditText>(R.id.tv_base_url).setText("http://127.0.0.1:8188")
                    view.findViewById<EditText>(R.id.tv_custom_body).setText(workflow)
                    view.findViewById<Toolbar>(R.id.tool_bar).menu.performIdentifierAction(R.id.menu_save, 0)
                }
                val deadline = SystemClock.uptimeMillis() + 5_000
                var saved = appDb.aiImageSourceDao.all.firstOrNull { it.name == name }
                while (saved == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(50)
                    saved = appDb.aiImageSourceDao.all.firstOrNull { it.name == name }
                }
                assertNotNull("保存后应写入数据库", saved)
                val source = requireNotNull(saved)
                assertEquals(AiImageSource.TYPE_COMFYUI, source.apiType)
                assertEquals(workflow, source.customBody)
                assertEquals("", source.model)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.executePendingTransactions()
                    assertFalse("保存成功后关闭编辑框", dialog.isAdded)
                    dialog = AiImageSourceEditDialog(source.id)
                    dialog.showNow(activity.supportFragmentManager, "comfy-reopen-test")
                }
                val reopenDeadline = SystemClock.uptimeMillis() + 5_000
                var loaded = false
                while (!loaded && SystemClock.uptimeMillis() < reopenDeadline) {
                    instrumentation.runOnMainSync {
                        loaded = dialog.requireView().findViewById<EditText>(R.id.tv_name).text.toString() == name
                    }
                    if (!loaded) SystemClock.sleep(50)
                }
                assertTrue("重新打开后应加载已保存配置", loaded)
                onView(withId(R.id.tv_api_type)).check(matches(withText("ComfyUI")))
                instrumentation.runOnMainSync {
                    val view = requireNotNull(dialog.view)
                    assertEquals(workflow, view.findViewById<EditText>(R.id.tv_custom_body).text.toString())
                    dialog.dismissNow()
                }
            }
        } finally {
            appDb.aiImageSourceDao.all.filter { it.name == name }.forEach(appDb.aiImageSourceDao::delete)
        }
    }

    companion object {
        private const val WORKFLOW = """{"1":{"class_type":"TestNode","inputs":{"text":"风景，{{prompt}}","width":"{{width}}","height":"{{height}}","seed":"{{seed}}","other":"{{unknown}}"}}}"""
    }
}
