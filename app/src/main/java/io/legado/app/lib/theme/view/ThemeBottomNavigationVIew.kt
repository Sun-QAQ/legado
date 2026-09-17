package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    init {
        applyThemeColors()
        post { applyThemeColors() }

        if (AppConfig.isEInkMode) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = ColorDrawable(TRANSPARENT)
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    private fun makeBackgroundTransparent() {
        backgroundTintList = null
        background = ColorDrawable(TRANSPARENT)
    }

    fun applyThemeColors() {
        makeBackgroundTransparent()
        val bottomBackground = context.bottomBackground
        val isLightBackground = ColorUtils.isColorLight(bottomBackground)
        val colors = resolveBottomNavigationThemeColors(
            accent = ThemeStore.accentColor(context),
            isLightBackground = isLightBackground,
            defaultItemColor = context.getSecondaryTextColor(isLightBackground)
        )
        setItemActiveIndicatorColor(ColorStateList.valueOf(colors.activeIndicatorColor))
        val itemColors = Selector.colorBuild()
            .setDefaultColor(colors.defaultItemColor)
            .setSelectedColor(colors.selectedItemColor)
            .create()
        itemIconTintList = itemColors
        itemTextColor = itemColors
    }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}

internal data class BottomNavigationThemeColors(
    val activeIndicatorColor: Int,
    val defaultItemColor: Int,
    val selectedItemColor: Int
)

internal fun resolveBottomNavigationThemeColors(
    accent: Int,
    isLightBackground: Boolean,
    defaultItemColor: Int
): BottomNavigationThemeColors {
    val targetColor = if (isLightBackground) OPAQUE_WHITE else OPAQUE_BLACK
    val ratio = if (isLightBackground) 0.8f else 0.65f
    return BottomNavigationThemeColors(
        activeIndicatorColor = blendArgb(accent, targetColor, ratio),
        defaultItemColor = defaultItemColor,
        selectedItemColor = accent
    )
}

private fun blendArgb(color1: Int, color2: Int, ratio: Float): Int {
    val inverseRatio = 1f - ratio
    fun component(color: Int, shift: Int) = color ushr shift and 0xFF
    fun blend(shift: Int): Int {
        return (component(color1, shift) * inverseRatio +
            component(color2, shift) * ratio).toInt()
    }
    return blend(24) shl 24 or
        (blend(16) shl 16) or
        (blend(8) shl 8) or
        blend(0)
}

private const val TRANSPARENT = 0x00000000
private const val OPAQUE_WHITE = -0x1
private const val OPAQUE_BLACK = -0x1000000
