package io.legado.app.ui.book.agent

import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemAgentReplyBinding
import io.legado.app.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用合成回复验证滚动与局部刷新，不发送真实 AI 请求或修改会话。 */
@RunWith(AndroidJUnit4::class)
class AgentStreamingRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun collapsedStepCountUpdatesWhileTextStreamsAndExpandingShowsLatestSteps() {
        lateinit var list: RecyclerView
        lateinit var adapter: AgentAdapter
        val question = AgentMessage(true, "合成执行过程更新测试")
        var reply = AgentMessage(text = "开始执行", streaming = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    itemAnimator = null
                }
                adapter = AgentAdapter(activity, object : AgentAdapter.CallBack {
                    override fun openBook(book: SearchBook) = Unit
                    override fun onLoadMore() = Unit
                    override fun importBookSource(source: SourceRepositoryItem) = Unit
                    override fun openGeneratedImage(path: String) = Unit
                })
                list.adapter = adapter
                activity.setContentView(list)
                adapter.setItems(listOf(question, reply))
            }
            try {
                awaitMain { lastBinding(list) != null }
                for (count in listOf(1, 2, 9, 10, 99, 100, 136, 137)) {
                    instrumentation.runOnMainSync {
                        reply = reply.copy(
                            text = "正在执行 $count",
                            steps = List(count) { AgentStep("step-$it", "执行步骤 $it") }
                        )
                        adapter.setItems(listOf(question, reply))
                        adapter.updateLastText("正在执行 $count，回复分片")
                    }
                    awaitMain {
                        lastBinding(list)?.tvStepsHeader?.text ==
                            list.context.getString(R.string.agent_steps_count, count)
                    }
                    instrumentation.runOnMainSync {
                        assertEquals(View.GONE, lastBinding(list)!!.llSteps.visibility)
                        assertEquals(0, lastBinding(list)!!.llSteps.childCount)
                    }
                }
                instrumentation.runOnMainSync { lastBinding(list)!!.tvStepsHeader.performClick() }
                awaitMain { lastBinding(list)?.llSteps?.childCount == 137 }
                instrumentation.runOnMainSync { lastBinding(list)!!.tvStepsHeader.performClick() }
                awaitMain { lastBinding(list)?.llSteps?.visibility == View.GONE }
                instrumentation.runOnMainSync {
                    reply = reply.copy(steps = reply.steps + AgentStep("next", "新增步骤"))
                    adapter.setItems(listOf(question, reply))
                }
                awaitMain {
                    lastBinding(list)?.tvStepsHeader?.text ==
                        list.context.getString(R.string.agent_steps_count, 138)
                }
                instrumentation.runOnMainSync {
                    assertEquals(View.GONE, lastBinding(list)!!.llSteps.visibility)
                    reply = reply.copy(streaming = false)
                    adapter.setItems(listOf(question, reply))
                }
                awaitMain { lastBinding(list)?.tvMessage?.text?.toString() == reply.text }
                instrumentation.runOnMainSync {
                    assertEquals(list.context.getString(R.string.agent_steps_count, 138), lastBinding(list)!!.tvStepsHeader.text)
                    assertEquals(View.GONE, lastBinding(list)!!.llSteps.visibility)
                }
            } finally {
                instrumentation.runOnMainSync { list.adapter = null }
            }
        }
    }

    @Test
    fun streamingKeepsStepViewsAndHistoryPositionAndDisplaysFinalText() {
        lateinit var list: RecyclerView
        lateinit var adapter: AgentAdapter
        lateinit var scrolling: AgentAutoScroll
        val history = List(30) { index -> AgentMessage(index % 2 == 0, "历史消息 $index\n".repeat(4)) }
        var reply = AgentMessage(
            text = "**初始回复**\n\n" + "正文内容\n".repeat(10),
            steps = listOf(AgentStep("test", "合成测试")),
            streaming = true
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }
                    itemAnimator = null
                }
                adapter = AgentAdapter(activity, object : AgentAdapter.CallBack {
                    override fun openBook(book: SearchBook) = Unit
                    override fun onLoadMore() = Unit
                    override fun importBookSource(source: SourceRepositoryItem) = Unit
                    override fun openGeneratedImage(path: String) = Unit
                })
                list.adapter = adapter
                activity.setContentView(list)
                scrolling = AgentAutoScroll(list)
                adapter.setItems(history + reply)
            }
            try {
                awaitMain { lastBinding(list)?.tvMessage?.text?.contains("初始回复") == true && !list.canScrollVertically(1) }
                instrumentation.runOnMainSync { lastBinding(list)!!.tvStepsHeader.performClick() }
                awaitMain { lastBinding(list)?.llSteps?.childCount == 1 }
                var originalStep: android.view.View? = null
                instrumentation.runOnMainSync { originalStep = lastBinding(list)!!.llSteps.getChildAt(0) }
                repeat(20) { index ->
                    instrumentation.runOnMainSync { adapter.updateLastText(reply.text + "追加 $index\n") }
                    SystemClock.sleep(10)
                }
                awaitMain { lastBinding(list)?.tvMessage?.text?.contains("追加 19") == true && !list.canScrollVertically(1) }
                instrumentation.runOnMainSync { assertSame(originalStep, lastBinding(list)!!.llSteps.getChildAt(0)) }

                instrumentation.runOnMainSync { list.smoothScrollBy(0, -list.height * 2) }
                awaitMain { list.scrollState == RecyclerView.SCROLL_STATE_IDLE && list.canScrollVertically(1) }
                var firstPosition = 0
                var firstTop = 0
                instrumentation.runOnMainSync {
                    val manager = list.layoutManager as LinearLayoutManager
                    firstPosition = manager.findFirstVisibleItemPosition()
                    firstTop = manager.findViewByPosition(firstPosition)!!.top
                    reply = reply.copy(text = reply.text + "很长的新增内容\n".repeat(60))
                    adapter.updateLastText(reply.text)
                    adapter.setItems(history + reply.copy(steps = listOf(reply.steps.single().copy(state = AgentStepState.DONE))))
                    scrolling.requestScroll()
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(200)
                instrumentation.runOnMainSync {
                    val manager = list.layoutManager as LinearLayoutManager
                    assertEquals(firstPosition, manager.findFirstVisibleItemPosition())
                    assertEquals(firstTop, manager.findViewByPosition(firstPosition)!!.top)
                    scrolling.followLatest()
                    reply = reply.copy(text = "**最终全文**\n\n" + "完整内容\n".repeat(30), streaming = false)
                    adapter.setItems(history + reply)
                }
                awaitMain { lastBinding(list)?.tvMessage?.text?.startsWith("最终全文") == true && !list.canScrollVertically(1) }
                instrumentation.runOnMainSync {
                    assertTrue(lastBinding(list)!!.tvMessage.text.toString().endsWith("完整内容"))
                }
            } finally {
                instrumentation.runOnMainSync {
                    scrolling.dispose()
                    list.adapter = null
                }
            }
        }
    }

    private fun lastBinding(list: RecyclerView): ItemAgentReplyBinding? =
        list.findViewHolderForAdapterPosition((list.adapter?.itemCount ?: 0) - 1)?.itemView
            ?.takeIf { it.findViewById<android.view.View>(R.id.ll_steps_root) != null }
            ?.let(ItemAgentReplyBinding::bind)

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("等待流式布局完成超时")
    }
}
