package io.legado.app.ui.book.explore

import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import io.legado.app.data.entities.rule.ExploreKind

/** Keep the source's category order and headings, disabling rows without a URL. */
internal class RandomRecommendCategoryAdapter(
    private val wrapped: ListAdapter,
    private val categories: List<ExploreKind>
) : ListAdapter by wrapped {
    override fun areAllItemsEnabled() = false

    override fun isEnabled(position: Int): Boolean {
        return !categories[position].url.isNullOrBlank()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return wrapped.getView(position, convertView, parent).apply {
            isEnabled = this@RandomRecommendCategoryAdapter.isEnabled(position)
            alpha = if (isEnabled) 1f else 0.5f
        }
    }
}
