package io.legado.app.lib.theme.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.databinding.ActivityReplaceEditBinding
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.setAccentCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ThemeEditTextTest {
    @Test
    fun replaceInputsKeepCursorAndAllHandlesTheSameColorAsFocusedBorder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalAccent = ThemeStore.accentColor(context)
        try {
            ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    // 使用区别于系统默认蓝色的两组颜色，覆盖真实替换规则输入框。
                    for (accent in listOf(Color.rgb(0, 105, 92), Color.rgb(255, 193, 7))) {
                        ThemeStore.editTheme(activity).accentColor(accent).apply()
                        val binding = ActivityReplaceEditBinding.inflate(activity.layoutInflater)
                        activity.setContentView(binding.root)
                        binding.etName.setText("替换规则名称")
                        binding.etName.requestFocus()
                        assertEquals(accent, binding.tilName.boxStrokeColor)
                        assertInputColor(binding.etName, accent)
                        binding.etGroup.requestFocus()
                        binding.etName.requestFocus()
                        assertInputColor(binding.etName, accent)
                        binding.etName.setSelection(0, binding.etName.length())
                        assertInputColor(binding.etName, accent)
                    }
                }
            }
        } finally {
            ThemeStore.editTheme(context).accentColor(originalAccent).apply()
        }
    }

    @Test
    fun refreshingThemePreservesFocusListenerAndUpdatesExistingHandles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalAccent = ThemeStore.accentColor(context)
        try {
            ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val input = ThemeEditText(activity)
                    val listener = View.OnFocusChangeListener { _, _ -> }
                    input.onFocusChangeListener = listener
                    for (accent in listOf(Color.MAGENTA, Color.GREEN)) {
                        ThemeStore.editTheme(activity).accentColor(accent).apply()
                        repeat(3) { input.setAccentCursor() }
                        assertSame(listener, input.onFocusChangeListener)
                        assertInputColor(input, accent)
                    }
                    // 重新附着时由控件自身刷新主题色，无需调用方手动同步。
                    val accent = Color.rgb(156, 39, 176)
                    ThemeStore.editTheme(activity).accentColor(accent).apply()
                    activity.setContentView(input)
                    assertInputColor(input, accent)
                }
            }
        } finally {
            ThemeStore.editTheme(context).accentColor(originalAccent).apply()
        }
    }

    private fun assertInputColor(input: ThemeEditText, accent: Int) {
        assertDrawableColor(input.textCursorDrawable!!, accent)
        for (handle in listOf(input.textSelectHandle, input.textSelectHandleLeft, input.textSelectHandleRight)) {
            assertDrawableColor(handle!!, accent)
            // 框架尝试使用静态蓝色重染时，仍保留当前主题色。
            handle.setTint(Color.BLUE)
            assertDrawableColor(handle, accent)
        }
    }

    private fun assertDrawableColor(drawable: Drawable, expected: Int) {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val oldBounds = android.graphics.Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(96 * 96)
            bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            val opaque = pixels.filter { Color.alpha(it) == 255 }
            assertTrue("Drawable 应包含不透明像素", opaque.isNotEmpty())
            // 手柄边缘的抗锯齿/预乘运算可能让 RGB 分量出现一级取整误差。
            assertTrue(
                "${drawable.javaClass.name} 颜色应为 ${Integer.toHexString(expected)}，" +
                    "实际为 ${opaque.distinct().joinToString { Integer.toHexString(it) }}",
                opaque.any { it == expected } && opaque.all {
                    abs(Color.red(it) - Color.red(expected)) <= 1 &&
                        abs(Color.green(it) - Color.green(expected)) <= 1 &&
                        abs(Color.blue(it) - Color.blue(expected)) <= 1
                }
            )
        } finally {
            drawable.bounds = oldBounds
            bitmap.recycle()
        }
    }
}
