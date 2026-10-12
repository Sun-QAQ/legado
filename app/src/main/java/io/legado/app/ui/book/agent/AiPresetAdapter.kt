package io.legado.app.ui.book.agent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AiPreset
import io.legado.app.databinding.ItemAiPresetBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor

class AiPresetAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AiPreset, ItemAiPresetBinding>(context) {

    var currentId: Long = 0L
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    val diffItemCallback = object : DiffUtil.ItemCallback<AiPreset>() {

        override fun areItemsTheSame(oldItem: AiPreset, newItem: AiPreset): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AiPreset, newItem: AiPreset): Boolean {
            return oldItem.name == newItem.name
                    && oldItem.prompt == newItem.prompt
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemAiPresetBinding {
        return ItemAiPresetBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiPresetBinding,
        item: AiPreset,
        payloads: MutableList<Any>
    ) {
        val prefix = if (item.id == currentId) {
            "[${context.getString(R.string.ai_source_current)}] "
        } else {
            ""
        }
        binding.tvName.text = prefix + item.name
        binding.tvName.setTextColor(if (item.id == currentId) context.accentColor else context.primaryTextColor)
        binding.tvPrompt.setTextColor(context.secondaryTextColor)
        binding.ivEdit.setColorFilter(context.primaryTextColor)
        binding.ivMenuMore.setColorFilter(context.primaryTextColor)
        binding.tvPrompt.text = item.prompt.ifBlank {
            context.getString(R.string.ai_preset_prompt)
        }
        binding.ivEdit.isVisible = item.id != NO_PRESET_ID
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAiPresetBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callBack::select)
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
        val preset = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.ai_preset_item)
        popupMenu.menu.findItem(R.id.menu_del).isVisible = preset.id != NO_PRESET_ID
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_select_preset_use -> callBack.select(preset)
                R.id.menu_del -> callBack.delete(preset)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        fun edit(aiPreset: AiPreset)
        fun select(aiPreset: AiPreset)
        fun delete(aiPreset: AiPreset)
    }

    companion object {
        const val NO_PRESET_ID = 0L
    }

}
