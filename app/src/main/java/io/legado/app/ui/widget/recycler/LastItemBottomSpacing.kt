package io.legado.app.ui.widget.recycler

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** 在最后一项之后提供可滚动的底部缓冲空间。 */
class LastItemBottomSpacing(
    private val height: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (state.itemCount > 0 && parent.getChildAdapterPosition(view) == state.itemCount - 1) {
            outRect.bottom = height
        }
    }
}
