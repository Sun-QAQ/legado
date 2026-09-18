package io.legado.app.ui.book.agent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.AiConversation
import io.legado.app.databinding.ItemAiConversationBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor

class AgentConversationAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AiConversation, ItemAiConversationBinding>(context) {

    var currentConversationId: String? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemAiConversationBinding {
        return ItemAiConversationBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiConversationBinding,
        item: AiConversation,
        payloads: MutableList<Any>
    ) {
        val isCurrent = item.id == currentConversationId
        binding.tvTitle.text = item.title
        binding.tvTitle.setTextColor(if (isCurrent) context.accentColor else context.primaryTextColor)
        binding.tvCurrent.setTextColor(context.accentColor)
        binding.tvCurrent.visibility = if (isCurrent) View.VISIBLE else View.GONE
        binding.tvTime.text = AppConst.dateFormat.format(item.updatedAt)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAiConversationBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callBack::open)
        }
        binding.ivDelete.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callBack::delete)
        }
    }

    interface CallBack {
        fun open(conversation: AiConversation)
        fun delete(conversation: AiConversation)
    }
}
