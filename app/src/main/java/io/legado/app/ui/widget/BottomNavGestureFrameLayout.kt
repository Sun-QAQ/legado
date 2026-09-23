package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import io.legado.app.BuildConfig
import kotlin.math.abs

/**
 * 底部悬浮导航栏容器。
 *
 * 导航栏浮在页面之上，默认会吞掉落在其区域内的所有触摸，使页面底部出现一片滑不动的死区
 * （如"我的"页下半部分刚好被导航栏盖住时）。这里点击仍交给导航栏，把滑动（纵向滚动页面或
 * 横向切换 ViewPager 页签）透传给 [gestureTarget]（主界面的 ViewPager），让被盖住的页面
 * 内容照常滚动/切换。
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
                debugLog("DOWN x=${ev.x.toInt()} y=${ev.y.toInt()}")
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                //多指手势交回导航栏自己处理
                cancelPassing(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!passing && ev.pointerCount == 1) {
                    val dy = abs(ev.y - downY)
                    val dx = abs(ev.x - downX)
                    debugLog("MOVE dy=${dy.toInt()} dx=${dx.toInt()} slop=$touchSlop")
                    if ((dy > touchSlop && dy > dx) || (dx > touchSlop && dx > dy)) {
                        passing = true
                        debugLog("start passing ${if (dy > dx) "vertical" else "horizontal"}")
                        //此时手指已经滑过一段，先补一个 DOWN 让目标开始接管
                        forward(ev, MotionEvent.ACTION_DOWN)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                debugLog("${ev.actionMasked} passing=$passing")
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
        debugLog("forward action=$action x=${event.x.toInt()} y=${event.y.toInt()}")
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    companion object {
        private const val TAG = "BottomNavGesture"
    }
}
