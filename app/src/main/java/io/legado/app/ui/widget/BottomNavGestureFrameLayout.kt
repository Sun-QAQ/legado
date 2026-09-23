package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 底部悬浮导航栏容器。
 *
 * 导航栏浮在页面之上，默认会吞掉落在其区域内的所有触摸，使页面底部出现一片滑不动的死区
 * （如"我的"页下半部分刚好被导航栏盖住时）。这里点击仍交给导航栏，只把垂直滑动透传给
 * [gestureTarget]（主界面的 ViewPager），让被盖住的页面内容照常滚动。
 */
class BottomNavGestureFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 滑动透传的目标，为空时保持默认行为
     */
    var gestureTarget: View? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var passing = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (gestureTarget == null) {
            return super.onInterceptTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                passing = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                //多指手势交回导航栏自己处理
                cancelPassing(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!passing && ev.pointerCount == 1) {
                    val dy = abs(ev.y - downY)
                    val dx = abs(ev.x - downX)
                    if (dy > touchSlop && dy > dx) {
                        passing = true
                        //此时手指已经滑过一段，先补一个 DOWN 让目标开始接管
                        forward(ev, MotionEvent.ACTION_DOWN)
                    }
                }
            }
        }
        return passing || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (passing && gestureTarget != null) {
            forward(ev, ev.actionMasked)
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun cancelPassing(ev: MotionEvent) {
        if (passing) {
            forward(ev, MotionEvent.ACTION_CANCEL)
            passing = false
        }
    }

    private fun forward(ev: MotionEvent, action: Int) {
        val target = gestureTarget ?: return
        val event = MotionEvent.obtain(ev)
        event.action = action
        event.offsetLocation(
            left + translationX - target.left - target.translationX,
            top + translationY - target.top - target.translationY
        )
        target.dispatchTouchEvent(event)
        event.recycle()
    }
}
