@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import androidx.core.graphics.drawable.DrawableCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Html
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EdgeEffect
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.record
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.TintHelper
import io.legado.app.utils.canvasrecorder.CanvasRecorder
import io.legado.app.utils.canvasrecorder.record
import splitties.systemservices.inputMethodManager
import splitties.views.bottomPadding
import splitties.views.topPadding
import java.lang.reflect.Field
import kotlin.math.roundToInt


private tailrec fun getCompatActivity(context: Context?): AppCompatActivity? {
    return when (context) {
        is AppCompatActivity -> context
        is androidx.appcompat.view.ContextThemeWrapper -> getCompatActivity(context.baseContext)
        is android.view.ContextThemeWrapper -> getCompatActivity(context.baseContext)
        else -> null
    }
}

val View.activity: AppCompatActivity?
    get() = getCompatActivity(context)

fun View.hideSoftInput() = run {
    inputMethodManager.hideSoftInputFromWindow(this.windowToken, 0)
}

fun EditText.showSoftInput() = run {
    requestFocus()
    inputMethodManager.showSoftInput(this, InputMethodManager.RESULT_SHOWN)
}

fun View.disableAutoFill() = run {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}

fun View.applyTint(
    @ColorInt color: Int,
    isDark: Boolean = AppConfig.isNightTheme
) {
    TintHelper.setTintAuto(this, color, false, isDark)
}

/**
 * 将视图当前渲染内容绘制到位图（含 onDraw 自定义绘制），用于把默认封面等视图内容转成图片预览。
 */
fun View.drawToBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    draw(canvas)
    return bitmap
}

fun View.applyBackgroundTint(
    @ColorInt color: Int,
    isDark: Boolean = AppConfig.isNightTheme
) {
    if (background == null) {
        setBackgroundColor(color)
    } else {
        TintHelper.setTintAuto(this, color, true, isDark)
    }
}

/**
 * 输入框闪烁光标统一使用主题强调色
 */
@SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
fun EditText.setAccentCursor() {
    val accent = ThemeStore.accentColor(context)
    val widthPx = (1.5f * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val cursor = UntintableCursorDrawable(accent, widthPx)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setTextCursorDrawable(cursor)
    } else {
        try {
            val fEditor = TextView::class.java.getDeclaredField("mEditor")
            fEditor.isAccessible = true
            val editor = fEditor.get(this)
            val fCursorDrawable = editor.javaClass.getDeclaredField("mCursorDrawable")
            fCursorDrawable.isAccessible = true
            fCursorDrawable.set(editor, arrayOf<Drawable>(cursor, cursor))
        } catch (ignored: Exception) {
        }
    }
    tintCursorHandles(accent)
    // 手柄在聚焦时会被框架用 colorControlActivated 重新着色, 在获得焦点时重刷
    val existing = onFocusChangeListener
    setOnFocusChangeListener { v, hasFocus ->
        existing?.onFocusChange(v, hasFocus)
        if (hasFocus) {
            tintCursorHandles(accent)
            v.post { tintCursorHandles(accent) }
        }
    }
}

/**
 * 光标拖动/选择手柄统一使用主题强调色
 */
private fun EditText.tintCursorHandles(@ColorInt accent: Int) {
    try {
        val fEditor = TextView::class.java.getDeclaredField("mEditor")
        fEditor.isAccessible = true
        val editor = fEditor.get(this)
        val names = arrayOf("mTextSelectHandleLeft", "mTextSelectHandleRight", "mTextSelectHandle")
        for (name in names) {
            val f = editor.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val d = f.get(editor) as? Drawable ?: continue
            tintDrawableRecursively(d, accent)
        }
    } catch (ignored: Exception) {
    }
}

private fun tintDrawableRecursively(drawable: Drawable, @ColorInt accent: Int) {
    DrawableCompat.setTintList(drawable.mutate(), ColorStateList.valueOf(accent))
    when (drawable) {
        is StateListDrawable -> {
            for (i in 0 until drawable.stateCount) {
                drawable.getStateDrawable(i)?.let { tintDrawableRecursively(it, accent) }
            }
        }

        is LayerDrawable -> {
            for (i in 0 until drawable.numberOfLayers) {
                drawable.getDrawable(i)?.let { tintDrawableRecursively(it, accent) }
            }
        }
    }
}

/**
 * 抗着色的光标 Drawable：阻止 TextInputLayout/系统按默认强调色或文字色重新着色
 */
private class UntintableCursorDrawable(color: Int, widthPx: Int) : GradientDrawable() {
    init {
        setColor(color)
        setSize(widthPx, widthPx)
    }

    override fun setTint(tintColor: Int) = Unit
    override fun setTintList(tint: ColorStateList?) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun setColorFilter(color: Int, mode: PorterDuff.Mode) = Unit
}

fun RecyclerView.setEdgeEffectColor(@ColorInt color: Int) {
    edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
        override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
            val edgeEffect = super.createEdgeEffect(view, direction)
            edgeEffect.color = color
            return edgeEffect
        }
    }
}

