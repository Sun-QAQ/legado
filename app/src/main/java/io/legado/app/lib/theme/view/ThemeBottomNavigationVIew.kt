package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
        makeBackgroundTransparent()
        post { makeBackgroundTransparent() }
        // 选中胶囊指示器：由强调色派生的容器色调，选中图标仍用强调色保持对比
        val accent = ThemeStore.accentColor(context)
        val isLight = ColorUtils.isColorLight(context.bottomBackground)
        val accentContainer = if (isLight) {
            ColorUtils.blendColors(accent, Color.WHITE, 0.8f)
        } else {
            ColorUtils.blendColors(accent, Color.BLACK, 0.65f)
        }
        setItemActiveIndicatorColor(ColorStateList.valueOf(accentContainer))

        val textColor = context.getSecondaryTextColor(ColorUtils.isColorLight(context.bottomBackground))
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context)).create()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList

        if (AppConfig.isEInkMode) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = ColorDrawable(Color.TRANSPARENT)
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    private fun makeBackgroundTransparent() {
        backgroundTintList = null
        background = ColorDrawable(Color.TRANSPARENT)
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
