package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.AiConversation
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConversationDao {

    @Query("select * from aiConversations order by updatedAt desc")
    fun observeAll(): Flow<List<AiConversation>>

    @Query("select * from aiConversations order by updatedAt desc limit 1")
    fun latest(): AiConversation?

    @Query("select * from aiConversations where id = :id")
    fun get(id: String): AiConversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(conversation: AiConversation)

    /** 自动保存只更新聊天内容，已有名称（含用户重命名）以数据库中的最新值为准。 */
    @Transaction
    fun saveSnapshot(conversation: AiConversation) {
        val storedTitle = get(conversation.id)?.title
        insert(if (storedTitle == null) conversation else conversation.copy(title = storedTitle))
    }

    @Query("update aiConversations set title = :title where id = :id")
    fun rename(id: String, title: String): Int

    @Query("delete from aiConversations where id = :id")
    fun delete(id: String)

    @Query("delete from aiConversations")
    fun deleteAll()
}
