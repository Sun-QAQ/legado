package io.legado.app.ui.book.agent

import androidx.recyclerview.widget.DiffUtil

internal enum class AgentMessagePayload { TEXT, STEPS, TEXT_AND_STEPS }

/** 用稳定 ID 跟踪编辑、删除和流式更新，兼容没有 ID 的旧调用。 */
internal class AgentMessageDiff(
    private val oldItems: List<AgentMessage>,
    private val newItems: List<AgentMessage>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldItems.size
    override fun getNewListSize() = newItems.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val old = oldItems[oldItemPosition]
        val new = newItems[newItemPosition]
        return old.isUser == new.isUser && if (old.id != null && new.id != null) {
            old.id == new.id
        } else oldItemPosition == newItemPosition
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        oldItems[oldItemPosition] == newItems[newItemPosition]

    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
        val old = oldItems[oldItemPosition]
        val new = newItems[newItemPosition]
        return when {
            old.isUser -> null
            old.copy(text = new.text, streaming = new.streaming) == new -> AgentMessagePayload.TEXT
            old.copy(steps = new.steps) == new -> AgentMessagePayload.STEPS
            old.copy(text = new.text, streaming = new.streaming, steps = new.steps) == new ->
                AgentMessagePayload.TEXT_AND_STEPS
            else -> null
        }
    }
}