fun ViewPager.setEdgeEffectColor(@ColorInt color: Int) {
    try {
        val clazz = ViewPager::class.java
        for (name in arrayOf("mLeftEdge", "mRightEdge")) {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            val edge = field.get(this)
            (edge as EdgeEffect).color = color
        }
    } catch (ignored: Exception) {
    }
}

fun EditText.disableEdit() {
    keyListener = null
}

fun View.gone() {
    if (visibility != GONE) {
        visibility = GONE
    }
}

fun View.gone(gone: Boolean) {
    if (gone) {
        gone()
    } else {
        visibility = VISIBLE
    }
}

fun View.invisible() {
    if (visibility != INVISIBLE) {
        visibility = INVISIBLE
    }
}

fun View.visible() {
    if (visibility != VISIBLE) {
        visibility = VISIBLE
    }
}

fun View.visible(visible: Boolean) {
    if (visible && visibility != VISIBLE) {
        visibility = VISIBLE
    } else if (!visible && visibility == VISIBLE) {
        visibility = INVISIBLE
    }
}

fun View.screenshot(bitmap: Bitmap? = null, canvas: Canvas? = null): Bitmap? {
    return if (width > 0 && height > 0) {
        val screenshot = if (bitmap != null && bitmap.width == width && bitmap.height == height) {
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap
        } else {
            bitmap?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val c = canvas ?: Canvas()
        c.setBitmap(screenshot)
        c.save()
        c.translate(-scrollX.toFloat(), -scrollY.toFloat())
        this.draw(c)
        c.restore()
        c.setBitmap(null)
        screenshot.prepareToDraw()
        screenshot
    } else {
        null
    }
}

fun View.screenshot(picture: Picture) {
    if (width > 0 && height > 0) {
        picture.record(width, height) {
            withTranslation(-scrollX.toFloat(), -scrollY.toFloat()) {
                draw(this)
            }
        }
    }
}

fun View.screenshot(canvasRecorder: CanvasRecorder) {
    if (width > 0 && height > 0) {
        canvasRecorder.record(width, height) {
            draw(this)
        }
    }
}

fun View.setPaddingBottom(bottom: Int) {
    setPadding(paddingLeft, paddingTop, paddingRight, bottom)
}

fun SeekBar.progressAdd(int: Int) {
    progress += int
}

fun RadioGroup.getIndexById(id: Int): Int {
    for (i in 0 until this.childCount) {
        if (id == get(i).id) {
            return i
        }
    }
    return 0
}

fun RadioGroup.getCheckedIndex(): Int {
    for (i in 0 until this.childCount) {
        if (checkedRadioButtonId == get(i).id) {
            return i
        }
    }
    return 0
}

fun RadioGroup.checkByIndex(index: Int) {
    check(get(index).id)
}

@SuppressLint("ObsoleteSdkInt")
fun TextView.setHtml(html: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    } else {
        @Suppress("DEPRECATION")
        text = Html.fromHtml(html)
    }
}

fun TextView.setTextIfNotEqual(charSequence: CharSequence?) {
    if (text != charSequence) {
        text = charSequence
    }
}

@SuppressLint("RestrictedApi")
fun PopupMenu.show(x: Int, y: Int) {
    kotlin.runCatching {
        val field: Field = this.javaClass.getDeclaredField("mPopup")
        field.isAccessible = true
        (field.get(this) as MenuPopupHelper).show(x, y)
    }.onFailure {
        it.printOnDebug()
    }
}

fun View.shouldHideSoftInput(event: MotionEvent): Boolean {
    if (this is EditText) {
        val l = intArrayOf(0, 0)
        getLocationInWindow(l)
        val left = l[0]
        val top = l[1]
        val bottom = top + getHeight()
        val right = left + getWidth()
        return !(event.x > left && event.x < right && event.y > top && event.y < bottom)
    }
    return false
}

fun View.applyStatusBarPadding(withInitialPadding: Boolean = false) {
    val initialPadding = if (withInitialPadding) topPadding else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
        topPadding = initialPadding + insets.top
        windowInsets
    }
}

fun View.applyNavigationBarPadding(withInitialPadding: Boolean = false) {
    val initialPadding = if (withInitialPadding) bottomPadding else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        bottomPadding = initialPadding + windowInsets.navigationBarHeight
        windowInsets
    }
}

fun View.applyNavigationBarMargin(withInitialMargin: Boolean = false) {
    val initialMargin = if (withInitialMargin) marginBottom else 0
    setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = initialMargin + windowInsets.navigationBarHeight
        }
        windowInsets
    }
}

fun View.setBackgroundKeepPadding(@DrawableRes backgroundResId: Int) {
    val paddingLeft = paddingLeft
    val paddingTop = paddingTop
    val paddingRight = paddingRight
    val paddingBottom = paddingBottom
    setBackgroundResource(backgroundResId)
    setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
}

fun View.canScroll(direction: Int): Boolean {
    return canScrollVertically(direction) || canScrollHorizontally(direction)
}

private val requestLayoutBroken = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M
        || Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.Q

fun View.setOnApplyWindowInsetsListenerCompat(listener: (View, WindowInsetsCompat) -> WindowInsetsCompat) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val windowInsets = listener(view, insets)
        if (requestLayoutBroken && isLayoutRequested) {
            post {
                requestLayout()
            }
        }
        windowInsets
    }
}

