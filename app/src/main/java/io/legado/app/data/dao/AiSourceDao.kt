package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiSource
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSourceDao {

    @Query("select * from aiSources order by lastUpdateTime desc")
    fun observeAll(): Flow<List<AiSource>>

    @get:Query("select * from aiSources order by lastUpdateTime desc")
    val all: List<AiSource>

    @get:Query("select * from aiSources where enabled = 1 order by lastUpdateTime desc")
    val allEnabled: List<AiSource>

    @Query("select * from aiSources where id = :id")
    fun get(id: Long): AiSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg aiSource: AiSource)

    @Update
    fun update(vararg aiSource: AiSource)

    @Delete
    fun delete(vararg aiSource: AiSource)

    @Query("delete from aiSources where id = :id")
    fun delete(id: Long)

}
