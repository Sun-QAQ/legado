package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import io.legado.app.R

/** 界面颜色只从主题设置的四个颜色派生，不另设界面色板。 */
class ThemePalette(context: Context) {
    val primary = ThemeStore.primaryColor(context)
    val accent = ThemeStore.accentColor(context)
    val background = ThemeStore.backgroundColor(context)
    val bottom = ThemeStore.bottomBackground(context)
    private val light = io.legado.app.utils.ColorUtils.isColorLight(background)
    val text = if (light) Color.rgb(33, 33, 33) else Color.rgb(238, 238, 238)
    val secondaryText = ColorUtils.blendARGB(background, text, 0.7f)
    val surface = ColorUtils.blendARGB(background, Color.WHITE, if (light) 0.5f else 0.04f)
    val divider = ColorUtils.blendARGB(background, text, 0.12f)
    val accentContainer = ColorUtils.blendARGB(background, accent, 0.15f)

    fun color(id: Int): Int? = when (id) {
        R.color.primary -> primary
        R.color.primaryDark -> io.legado.app.utils.ColorUtils.darkenColor(primary)
        R.color.accent, R.color.accent_activated -> accent
        R.color.background, R.color.color_surface -> background
        R.color.background_card, R.color.color_card -> surface
        R.color.background_menu, R.color.color_surface_variant -> surface
        R.color.background_prefs -> ColorUtils.setAlphaComponent(surface, 128)
        R.color.navigation_bar_bag -> bottom
        R.color.color_primary_container -> accentContainer
        R.color.bg_divider_line -> divider
        R.color.primaryText -> text
        R.color.secondaryText, R.color.tv_text_summary, R.color.menu_color_default -> secondaryText
        else -> null
    }

    companion object {
        // 先判断资源 ID，避免普通颜色查询初始化 ThemeStore，或递归读取它的兜底资源。
        fun isThemeColor(id: Int): Boolean = id in themeColors

        private val themeColors = setOf(
            R.color.primary, R.color.primaryDark, R.color.accent, R.color.accent_activated,
            R.color.background, R.color.color_surface, R.color.background_card, R.color.color_card,
            R.color.background_menu, R.color.color_surface_variant, R.color.background_prefs,
            R.color.navigation_bar_bag, R.color.color_primary_container, R.color.bg_divider_line,
            R.color.primaryText, R.color.secondaryText, R.color.tv_text_summary, R.color.menu_color_default
        )
    }
}
