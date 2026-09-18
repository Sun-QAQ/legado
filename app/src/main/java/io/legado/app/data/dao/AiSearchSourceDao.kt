package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiSearchSource
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSearchSourceDao {

    @Query("select * from aiSearchSources order by lastUpdateTime desc")
    fun observeAll(): Flow<List<AiSearchSource>>

    @get:Query("select * from aiSearchSources where enabled = 1 order by lastUpdateTime desc")
    val allEnabled: List<AiSearchSource>

    @Query("select * from aiSearchSources where id = :id")
    fun get(id: Long): AiSearchSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(source: AiSearchSource)

    @Update
    fun update(source: AiSearchSource)

    @Delete
    fun delete(source: AiSearchSource)
}
