package io.legado.app.ui.book.agent

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.core.view.drawToBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentMessageActionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun editsAndDeletesSurviveReloadAndDeletingLastMessageRemovesSnapshot() = runBlocking {
        val context = instrumentation.targetContext
        val previousPersona = context.getPrefLong(PreferKey.aiPersonaId)
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val id = "message-actions-${UUID.randomUUID()}"
        val preference = "message-actions-test-${UUID.randomUUID()}"
        val agent = AgentViewModel(context.applicationContext as Application, preference, false)
        val messages = listOf(AgentMessage(true, "原始问题"), AgentMessage(text = "原始回复"))
        val calls = JsonParser.parseString("""[{"id":"read","type":"function","function":{"name":"read_book_content","arguments":"{}"}}]""")
        val turns = listOf(AgentChatTurn("user", "原始问题"), AgentChatTurn("assistant", "读取中", toolCalls = calls),
            AgentChatTurn("tool", "章节正文", "read"), AgentChatTurn("assistant", "原始回复"))
        val now = System.currentTimeMillis()
        try {
            appDb.aiConversationDao.insert(AiConversation(id, "测试会话", AgentConversationCodec.encodeMessages(messages),
                AgentConversationCodec.encodeTurns(turns), 0, 0, "默认", DEFAULT_AGENT_PERSONA_PROMPT, now, now))
            context.putPrefString(preference, id)
            agent.startConversationHistory()?.join()
            val userId = agent.messages.value[0].id!!
            val replyId = agent.messages.value[1].id!!
            instrumentation.runOnMainSync {
                assertTrue(agent.editMessage(userId, "修改的问题\n第二行"))
                assertTrue(agent.editMessage(replyId, "**修改后的回复**"))
            }
            agent.newConversation().join()
            agent.openConversation(id)?.join()
            assertEquals("修改的问题\n第二行", agent.messages.value[0].text)
            assertEquals("**修改后的回复**", agent.messages.value[1].text)
            val stored = AgentConversationCodec.decodeTurns(appDb.aiConversationDao.get(id)!!.turnsJson)
            assertEquals("修改的问题\n第二行", stored.first().content)
            assertEquals("**修改后的回复**", stored.last().content)
            assertEquals("章节正文", stored[2].content)
            instrumentation.runOnMainSync { assertTrue(agent.deleteMessage(replyId)) }
            agent.newConversation().join()
            agent.openConversation(id)?.join()
            assertEquals(1, agent.messages.value.size)
            assertEquals(1, AgentConversationCodec.decodeTurns(appDb.aiConversationDao.get(id)!!.turnsJson).size)
            instrumentation.runOnMainSync {
                // 排队的旧快照不能在删除后把消息重新写回来。
                repeat(20) { assertTrue(agent.editMessage(userId, "修改 $it")) }
                assertTrue(agent.deleteMessage(userId))
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (appDb.aiConversationDao.get(id) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertNull(appDb.aiConversationDao.get(id))
            assertNull(agent.currentConversationId.value)
            assertTrue(agent.messages.value.isEmpty())
            agent.newConversation().join()
            SystemClock.sleep(100)
            assertNull(appDb.aiConversationDao.get(id))
        } finally {
            agent.newConversation().join()
            appDb.aiConversationDao.delete(id)
            context.defaultSharedPreferences.edit().remove(preference).apply()
            context.putPrefLong(PreferKey.aiPersonaId, previousPersona)
            context.putPrefLong(PreferKey.aiSupplierId, previousSupplier)
        }
    }

    @Test
    fun longPressUsesCurrentMessageAfterDeletionAndEditorSavesMultilineMarkdown() {
        lateinit var list: RecyclerView
        lateinit var adapter: AgentAdapter
        lateinit var owner: Fragment
        lateinit var agent: AgentViewModel
        var selectedId: String? = null
        val messages = listOf(AgentMessage(true, "用户消息", id = "user"), AgentMessage(text = "原始回复", id = "reply"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                owner = Fragment()
                activity.supportFragmentManager.beginTransaction().add(owner, "message-editor-owner").commitNow()
                agent = ViewModelProvider(owner)[AgentViewModel::class.java]
                @Suppress("UNCHECKED_CAST")
                val state = AgentViewModel::class.java.getDeclaredField("_messages").apply { isAccessible = true }
                    .get(agent) as MutableStateFlow<List<AgentMessage>>
                state.value = messages
                @Suppress("UNCHECKED_CAST")
                val history = AgentViewModel::class.java.getDeclaredField("history").apply { isAccessible = true }
                    .get(agent) as MutableList<AgentChatTurn>
                history.addAll(listOf(AgentChatTurn("user", "用户消息", messageId = "user"), AgentChatTurn("assistant", "原始回复", messageId = "reply")))
                list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); itemAnimator = null }
                adapter = AgentAdapter(activity, object : AgentAdapter.CallBack {
                    override fun openBook(book: SearchBook) = Unit
                    override fun onLoadMore() = Unit
                    override fun importBookSource(source: SourceRepositoryItem) = Unit
                    override fun openGeneratedImage(path: String) = Unit
                    override fun onMessageLongClick(message: AgentMessage, anchor: View) { selectedId = message.id }
                })
                list.adapter = adapter
                adapter.setItems(messages)
                activity.setContentView(list)
            }
            try {
                awaitMain { list.findViewHolderForAdapterPosition(1) != null }
                instrumentation.runOnMainSync {
                    assertTrue(list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.tv_message).performLongClick())
                    assertEquals("reply", selectedId)
                    assertTrue(agent.deleteMessage("user"))
                    adapter.setItems(agent.messages.value)
                }
                awaitMain { list.findViewHolderForAdapterPosition(0) != null }
                instrumentation.runOnMainSync {
                    selectedId = null
                    assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick())
                    assertEquals("reply", selectedId)
                    AgentMessageEditDialog("reply").show(owner.childFragmentManager, "edit-message")
                    owner.childFragmentManager.executePendingTransactions()
                }
                awaitMain { owner.childFragmentManager.findFragmentByTag("edit-message")?.view?.width?.let { it > 0 } == true }
                instrumentation.runOnMainSync {
                    val dialog = owner.childFragmentManager.findFragmentByTag("edit-message") as AgentMessageEditDialog
                    val text = dialog.requireView().findViewById<EditText>(R.id.et_message)
                    assertEquals("原始回复", text.text.toString())
                    text.setText("**修改后的回复**\n\n第二段内容")
                    File(instrumentation.targetContext.cacheDir, "agent_message_edit_preview.png").outputStream().use {
                        dialog.requireView().drawToBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    dialog.requireView().findViewById<Toolbar>(R.id.tool_bar).menu.performIdentifierAction(R.id.menu_save, 0)
                    assertEquals("**修改后的回复**\n\n第二段内容", agent.messages.value.single().text)
                    adapter.setItems(agent.messages.value)
                }
            } finally {
                instrumentation.runOnMainSync { list.adapter = null }
            }
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("等待消息界面完成超时")
    }
}
