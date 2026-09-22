package io.legado.app.ui.widget.recycler

import android.content.Context
import io.legado.app.utils.getCompatDrawable
import androidx.recyclerview.widget.DividerItemDecoration
import io.legado.app.R

class VerticalDivider(context: Context) : DividerItemDecoration(context, VERTICAL) {

    init {
        context.getCompatDrawable(R.drawable.ic_divider)?.let {
            this.setDrawable(it)
        }
    }

}