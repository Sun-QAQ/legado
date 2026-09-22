package io.legado.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.res.use
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.lib.theme.ThemeDrawables
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.getCompatColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePaletteTest {
    @Test
    fun customColorsReachInflatedViewsAndPressedDrawables() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = ThemePalette(context)
        try {
            ActivityScenario.launch<SearchActivity>(Intent(context, SearchActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.viewInflaterClass)).use {
                        assertEquals("io.legado.app.lib.theme.ThemeViewInflater", it.getString(0))
                    }
                    // 同一进程中换两组差异明显的颜色，覆盖亮/暗背景以及颜色缓存失效。
                    for ((background, accent) in listOf("#E8F5E9" to "#00695C", "#14232A" to "#FFCA28")) {
                        ThemeStore.editTheme(activity)
                            .primaryColor(Color.parseColor("#1565C0"))
                            .accentColor(Color.parseColor(accent))
                            .backgroundColor(Color.parseColor(background))
                            .bottomBackground(Color.parseColor("#37474F"))
                            .apply()
                        activity.initTheme()
                        activity.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarStyle)).use {
                            val expected = if (background == "#E8F5E9") R.style.AppTheme_AppBarOverlay_Light
                                else R.style.AppTheme_AppBarOverlay_Dark
                            assertEquals(expected, it.getResourceId(0, 0))
                        }
                        val palette = ThemePalette(activity)
                        val category = activity.layoutInflater.inflate(R.layout.view_preference_category, null)
                        assertEquals(palette.accent, category.findViewById<TextView>(R.id.preference_title).currentTextColor)
                        val row = activity.layoutInflater.inflate(R.layout.item_rss_article_2, null)
                        val imageBackground = row.findViewById<View>(R.id.image_view).background as GradientDrawable
                        assertEquals(palette.background, imageBackground.color!!.defaultColor)
                        assertEquals(palette.text, row.findViewById<TextView>(R.id.tv_title).currentTextColor)
                        assertEquals(palette.primary, activity.getCompatColor(R.color.primary))
                        assertEquals(palette.bottom, activity.getCompatColor(R.color.navigation_bar_bag))
                        val selector = ThemeDrawables.get(activity, R.drawable.bg_explore_action)!!
                        selector.state = intArrayOf()
                        val normal = (selector.current as GradientDrawable).color!!.defaultColor
                        selector.state = intArrayOf(android.R.attr.state_pressed)
                        val pressed = (selector.current as GradientDrawable).color!!.defaultColor
                        assertEquals(palette.surface, normal)
                        assertEquals(palette.accentContainer, pressed)
                        assertNotEquals(normal, pressed)
                        // 业务状态色不属于用户主题色。
                        assertEquals(activity.getColor(R.color.error), activity.getCompatColor(R.color.error))
                    }
                }
            }
        } finally {
            ThemeStore.editTheme(context)
                .primaryColor(original.primary).accentColor(original.accent)
                .backgroundColor(original.background).bottomBackground(original.bottom).apply()
        }
    }
}
