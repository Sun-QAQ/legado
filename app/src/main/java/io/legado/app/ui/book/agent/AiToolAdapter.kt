package io.legado.app.ui.book.agent

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemAiToolBinding

class AiToolAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<AgentTools.Info, ItemAiToolBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<AgentTools.Info>() {
        override fun areItemsTheSame(oldItem: AgentTools.Info, newItem: AgentTools.Info) =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: AgentTools.Info, newItem: AgentTools.Info) =
            oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup) =
        ItemAiToolBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAiToolBinding,
        item: AgentTools.Info,
        payloads: MutableList<Any>
    ) {
        binding.tvName.text = item.name
        binding.tvDescription.text = item.description
        binding.swtEnabled.isChecked = item.enabled
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAiToolBinding) {
        binding.swtEnabled.setOnUserCheckedChangeListener { enabled ->
            getItem(holder.layoutPosition)?.let { callBack.setEnabled(it.name, enabled) }
        }
    }

    fun interface CallBack {
        fun setEnabled(name: String, enabled: Boolean)
    }
}
