package io.legado.app.ui.book.agent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AiSearchSource
import io.legado.app.databinding.ItemAiSourceBinding

class AiSearchSourceAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AiSearchSource, ItemAiSourceBinding>(context) {

    var currentId: Long = 0L
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    val diffItemCallback = object : DiffUtil.ItemCallback<AiSearchSource>() {
        override fun areItemsTheSame(oldItem: AiSearchSource, newItem: AiSearchSource) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AiSearchSource, newItem: AiSearchSource) =
            oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup) =
        ItemAiSourceBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiSourceBinding,
        item: AiSearchSource,
        payloads: MutableList<Any>
    ) {
        val prefix = if (item.id == currentId) {
            "[${context.getString(R.string.ai_search_source_default)}] "
        } else {
            ""
        }
        binding.tvName.text = prefix + item.name
        binding.tvModel.text = "${typeLabel(item.type)} · ${item.defaultCount}"
        binding.swtEnabled.isChecked = item.enabled
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAiSourceBinding) {
        binding.swtEnabled.setOnUserCheckedChangeListener { checked ->
            getItem(holder.layoutPosition)?.let { callBack.enable(checked, it) }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callBack::edit)
        }
        binding.ivMenuMore.setOnClickListener { showMenu(it, holder.layoutPosition) }
    }

    private fun showMenu(view: View, position: Int) {
        val source = getItem(position) ?: return
        PopupMenu(context, view).apply {
            inflate(R.menu.ai_search_source_item)
            menu.findItem(R.id.menu_set_default).isVisible = source.enabled && source.id != currentId
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_set_default -> callBack.setDefault(source)
                    R.id.menu_test_connection -> callBack.test(source)
                    R.id.menu_del -> callBack.delete(source)
                }
                true
            }
            show()
        }
    }

    private fun typeLabel(type: String): String = when (type) {
        AiSearchSource.TYPE_TAVILY -> "Tavily"
        AiSearchSource.TYPE_BRAVE -> "Brave"
        AiSearchSource.TYPE_SEARXNG -> "SearXNG"
        else -> context.getString(R.string.ai_search_type_custom)
    }

    interface CallBack {
        fun enable(enabled: Boolean, source: AiSearchSource)
        fun edit(source: AiSearchSource)
        fun setDefault(source: AiSearchSource)
        fun test(source: AiSearchSource)
        fun delete(source: AiSearchSource)
    }
}
