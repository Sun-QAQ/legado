package io.legado.app.ui.book.agent

import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

/** 跟随回复高度逐帧滚动；用户向上阅读时立即停止，回到底部后恢复。 */
internal class AgentAutoScroll(private val recyclerView: RecyclerView) {
    private var following = true
    private var scheduled = false
    private var jumpPending = false
    private val scrollFrame = Runnable { scrollFrame() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { requestScroll() }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
            if (view.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                if (dy < 0) following = false
                else if (!view.canScrollVertically(1)) following = true
            }
        }

        override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) requestScroll()
        }
    }

    init {
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    fun followLatest() {
        following = true
        requestScroll()
    }

    fun requestScroll() {
        if (!following || scheduled || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        scheduled = true
        recyclerView.postOnAnimation(scrollFrame)
    }

    private fun scrollFrame() {
        scheduled = false
        if (!following || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        if (recyclerView.isComputingLayout) {
            requestScroll()
            return
        }
        val layoutManager = recyclerView.layoutManager ?: return
        val lastPosition = (recyclerView.adapter?.itemCount ?: 0) - 1
        if (lastPosition < 0) return
        val lastView = layoutManager.findViewByPosition(lastPosition)
        if (lastView == null) {
            jumpPending = true
            layoutManager.scrollToPosition(lastPosition)
            return
        }
        val bottom = layoutManager.getDecoratedBottom(lastView)
        val distance = bottom - (recyclerView.height - recyclerView.paddingBottom)
        if (abs(distance) <= 1) return
        val step = if (jumpPending) distance else ceil(abs(distance) * 0.35).toInt() * distance.sign
        jumpPending = false
        recyclerView.scrollBy(0, step)
        if (layoutManager.getDecoratedBottom(lastView) != bottom) requestScroll()
    }

    fun dispose() {
        recyclerView.removeCallbacks(scrollFrame)
        recyclerView.removeOnScrollListener(scrollListener)
        if (recyclerView.viewTreeObserver.isAlive) {
            recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
    }
}
