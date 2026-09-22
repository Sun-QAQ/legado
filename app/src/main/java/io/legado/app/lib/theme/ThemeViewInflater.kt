package io.legado.app.lib.theme

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.CompoundButton
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.theme.MaterialComponentsViewInflater
import io.legado.app.help.config.AppConfig
import java.lang.reflect.Constructor

/**
 * AppCompat 的标准扩展点：在控件创建时把语义颜色解析为主题设置值。
 * 不替换 Resources，也不按最终色值猜测颜色，避免误改封面、阅读配色或状态色。
 */
@Keep
class ThemeViewInflater : MaterialComponentsViewInflater() {
    private val constructors = HashMap<String, Constructor<out View>>()

    override fun createView(context: Context, name: String, attrs: AttributeSet): View? {
        val className = if (name == "view") attrs.getAttributeValue(null, "class") else name
        if (className == null || className in setOf("fragment", "include", "merge")) return null
        val names = if ('.' in className) listOf(className) else
            listOf("android.widget.$className", "android.view.$className", "android.webkit.$className")
        for (candidate in names) {
            val constructor = constructors[candidate] ?: try {
                context.classLoader.loadClass(candidate).asSubclass(View::class.java)
                    .getConstructor(Context::class.java, AttributeSet::class.java)
                    .also { constructors[candidate] = it }
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: NoSuchMethodException) {
                continue
            }
            return constructor.newInstance(context, attrs).themed(attrs)
        }
        return null
    }

    private fun <T : View> T.themed(attrs: AttributeSet?): T = apply {
        if (isInEditMode) return@apply
        val palette = ThemePalette(context)
        val attributes = context.obtainStyledAttributes(attrs, COLOR_ATTRIBUTES)
        try {
            // TypedArray 会追踪 @color 别名到最终资源。先保留 XML 中原始语义 ID，
            // 否则 @color/accent -> @color/md_red_600 会失去“强调色”的含义。
            fun resource(index: Int): Int {
                val attribute = COLOR_ATTRIBUTES[index]
                val namespace = if (attribute ushr 24 == 1) ANDROID_NAMESPACE else APP_NAMESPACE
                val name = resources.getResourceEntryName(attribute)
                val original = attrs?.getAttributeResourceValue(namespace, name, 0) ?: 0
                return if (original != 0) original else attributes.getResourceId(index, 0)
            }
            fun color(index: Int) = palette.color(resource(index))
            ThemeDrawables.get(context, resource(0), palette)?.let {
                // setBackground 会采用 drawable padding；保留布局和自定义控件的实际 padding。
                val left = paddingLeft
                val top = paddingTop
                val right = paddingRight
                val bottom = paddingBottom
                background = it
                setPadding(left, top, right, bottom)
            }
            color(1)?.let { backgroundTintList = ColorStateList.valueOf(it) }
            if (this is TextView) {
                color(2)?.let { setTextColor(it) }
                color(3)?.let { setHintTextColor(it) }
                color(4)?.let { setLinkTextColor(it) }
                highlightColor = ColorUtils.setAlphaComponent(palette.accent, 64)
            }
            if (this is ImageView) {
                (color(5) ?: color(6))?.let { imageTintList = ColorStateList.valueOf(it) }
            }
            color(7)?.let { backgroundTintList = ColorStateList.valueOf(it) }
            if (this is MaterialCardView) {
                setCardBackgroundColor(color(8) ?: palette.surface)
                strokeColor = color(9) ?: palette.divider
                rippleColor = ColorStateList.valueOf(palette.accentContainer)
            }
            if (this is EditText || this is ProgressBar || this is CompoundButton || this is CheckedTextView) {
                TintHelper.setTintAuto(this, palette.accent, false, AppConfig.isNightTheme)
            }
        } finally {
            attributes.recycle()
        }
    }

    override fun createTextView(context: Context, attrs: AttributeSet) = super.createTextView(context, attrs).themed(attrs)
    override fun createImageView(context: Context, attrs: AttributeSet) = super.createImageView(context, attrs).themed(attrs)
    override fun createButton(context: Context, attrs: AttributeSet) = super.createButton(context, attrs).themed(attrs)
    override fun createEditText(context: Context, attrs: AttributeSet) = super.createEditText(context, attrs).themed(attrs)
    override fun createSpinner(context: Context, attrs: AttributeSet) = super.createSpinner(context, attrs).themed(attrs)
    override fun createImageButton(context: Context, attrs: AttributeSet) = super.createImageButton(context, attrs).themed(attrs)
    override fun createCheckBox(context: Context, attrs: AttributeSet) = super.createCheckBox(context, attrs).themed(attrs)
    override fun createRadioButton(context: Context, attrs: AttributeSet) = super.createRadioButton(context, attrs).themed(attrs)
    override fun createCheckedTextView(context: Context, attrs: AttributeSet) = super.createCheckedTextView(context, attrs).themed(attrs)
    override fun createAutoCompleteTextView(context: Context, attrs: AttributeSet?) = super.createAutoCompleteTextView(context, attrs).themed(attrs)
    override fun createMultiAutoCompleteTextView(context: Context, attrs: AttributeSet) = super.createMultiAutoCompleteTextView(context, attrs).themed(attrs)
    override fun createRatingBar(context: Context, attrs: AttributeSet) = super.createRatingBar(context, attrs).themed(attrs)
    override fun createSeekBar(context: Context, attrs: AttributeSet) = super.createSeekBar(context, attrs).themed(attrs)
    override fun createToggleButton(context: Context, attrs: AttributeSet) = super.createToggleButton(context, attrs).themed(attrs)

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
        private val COLOR_ATTRIBUTES = intArrayOf(
            android.R.attr.background, android.R.attr.backgroundTint,
            android.R.attr.textColor, android.R.attr.textColorHint, android.R.attr.textColorLink,
            android.R.attr.tint, androidx.appcompat.R.attr.tint, androidx.appcompat.R.attr.backgroundTint,
            androidx.cardview.R.attr.cardBackgroundColor, com.google.android.material.R.attr.strokeColor
        )
    }
}
