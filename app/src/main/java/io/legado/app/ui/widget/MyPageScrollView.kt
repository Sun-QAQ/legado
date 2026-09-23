package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import io.legado.app.BuildConfig
import kotlin.math.abs

/**
 * "我的"页滚动容器。
 *
 * 页内菜单项都是可点击的 View，真机上手指出现在菜单项上时页面滑不动、落在非点击区域
 * （如个人信息卡片）却能滑动，说明手势被子 View 抢走后容器拿不到拦截机会。
 * 这里强制纵向拖拽由容器自己接管：
 * 1、忽略子 View 的 requestDisallowInterceptTouchEvent(true)，容器不会被"禁止拦截"；
 * 2、纵向位移一超过 touchSlop 就立刻拦截，不再依赖子 View 的配合。
 *
 * 轻触（未超过 touchSlop）仍照常交给子 View，点击、长按反馈不受影响。
 */
class MyPageScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                debugLog("DOWN x=${ev.x.toInt()} y=${ev.y.toInt()}")
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                //多指时用新落下的手指重新做基准,避免误判
                val index = ev.actionIndex
                downX = ev.getX(index)
                downY = ev.getY(index)
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && ev.pointerCount == 1) {
                    val dy = abs(ev.y - downY)
                    val dx = abs(ev.x - downX)
                    if (dy > touchSlop && dy > dx) {
                        dragging = true
                        debugLog("start dragging dy=${dy.toInt()} dx=${dx.toInt()} slop=$touchSlop")
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                debugLog("${ev.actionMasked} scrollY=$scrollY dragging=$dragging")
            }
        }
        return dragging || super.onInterceptTouchEvent(ev)
    }

    /**
     * 忽略子 View"不要拦截"的请求,纵向滚动始终由本容器接管。
     * 本页没有需要自己处理拖拽的子 View,忽略是安全的。
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) {
            debugLog("child asked disallow intercept, caller=${callerInfo()}")
        }
        super.requestDisallowInterceptTouchEvent(false)
    }

    private fun callerInfo(): String =
        Throwable().stackTrace.drop(3).take(4).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}"
        }

    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    companion object {
        private const val TAG = "MyPageScrollView"
    }
}
