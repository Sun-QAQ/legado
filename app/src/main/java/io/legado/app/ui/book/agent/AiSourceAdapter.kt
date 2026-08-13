package io.legado.app.ui.book.agent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AiSource
import io.legado.app.databinding.ItemAiSourceBinding

class AiSourceAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AiSource, ItemAiSourceBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<AiSource>() {

        override fun areItemsTheSame(oldItem: AiSource, newItem: AiSource): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AiSource, newItem: AiSource): Boolean {
            return oldItem.name == newItem.name
                    && oldItem.model == newItem.model
                    && oldItem.enabled == newItem.enabled
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemAiSourceBinding {
        return ItemAiSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiSourceBinding,
        item: AiSource,
        payloads: MutableList<Any>
    ) {
        binding.tvName.text = item.name
        binding.tvModel.text = item.model.ifBlank {
            context.getString(R.string.ai_source_model)
        }
        binding.swtEnabled.isChecked = item.enabled
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAiSourceBinding) {
        binding.swtEnabled.setOnUserCheckedChangeListener { checked ->
            getItem(holder.layoutPosition)?.let {
                callBack.enable(checked, it)
            }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.edit(it)
            }
        }
        binding.ivMenuMore.setOnClickListener {
            showMenu(binding.ivMenuMore, holder.layoutPosition)
        }
    }

    private fun showMenu(view: View, position: Int) {
        val source = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.ai_source_item)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_fetch_models -> callBack.fetchModels(source)
                R.id.menu_del -> callBack.delete(source)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        fun enable(enabled: Boolean, aiSource: AiSource)
        fun edit(aiSource: AiSource)
        fun fetchModels(aiSource: AiSource)
        fun delete(aiSource: AiSource)
    }

}
