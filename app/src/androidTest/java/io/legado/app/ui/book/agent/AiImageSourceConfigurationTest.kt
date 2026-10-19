package io.legado.app.ui.book.agent

import android.graphics.Bitmap
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiImageSourceConfigurationTest {
    @Test
    fun switchingApiTypeShowsWorkflowHelpAndPreservesOpenAiFields() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(AiImageSourceManageActivity::class.java).use { scenario ->
            lateinit var dialog: AiImageSourceEditDialog
            scenario.onActivity { activity ->
                dialog = AiImageSourceEditDialog()
                dialog.show(activity.supportFragmentManager, "comfyui-config-test")
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val view = requireNotNull(dialog.view)
                view.findViewById<EditText>(R.id.tv_model).setText("original-model")
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_model).visibility)
            }
            onView(withId(R.id.tv_api_type)).perform(click())
            onView(withText("ComfyUI")).perform(click())
            instrumentation.runOnMainSync {
                val view = requireNotNull(dialog.view)
                assertEquals(View.GONE, view.findViewById<View>(R.id.layout_model).visibility)
                assertEquals(View.GONE, view.findViewById<View>(R.id.layout_response_format).visibility)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.tv_workflow_help).visibility)
                assertTrue(view.findViewById<TextView>(R.id.tv_workflow_help).text.contains("\"{{prompt}}\""))
                view.findViewById<EditText>(R.id.tv_name).setText("ComfyUI")
                view.findViewById<EditText>(R.id.tv_base_url).setText("http://192.168.1.10:8188")
            }
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "comfyui-config.png")
                file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                screenshot.recycle()
            }
            onView(withId(R.id.tv_api_type)).perform(click())
            onView(withText(instrumentation.targetContext.getString(R.string.ai_image_api_openai))).perform(click())
            instrumentation.runOnMainSync {
                val view = requireNotNull(dialog.view)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_model).visibility)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_response_format).visibility)
                assertEquals(View.GONE, view.findViewById<View>(R.id.tv_workflow_help).visibility)
                assertEquals("original-model", view.findViewById<EditText>(R.id.tv_model).text.toString())
                dialog.dismissNow()
            }
        }
    }
}
