package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface AiPresetDao {
    @Query("select * from aiPresets order by lastUpdateTime desc, id desc")
    fun observeAll(): Flow<List<AiPreset>>

    @get:Query("select * from aiPresets order by lastUpdateTime desc, id desc")
    val all: List<AiPreset>

    @Query("select * from aiPresets where id = :id")
    fun get(id: Long): AiPreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg preset: AiPreset)

    @Query("delete from aiPresets where id = :id")
    fun delete(id: Long)
}
