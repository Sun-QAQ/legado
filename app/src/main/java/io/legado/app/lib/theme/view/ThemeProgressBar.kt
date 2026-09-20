package io.legado.app.lib.theme.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ProgressBar
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.applyTint

class ThemeProgressBar(context: Context, attrs: AttributeSet) : ProgressBar(context, attrs) {

    init {
        if (!isInEditMode) {
            applyTint(context.accentColor)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        //ViewHolder 复用或宿主未重建时,重新附着会再次读取当前主题强调色
        if (!isInEditMode) {
            applyTint(context.accentColor)
        }
    }
}