package io.legado.app

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.shape.MaterialShapeDrawable
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.widget.TitleBar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadMenuThemeTest {
    @Test
    fun togglingTitleAdditionKeepsToolbarColorsInLightAndDarkThemes() = withSettings { activity ->
        AppConfig.readBarStyleFollowPage = false
        for (background in listOf("#F3F0E8", "#18242D")) {
            ThemeStore.editTheme(activity)
                .primaryColor(Color.parseColor("#1976D2"))
                .backgroundColor(Color.parseColor(background))
                .bottomBackground(Color.parseColor("#526B5D"))
                .apply()
            activity.initTheme()
            val palette = ThemePalette(activity)
            val menu = ReadMenu(activity)
            val titleBar = menu.findViewById<TitleBar>(R.id.title_bar)
            assertEquals(palette.background, backgroundColor(titleBar))
            assertEquals(palette.text, titleColor(titleBar))
            for (show in listOf(false, true, false)) {
                AppConfig.showReadTitleBarAddition = show
                menu.reset()
                assertEquals("Changing title details must not recolor the toolbar",
                    palette.background, backgroundColor(titleBar))
                assertEquals(palette.text, titleColor(titleBar))
                assertEquals(palette.secondaryText,
                    menu.findViewById<TextView>(R.id.tv_chapter_name).currentTextColor)
                assertEquals(if (show) View.VISIBLE else View.GONE,
                    menu.findViewById<View>(R.id.title_bar_addition).visibility)
            }
        }
    }

    @Test
    fun leavingPageFollowingRestoresThemeColorsAndRepeatedResetsKeepThem() = withSettings { activity ->
        val palette = ThemePalette(activity)
        val pageBackground = Color.parseColor("#C9E4D0")
        val pageText = Color.parseColor("#314638")
        ReadBookConfig.durConfig = ReadBookConfig.durConfig.copy().apply {
            setCurBg(0, "#C9E4D0")
            setCurTextColor(pageText)
        }
        AppConfig.readBarStyleFollowPage = true
        val menu = ReadMenu(activity)
        val titleBar = menu.findViewById<TitleBar>(R.id.title_bar)
        repeat(2) {
            assertEquals(pageBackground, backgroundColor(titleBar))
            assertEquals(pageText, titleColor(titleBar))
            menu.reset()
        }
        AppConfig.readBarStyleFollowPage = false
        repeat(2) {
            menu.reset()
            assertEquals(palette.background, backgroundColor(titleBar))
            assertEquals(palette.text, titleColor(titleBar))
        }
    }

    private fun backgroundColor(titleBar: TitleBar) =
        (titleBar.background as MaterialShapeDrawable).fillColor!!.defaultColor

    private fun titleColor(titleBar: TitleBar): Int {
        titleBar.title = "测试书籍"
        return titleBar.toolbar.children.filterIsInstance<TextView>()
            .single { it.text == titleBar.title }.currentTextColor
    }

    private fun withSettings(test: (SearchActivity) -> Unit) {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val original = ThemePalette(activity)
                val originalFollow = AppConfig.readBarStyleFollowPage
                val originalAddition = AppConfig.showReadTitleBarAddition
                val originalEInk = AppConfig.isEInkMode
                val originalConfig = ReadBookConfig.durConfig
                val originalSharedConfig = ReadBookConfig.shareConfig
                try {
                    AppConfig.isEInkMode = false
                    test(activity)
                } finally {
                    AppConfig.readBarStyleFollowPage = originalFollow
                    AppConfig.showReadTitleBarAddition = originalAddition
                    AppConfig.isEInkMode = originalEInk
                    ReadBookConfig.durConfig = originalConfig
                    ReadBookConfig.shareConfig = originalSharedConfig
                    ThemeStore.editTheme(activity)
                        .primaryColor(original.primary).accentColor(original.accent)
                        .backgroundColor(original.background).bottomBackground(original.bottom).apply()
                }
            }
        }
    }
}
