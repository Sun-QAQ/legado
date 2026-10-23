package io.legado.app.ui.widget.code

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic editor text only; does not create, save or execute a book source. */
@RunWith(AndroidJUnit4::class)
class CodeViewRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun longTextKeepsSelectionAndForeignSpansWhileHighlighting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var editor: CodeView
            val text = "const count = 42; // 中文注释\nlet label = \"return @js:\";\n" +
                "const value = 1;\n".repeat(100)
            val foreignSpan = ForegroundColorSpan(0xff123456.toInt())
            scenario.onActivity { activity ->
                editor = CodeView(activity).apply {
                    useSourceSyntax(SourceSyntaxHighlighter.Language.JAVASCRIPT)
                    setText(text)
                    setSelection(8, 11)
                    editableText.setSpan(foreignSpan, 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setPadding(24, 24, 24, 24)
                }
                activity.setContentView(editor)
            }
            awaitMain { editor.editableText.getSpans(1024, text.length, ForegroundColorSpan::class.java).isNotEmpty() }
            instrumentation.runOnMainSync {
                assertEquals(text, editor.text.toString())
                assertEquals(8, editor.selectionStart)
                assertEquals(11, editor.selectionEnd)
                assertEquals(6, editor.editableText.getSpanStart(foreignSpan))
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.externalCacheDir, "source-code-highlight.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }

    @Test
    fun rapidReplacementAndEmptyTextDiscardOldHighlightResults() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var editor: CodeView
            scenario.onActivity { activity ->
                editor = CodeView(activity).apply {
                    useSourceSyntax(SourceSyntaxHighlighter.Language.JAVASCRIPT)
                }
                activity.setContentView(editor)
                editor.setText("let old = 1;\n".repeat(5000))
            }
            SystemClock.sleep(170)
            instrumentation.runOnMainSync {
                editor.setText("return false;")
                editor.setSelection(3)
            }
            awaitMain { editor.editableText.getSpans(0, 6, ForegroundColorSpan::class.java).isNotEmpty() }
            instrumentation.runOnMainSync {
                assertEquals("return false;", editor.text.toString())
                assertEquals(3, editor.selectionStart)
                editor.setTextHighlighted("")
            }
            SystemClock.sleep(300)
            instrumentation.runOnMainSync {
                assertEquals("", editor.text.toString())
                assertTrue(editor.editableText.getSpans(0, 0, ForegroundColorSpan::class.java).isEmpty())
            }
        }
    }

    @Test
    fun recycledEditorClearsOldColorsAndHighlightsAgainAfterAttach() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var editor: CodeView
            lateinit var container: FrameLayout
            scenario.onActivity { activity ->
                container = FrameLayout(activity)
                editor = CodeView(activity).apply {
                    useSourceSyntax(SourceSyntaxHighlighter.Language.JAVASCRIPT)
                    setText("const count = 1;")
                }
                container.addView(editor, ViewGroup.LayoutParams(-1, -1))
                activity.setContentView(container)
            }
            awaitMain { editor.editableText.getSpans(0, 5, ForegroundColorSpan::class.java).isNotEmpty() }
            instrumentation.runOnMainSync {
                container.removeView(editor)
                editor.useSourceSyntax(SourceSyntaxHighlighter.Language.PLAIN)
                container.addView(editor)
            }
            awaitMain { editor.editableText.getSpans(0, editor.length(), ForegroundColorSpan::class.java).isEmpty() }
            instrumentation.runOnMainSync {
                container.removeView(editor)
                editor.useSourceSyntax(SourceSyntaxHighlighter.Language.JSON)
                editor.setText("{\"enabled\": true}")
                container.addView(editor)
            }
            awaitMain { editor.editableText.getSpans(1, 10, ForegroundColorSpan::class.java).isNotEmpty() }
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Timed out waiting for editor highlighting")
    }
}
