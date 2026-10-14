package io.legado.app.ui.book.agent

import androidx.recyclerview.widget.DiffUtil

internal enum class AgentMessagePayload { TEXT, STEPS }

/** 对话仅在尾部追加/替换回复；保持既有消息的 ViewHolder 和阅读位置。 */
internal class AgentMessageDiff(
    private val oldItems: List<AgentMessage>,
    private val newItems: List<AgentMessage>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldItems.size
    override fun getNewListSize() = newItems.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        oldItemPosition == newItemPosition &&
            oldItems[oldItemPosition].isUser == newItems[newItemPosition].isUser

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        oldItems[oldItemPosition] == newItems[newItemPosition]

    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
        val old = oldItems[oldItemPosition]
        val new = newItems[newItemPosition]
        return when {
            old.isUser -> null
            old.copy(text = new.text, streaming = new.streaming) == new -> AgentMessagePayload.TEXT
            old.copy(steps = new.steps) == new -> AgentMessagePayload.STEPS
            else -> null
        }
    }
}
