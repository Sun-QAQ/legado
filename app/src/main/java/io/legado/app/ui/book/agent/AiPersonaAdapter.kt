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
import io.legado.app.data.entities.AiPersona
import io.legado.app.databinding.ItemAiPersonaBinding

class AiPersonaAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AiPersona, ItemAiPersonaBinding>(context) {

    var currentId: Long = 0L
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    val diffItemCallback = object : DiffUtil.ItemCallback<AiPersona>() {

        override fun areItemsTheSame(oldItem: AiPersona, newItem: AiPersona): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AiPersona, newItem: AiPersona): Boolean {
            return oldItem.name == newItem.name
                    && oldItem.prompt == newItem.prompt
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemAiPersonaBinding {
        return ItemAiPersonaBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiPersonaBinding,
        item: AiPersona,
        payloads: MutableList<Any>
    ) {
        val prefix = if (item.id == currentId) {
            "[${context.getString(R.string.ai_source_current)}] "
        } else {
            ""
        }
        binding.tvName.text = prefix + item.name
        binding.tvPrompt.text = item.prompt.ifBlank {
            context.getString(R.string.ai_persona_prompt)
        }
        binding.ivEdit.isVisible = item.id != DEFAULT_PERSONA_ID
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAiPersonaBinding) {
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
        val persona = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.ai_persona_item)
        popupMenu.menu.findItem(R.id.menu_del).isVisible = persona.id != DEFAULT_PERSONA_ID
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_select_persona_use -> callBack.select(persona)
                R.id.menu_del -> callBack.delete(persona)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        fun edit(aiPersona: AiPersona)
        fun select(aiPersona: AiPersona)
        fun delete(aiPersona: AiPersona)
    }

    companion object {
        const val DEFAULT_PERSONA_ID = 0L
    }

}
