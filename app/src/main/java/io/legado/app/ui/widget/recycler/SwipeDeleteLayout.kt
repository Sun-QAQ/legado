package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import io.legado.app.R
import io.legado.app.utils.dpToPx
import kotlin.math.abs

/**
 * 支持左滑露出删除按钮的列表项容器。
 *
 * 删除动作必须由用户点击露出的按钮触发，不会因滑动直接删除。
 */
class SwipeDeleteLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    var onDeleteClick: (() -> Unit)? = null

    private val revealWidth = 76.dpToPx()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.error)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(android.R.color.white)
        textSize = 14.dpToPx().toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private var offsetX = 0f
    private var downX = 0f
    private var downY = 0f
    private var deleteAreaTouched = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                deleteAreaTouched = isOpen && event.x >= width - revealWidth
                if (isOpen && !deleteAreaTouched) {
                    close()
                    return true
                }
                return deleteAreaTouched
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                setOffset(event.x - downX)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (deleteAreaTouched && event.x >= width - revealWidth) {
                    close()
                    onDeleteClick?.invoke()
                } else if (offsetX <= -revealWidth / 2f) {
                    open()
                } else {
                    close()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (offsetX <= -revealWidth / 2f) open() else close()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (offsetX == 0f) return
        val left = width + offsetX
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), deletePaint)
        val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(
            context.getString(R.string.delete),
            width - revealWidth / 2f,
            baseline,
            textPaint
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(offsetX, 0f)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    fun close() = setOffset(0f)

    private fun open() = setOffset(-revealWidth.toFloat())

    private fun setOffset(value: Float) {
        offsetX = value.coerceIn(-revealWidth.toFloat(), 0f)
        invalidate()
    }

    private val isOpen: Boolean
        get() = offsetX < 0f
}
