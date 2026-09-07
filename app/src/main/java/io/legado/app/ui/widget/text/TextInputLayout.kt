package io.legado.app.ui.widget.text

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore

class TextInputLayout(context: Context, attrs: AttributeSet?) : TextInputLayout(context, attrs) {

    init {
        if (!isInEditMode) {
            defaultHintTextColor =
                Selector.colorBuild().setDefaultColor(ThemeStore.accentColor(context)).create()
            val defaultStroke = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOutline,
                ThemeStore.accentColor(context)
            )
            setBoxStrokeColorStateList(
                Selector.colorBuild()
                    .setDefaultColor(defaultStroke)
                    .setFocusedColor(ThemeStore.accentColor(context))
                    .create()
            )
            setCursorColor(ColorStateList.valueOf(ThemeStore.accentColor(context)))
        }
    }

}
