package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("delete from aiConversations where id = :id")
    fun delete(id: String)

    @Query("delete from aiConversations")
    fun deleteAll()
}
