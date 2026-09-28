package io.legado.app.ui.book.info

import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemEpisodeBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor

class EpisodeAdapter(
    context: Context,
    private val durChapterIndex: () -> Int,
    private val onEpisodeClick: (BookChapter) -> Unit,
) : RecyclerAdapter<BookChapter, ItemEpisodeBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemEpisodeBinding {
        return ItemEpisodeBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemEpisodeBinding,
        item: BookChapter,
        payloads: MutableList<Any>,
    ) = binding.run {
        tvEpisode.text = item.title
        tvEpisode.setTextColor(
            if (item.index == durChapterIndex()) context.accentColor
            else context.getCompatColor(R.color.primaryText)
        )
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemEpisodeBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let(onEpisodeClick)
        }
    }
}
