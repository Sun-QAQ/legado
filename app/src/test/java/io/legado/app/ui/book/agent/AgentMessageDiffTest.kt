package io.legado.app.ui.book.agent

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentMessageDiffTest {
    @Test
    fun `追加回复不会重新绑定已有历史消息`() {
        val history = listOf(AgentMessage(true, "问题"), AgentMessage(text = "历史回复"))
        val events = events(history, history + AgentMessage(streaming = true))
        assertEquals(listOf("insert:2:1"), events)
    }

    @Test
    fun `流式文字与最终全文只刷新文本区域`() {
        val reply = AgentMessage(text = "部分", streaming = true)
        val history = listOf(AgentMessage(true, "问题"), reply)
        assertEquals(listOf("change:1:1:TEXT"), events(history, history.dropLast(1) + reply.copy(text = "部分文字")))
        assertEquals(listOf("change:1:1:TEXT"), events(history, history.dropLast(1) + reply.copy(text = "最终全文", streaming = false)))
    }

    @Test
    fun `步骤更新不触发文字重新解析`() {
        val reply = AgentMessage(text = "回复", steps = listOf(AgentStep("1", "请求")), streaming = true)
        val updated = reply.copy(steps = listOf(reply.steps.single().copy(state = AgentStepState.DONE)))
        assertEquals(listOf("change:0:1:STEPS"), events(listOf(reply), listOf(updated)))
    }

    @Test
    fun `流式正文与步数同时变化时合并局部刷新`() {
        val reply = AgentMessage(text = "部分回复", streaming = true)
        val updated = reply.copy(text = "部分回复继续", steps = listOf(AgentStep("1", "调用工具")))
        assertEquals(listOf("change:0:1:TEXT_AND_STEPS"), events(listOf(reply), listOf(updated)))
        assertEquals(
            listOf("change:0:1:TEXT_AND_STEPS"),
            events(listOf(updated), listOf(updated.copy(
                streaming = false,
                steps = updated.steps.map { it.copy(state = AgentStepState.DONE) }
            )))
        )
    }

    @Test
    fun `内容相同不刷新并且结果结构变化需要完整绑定`() {
        val reply = AgentMessage(text = "回复", streaming = true)
        assertEquals(emptyList<String>(), events(listOf(reply), listOf(reply)))
        val updated = reply.copy(canLoadMore = true)
        assertNull(AgentMessageDiff(listOf(reply), listOf(updated)).getChangePayload(0, 0))
    }

    private fun events(old: List<AgentMessage>, new: List<AgentMessage>): List<String> {
        val result = mutableListOf<String>()
        DiffUtil.calculateDiff(AgentMessageDiff(old, new), false).dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { result += "insert:$position:$count" }
            override fun onRemoved(position: Int, count: Int) { result += "remove:$position:$count" }
            override fun onMoved(fromPosition: Int, toPosition: Int) { result += "move:$fromPosition:$toPosition" }
            override fun onChanged(position: Int, count: Int, payload: Any?) { result += "change:$position:$count:$payload" }
        })
        return result
    }
}
