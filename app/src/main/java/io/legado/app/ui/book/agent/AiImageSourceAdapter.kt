package io.legado.app.ui.book.agent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AiImageSource
import io.legado.app.databinding.ItemAiSourceBinding

class AiImageSourceAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AiImageSource, ItemAiSourceBinding>(context) {

    var currentId: Long = 0L

    val diffItemCallback = object : DiffUtil.ItemCallback<AiImageSource>() {
        override fun areItemsTheSame(oldItem: AiImageSource, newItem: AiImageSource) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AiImageSource, newItem: AiImageSource) = oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup) = ItemAiSourceBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiSourceBinding,
        item: AiImageSource,
        payloads: MutableList<Any>
    ) {
        val prefix = if (item.id == currentId) "[${context.getString(R.string.ai_image_source_default)}] " else ""
        binding.tvName.text = prefix + item.name
        binding.tvModel.text = listOf(item.model, item.imageSize).filter { it.isNotBlank() }.joinToString(" · ")
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
            inflate(R.menu.ai_image_source_item)
            menu.findItem(R.id.menu_set_default).isVisible = source.enabled && source.id != currentId
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_set_default -> callBack.setDefault(source)
                    R.id.menu_del -> callBack.delete(source)
                }
                true
            }
            show()
        }
    }

    interface CallBack {
        fun enable(enabled: Boolean, source: AiImageSource)
        fun edit(source: AiImageSource)
        fun setDefault(source: AiImageSource)
        fun delete(source: AiImageSource)
    }
}
