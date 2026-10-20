package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.AiConversationSummary
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream

private const val TEXT_CHUNK_BYTES = 256 * 1024

@Dao
interface AiConversationDao {

    @Query("select id, title, supplierId, personaId, personaName, createdAt, updatedAt from aiConversations order by updatedAt desc")
    fun observeAll(): Flow<List<AiConversationSummary>>

    @Query("select id from aiConversations order by updatedAt desc limit 1")
    fun latestId(): String?

    @Query("select id, title, supplierId, personaId, personaName, createdAt, updatedAt from aiConversations where id = :id")
    fun summary(id: String): AiConversationSummary?

    @Query("select title from aiConversations where id = :id")
    fun title(id: String): String?

    @Query("select exists(select 1 from aiConversations where id = :id)")
    fun exists(id: String): Boolean

    @Transaction
    fun latest(): AiConversation? = latestId()?.let { get(it) }

    /** 一次恢复保持同一事务快照，避免正文超过 CursorWindow 或各分块来自不同版本。 */
    @Transaction
    fun get(id: String): AiConversation? {
        val info = summary(id) ?: return null
        return AiConversation(info.id, info.title,
            readText { offset -> messagesChunk(id, offset, TEXT_CHUNK_BYTES) },
            readText { offset -> turnsChunk(id, offset, TEXT_CHUNK_BYTES) },
            info.supplierId, info.personaId, info.personaName,
            readText { offset -> personaPromptChunk(id, offset, TEXT_CHUNK_BYTES) },
            info.createdAt, info.updatedAt)
    }

    // 使用 BLOB 按 UTF-8 字节分块，拼接后统一解码，保留跨分块的汉字、emoji 和 NUL。
    @Query("select substr(cast(messagesJson as blob), :offset, :size) from aiConversations where id = :id")
    fun messagesChunk(id: String, offset: Int, size: Int): ByteArray?

    @Query("select substr(cast(turnsJson as blob), :offset, :size) from aiConversations where id = :id")
    fun turnsChunk(id: String, offset: Int, size: Int): ByteArray?

    @Query("select substr(cast(personaPrompt as blob), :offset, :size) from aiConversations where id = :id")
    fun personaPromptChunk(id: String, offset: Int, size: Int): ByteArray?

    private fun readText(chunkAt: (Int) -> ByteArray?): String {
        val output = ByteArrayOutputStream()
        var offset = 1 // SQLite substr 从 1 开始。
        while (true) {
            // 同一事务内已确认行存在；部分旧版驱动把空 BLOB 返回为 null。
            val chunk = chunkAt(offset) ?: break
            output.write(chunk)
            if (chunk.size < TEXT_CHUNK_BYTES) break
            offset += chunk.size
        }
        return output.toString(Charsets.UTF_8.name())
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(conversation: AiConversation)

    /** 自动保存只更新聊天内容，已有名称（含用户重命名）以数据库中的最新值为准。 */
    @Transaction
    fun saveSnapshot(conversation: AiConversation) {
        val storedTitle = title(conversation.id)
        insert(if (storedTitle == null) conversation else conversation.copy(title = storedTitle))
    }

    @Query("update aiConversations set title = :title where id = :id")
    fun rename(id: String, title: String): Int

    @Query("delete from aiConversations where id = :id")
    fun delete(id: String)

    @Query("delete from aiConversations")
    fun deleteAll()
}
