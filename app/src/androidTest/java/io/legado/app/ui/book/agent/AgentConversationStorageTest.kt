package io.legado.app.ui.book.agent

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiConversation
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentConversationStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun oversizedLegacySnapshotRestoresAllFieldsAcrossUtf8ChunkBoundaries() {
        withDatabase { db ->
            val dao = db.aiConversationDao
            // 汉字和 emoji 跨越 256 KiB 分块边界，NUL 后也必须完整恢复。
            val payload = "a".repeat(256 * 1024 - 1) + "中文😀\u0000" + "章😀".repeat(400000)
            val original = fixture().copy(messagesJson = payload, turnsJson = payload, personaPrompt = payload)
            dao.insert(original)
            assertEquals(original, dao.get(original.id))
            assertEquals(original, dao.latest())
            assertTrue(dao.exists(original.id))
            assertNull(dao.get("missing"))
            assertFalse(dao.exists("missing"))
            val exactChunk = original.copy(messagesJson = "a".repeat(256 * 1024), turnsJson = "", personaPrompt = "")
            dao.insert(exactChunk)
            assertEquals(exactChunk, dao.get(original.id))
        }
    }

    @Test
    fun historyAndAutosaveReadOnlyMetadataAndKeepRenamedTitle() {
        withDatabase { db ->
            val dao = db.aiConversationDao
            val large = fixture().copy(turnsJson = "大".repeat(1200000))
            val older = fixture().copy(updatedAt = large.updatedAt - 1)
            dao.insert(older)
            dao.insert(large)
            val history = runBlocking { dao.observeAll().first() }
            assertEquals(listOf(large.id, older.id), history.map { it.id })
            assertEquals(large.title, history.first().title)
            assertEquals(1, dao.rename(large.id, "保留自定义名称"))
            dao.saveSnapshot(large.copy(title = "过期标题", turnsJson = "[]"))
            assertEquals("保留自定义名称", dao.title(large.id))
            assertEquals("[]", dao.get(large.id)!!.turnsJson)
            dao.delete(large.id)
            assertEquals(older, dao.latest())
            dao.deleteAll()
            assertNull(dao.latest())
        }
    }

    @Test
    fun startupRestoresLargeToolHistoryAndCanPersistAgain() = runBlocking {
        val context = instrumentation.targetContext
        val preference = "large-conversation-${UUID.randomUUID()}"
        val previousPersona = context.getPrefLong(PreferKey.aiPersonaId)
        val previousSupplier = context.getPrefLong(PreferKey.aiSupplierId)
        val largeText = "章节😀".repeat(400000)
        val conversation = fixture().copy(turnsJson = AgentConversationCodec.encodeTurns(listOf(
            AgentChatTurn("user", "原始问题"),
            AgentChatTurn("tool", largeText, "chapter"),
            AgentChatTurn("assistant", "原始回复")
        )))
        val agent = AgentViewModel(context.applicationContext as Application, preference, false)
        try {
            appDb.aiConversationDao.insert(conversation)
            context.putPrefString(preference, conversation.id)
            agent.startConversationHistory()?.join()
            assertEquals(conversation.id, agent.currentConversationId.value)
            assertEquals(listOf("原始问题", "原始回复"), agent.messages.value.map { it.text })
            agent.renameConversation(conversation.id, "大体积历史").join()
            agent.newConversation().join()
            val stored = appDb.aiConversationDao.get(conversation.id)!!
            assertEquals("大体积历史", stored.title)
            assertEquals(largeText, AgentConversationCodec.decodeTurns(stored.turnsJson)[1].content)
            agent.openConversation(conversation.id)?.join()
            assertEquals(conversation.id, agent.currentConversationId.value)
        } finally {
            agent.newConversation().join()
            appDb.aiConversationDao.delete(conversation.id)
            context.defaultSharedPreferences.edit().remove(preference).apply()
            context.putPrefLong(PreferKey.aiPersonaId, previousPersona)
            context.putPrefLong(PreferKey.aiSupplierId, previousSupplier)
        }
    }

    private fun fixture(): AiConversation = AiConversation(
        "storage-test-${UUID.randomUUID()}", "历史对话",
        AgentConversationCodec.encodeMessages(listOf(AgentMessage(true, "原始问题"), AgentMessage(text = "原始回复"))),
        "[]", 0, 0, "默认", DEFAULT_AGENT_PERSONA_PROMPT, 1, System.currentTimeMillis()
    )

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }
}
