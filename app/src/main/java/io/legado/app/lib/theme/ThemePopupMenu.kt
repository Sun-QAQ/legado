package io.legado.app.lib.theme

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import io.legado.app.R
import io.legado.app.utils.dpToPx

/** 原生菜单保留交互行为，浮层和菜单项在显示时采用当前主题颜色。 */
object ThemePopupMenu {
    fun styleBackground(view: View) {
        view.background = ThemeDrawables.get(view.context, R.drawable.bg_popup_menu)
        view.clipToOutline = true
        view.elevation = 6f.dpToPx()
    }

    fun styleItemOnAttach(view: View) {
        // 菜单项的子控件在 onCreateView 返回后才 inflate，附着时再着色。
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val palette = ThemePalette(v.context)
                val colors = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(ColorUtils.setAlphaComponent(palette.text, 97), palette.text)
                )
                fun tint(child: View) {
                    if (child is TextView) child.setTextColor(colors)
                    if (child is ImageView) child.imageTintList = colors
                    if (child is CompoundButton) {
                        child.buttonTintList = ColorStateList.valueOf(palette.accent)
                    }
                    if (child is ViewGroup) child.children.forEach(::tint)
                }
                v.minimumHeight = 48.dpToPx()
                tint(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}
