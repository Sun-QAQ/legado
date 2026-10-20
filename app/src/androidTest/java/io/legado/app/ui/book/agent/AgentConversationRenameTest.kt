package io.legado.app.ui.book.agent

import android.app.Application
import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.drawToBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiConversation
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentConversationRenameTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun namesSurviveQueuedSnapshotsAndReloadWithoutChangingArchivedContent() = runBlocking {
        val context = instrumentation.targetContext
        val previousPersona = context.getPrefLong(PreferKey.aiPersonaId)
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val preference = "conversation-rename-${UUID.randomUUID()}"
        val active = fixture("原始名称")
        val archived = fixture("历史名称")
        val agent = AgentViewModel(context.applicationContext as Application, preference, false)
        val restored = AgentViewModel(context.applicationContext as Application, preference, false)
        try {
            appDb.aiConversationDao.insert(active)
            appDb.aiConversationDao.insert(archived)
            context.putPrefString(preference, active.id)
            agent.startConversationHistory()?.join()
            var renamed = false
            agent.renameConversation(archived.id, "历史自定义名称") { renamed = it }.join()
            assertTrue(renamed)
            assertEquals(active.id, agent.currentConversationId.value)
            val storedArchive = appDb.aiConversationDao.get(archived.id)!!
            assertEquals(archived.copy(title = "历史自定义名称"), storedArchive)

            val userId = agent.messages.value.first().id!!
            lateinit var rename: Job
            instrumentation.runOnMainSync {
                repeat(10) { assertTrue(agent.editMessage(userId, "改名前 $it")) }
                rename = agent.renameConversation(active.id, "  共读三体  ") { renamed = it }
                repeat(10) { assertTrue(agent.editMessage(userId, "改名后 $it")) }
            }
            rename.join()
            assertTrue(renamed)
            agent.newConversation().join()
            val stored = appDb.aiConversationDao.get(active.id)!!
            assertEquals("共读三体", stored.title)
            assertEquals("改名后 9", AgentConversationCodec.decodeMessages(stored.messagesJson).first().text)
            // 其他保存者携带旧标题时，事务仍应保留数据库中的最新名称。
            appDb.aiConversationDao.saveSnapshot(stored.copy(title = "过期自动标题", updatedAt = stored.updatedAt + 1))
            assertEquals("共读三体", appDb.aiConversationDao.get(active.id)!!.title)

            context.putPrefString(preference, active.id)
            restored.startConversationHistory()?.join()
            instrumentation.runOnMainSync {
                assertTrue(restored.editMessage(restored.messages.value.first().id!!, "重开后继续聊天"))
            }
            restored.newConversation().join()
            assertEquals("共读三体", appDb.aiConversationDao.get(active.id)!!.title)
            appDb.aiConversationDao.delete(archived.id)
            agent.renameConversation(archived.id, "已删除名称") { renamed = it }.join()
            assertFalse(renamed)
            assertNull(appDb.aiConversationDao.get(archived.id))
        } finally {
            agent.newConversation().join()
            restored.newConversation().join()
            appDb.aiConversationDao.delete(active.id)
            appDb.aiConversationDao.delete(archived.id)
            context.defaultSharedPreferences.edit().remove(preference).apply()
            context.putPrefLong(PreferKey.aiPersonaId, previousPersona)
            context.putPrefLong(PreferKey.aiSupplierId, previousSupplier)
        }
    }

    @Test
    fun historyButtonAndLongPressRenameWithValidationAndCancel() {
        val conversation = fixture("历史名称测试")
        lateinit var history: AgentConversationHistoryDialog
        lateinit var list: RecyclerView
        lateinit var agent: AgentViewModel
        lateinit var editor: AlertDialog
        appDb.aiConversationDao.insert(conversation)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val container = FrameLayout(activity).apply { id = View.generateViewId() }
                    activity.setContentView(container)
                    val owner = Fragment(R.layout.dialog_edit_text)
                    activity.supportFragmentManager.beginTransaction().add(container.id, owner).commitNow()
                    agent = ViewModelProvider(owner)[AgentViewModel::class.java]
                    history = AgentConversationHistoryDialog()
                    history.show(owner.childFragmentManager, "rename-history")
                    owner.childFragmentManager.executePendingTransactions()
                }
                awaitMain { history.view?.width?.let { it > 0 } == true }
                instrumentation.runOnMainSync { list = history.requireView().findViewById(R.id.recycler_view) }
                awaitMain {
                    list.findViewHolderForAdapterPosition(0)?.itemView
                        ?.findViewById<TextView>(R.id.tv_title)?.text?.toString() == conversation.title
                }
                instrumentation.runOnMainSync {
                    val row = list.findViewHolderForAdapterPosition(0)!!.itemView
                    assertTrue(row.findViewById<View>(R.id.iv_rename).performClick())
                    editor = renameDialog(history)!!
                    val text = editor.findViewById<EditText>(R.id.edit_view)!!
                    assertEquals(conversation.title, text.text.toString())
                    text.setText("   ")
                    editor.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    assertTrue(editor.isShowing)
                    assertNotNull(text.error)
                    text.setText("共读三体的感受")
                    editor.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                }
                awaitMain {
                    !editor.isShowing && list.findViewHolderForAdapterPosition(0)?.itemView
                        ?.findViewById<TextView>(R.id.tv_title)?.text?.toString() == "共读三体的感受"
                }
                instrumentation.runOnMainSync {
                    assertNull(agent.currentConversationId.value)
                    val row = list.findViewHolderForAdapterPosition(0)!!.itemView
                    File(instrumentation.targetContext.cacheDir, "agent_conversation_rename_preview.png").outputStream().use {
                        row.drawToBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    assertTrue(row.performLongClick())
                    editor = renameDialog(history)!!
                    editor.findViewById<EditText>(R.id.edit_view)!!.setText("取消的改名")
                    editor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                }
                assertEquals("共读三体的感受", appDb.aiConversationDao.get(conversation.id)!!.title)
            }
        } finally {
            appDb.aiConversationDao.delete(conversation.id)
        }
    }

    private fun fixture(title: String): AiConversation {
        val now = System.currentTimeMillis() + 60000
        return AiConversation("rename-test-${UUID.randomUUID()}", title,
            AgentConversationCodec.encodeMessages(listOf(AgentMessage(true, "原始问题"), AgentMessage(text = "原始回复"))),
            AgentConversationCodec.encodeTurns(listOf(AgentChatTurn("user", "原始问题"), AgentChatTurn("assistant", "原始回复"))),
            0, 0, "默认", DEFAULT_AGENT_PERSONA_PROMPT, now, now)
    }

    private fun renameDialog(history: AgentConversationHistoryDialog): AlertDialog? =
        AgentConversationHistoryDialog::class.java.getDeclaredField("renameDialog")
            .apply { isAccessible = true }.get(history) as AlertDialog?

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("等待历史对话界面完成超时")
    }
}
