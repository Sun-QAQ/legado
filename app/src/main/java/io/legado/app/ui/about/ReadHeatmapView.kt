package io.legado.app.ui.about

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import io.legado.app.R
import io.legado.app.lib.theme.ThemePalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

/** Draws the calendar as one view; exposes each day to accessibility services. */
class ReadHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val step get() = gridStep(width)
    private val cellSize get() = (step - 4 * density).coerceAtLeast(density)
    private val labelWidth = 32 * density
    private val chartTop = 24 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = ThemePalette(context)
    // Very pale accent colors still need a darker endpoint on a light surface.
    private val endpoint = if (ColorUtils.calculateLuminance(palette.surface) > 0.5) {
        ColorUtils.blendARGB(palette.accent, Color.BLACK, 0.45f)
    } else {
        ColorUtils.blendARGB(palette.accent, Color.WHITE, 0.2f)
    }
    private val colors = IntArray(5) { level ->
        if (level == 0) ColorUtils.blendARGB(palette.surface, palette.text, 0.06f) else
            ColorUtils.blendARGB(palette.surface, endpoint, level / 4f)
    }
    private var heatmap = ReadHeatmap(LocalDate.now(), emptyList())
    private var selectedDate: LocalDate = heatmap.days.last().date
    internal var onDaySelected: ((ReadHeatmap.Day) -> Unit)? = null
    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = dayAt(x, y) ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(heatmap.days.indices)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val day = heatmap.days[id]
            node.contentDescription = describeDay(day)
            node.setBoundsInParent(cellBounds(day))
            node.isSelected = day.date == selectedDate
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            selectDay(id)
            return true
        }
    }

    init {
        isFocusable = true
        isClickable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    internal fun setData(data: ReadHeatmap) {
        heatmap = data
        selectedDate = data.days.last().date
        accessibility.invalidateRoot()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (labelWidth + heatmap.columnCount * 20 * density).toInt()
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(
            measuredWidth,
            resolveSize((chartTop + 7 * gridStep(measuredWidth) + 32 * density).toInt(), heightMeasureSpec),
        )
    }

    private fun gridStep(viewWidth: Int): Float =
        minOf(22 * density, (viewWidth - labelWidth).coerceAtLeast(0f) / heatmap.columnCount)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.textSize = 10 * resources.displayMetrics.scaledDensity
        paint.color = palette.secondaryText
        val locale = Locale.getDefault()
        for (row in listOf(0, 2, 4)) {
            val weekday = java.time.DayOfWeek.of(row + 1).getDisplayName(TextStyle.SHORT, locale)
            canvas.drawText(weekday, 0f, chartTop + row * step + cellSize, paint)
        }
        // Skip month labels that would overlap at the first partial month.
        var lastLabelRight = -1f
        heatmap.days.filter { it.date == heatmap.start || it.date.dayOfMonth == 1 }
            .forEach { day ->
                val label = day.date.month.getDisplayName(TextStyle.SHORT, locale)
                val x = cellBounds(day).left.toFloat()
                val labelWidth = paint.measureText(label)
                if (x >= lastLabelRight + 6 * density && x + labelWidth <= width) {
                    canvas.drawText(label, x, 14 * density, paint)
                    lastLabelRight = x + labelWidth
                }
            }
        heatmap.days.forEach { day ->
            val bounds = cellBounds(day)
            paint.color = colors[day.level]
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                bounds.bottom.toFloat(), 2 * density, 2 * density, paint,
            )
            if (day.date == selectedDate) {
                paint.color = palette.text
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                canvas.drawRoundRect(
                    bounds.left - density, bounds.top - density, bounds.right + density,
                    bounds.bottom + density, 3 * density, 3 * density, paint,
                )
                paint.style = Paint.Style.FILL
            }
        }
        val legendY = chartTop + 7 * step + 10 * density
        val legendStep = 16 * density
        val legendSize = 12 * density
        val more = context.getString(R.string.read_heatmap_more)
        val less = context.getString(R.string.read_heatmap_less)
        val legendX = max(labelWidth, width - paint.measureText(more) - 6 * legendStep)
        paint.color = palette.secondaryText
        canvas.drawText(less, legendX - paint.measureText(less) - 8 * density, legendY + legendSize, paint)
        colors.forEachIndexed { level, color ->
            paint.color = color
            val x = legendX + level * legendStep
            canvas.drawRoundRect(x, legendY, x + legendSize, legendY + legendSize,
                2 * density, 2 * density, paint)
        }
        paint.color = palette.secondaryText
        canvas.drawText(more, legendX + 5 * legendStep, legendY + legendSize, paint)
    }

    private fun cellBounds(day: ReadHeatmap.Day): Rect {
        val index = heatmap.cellIndex(day)
        val gridLeft = labelWidth + (width - labelWidth - heatmap.columnCount * step) / 2
        val left = (gridLeft + index / 7 * step + (step - cellSize) / 2).toInt()
        val top = (chartTop + index % 7 * step).toInt()
        return Rect(left, top, (left + cellSize).toInt(), (top + cellSize).toInt())
    }

    private fun dayAt(x: Float, y: Float): Int? = heatmap.days.indices.firstOrNull {
        cellBounds(heatmap.days[it]).contains(x.toInt(), y.toInt())
    }

    private fun selectDay(index: Int) {
        val day = heatmap.days[index]
        selectedDate = day.date
        onDaySelected?.invoke(day)
        accessibility.invalidateRoot()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            dayAt(event.x, event.y)?.let { selectDay(it) }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    internal fun describeDay(day: ReadHeatmap.Day): String {
        val date = day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        return when {
            day.readTime <= 0 -> context.getString(R.string.read_heatmap_day_empty, date)
            day.readTime < 60_000 -> context.getString(R.string.read_heatmap_day_short, date)
            else -> context.getString(
                R.string.read_heatmap_day, date,
                day.readTime / 3_600_000, day.readTime % 3_600_000 / 60_000,
            )
        }
    }
}
