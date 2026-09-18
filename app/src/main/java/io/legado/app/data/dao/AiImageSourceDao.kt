package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiImageSource
import kotlinx.coroutines.flow.Flow

@Dao
interface AiImageSourceDao {

    @Query("select * from aiImageSources order by lastUpdateTime desc")
    fun observeAll(): Flow<List<AiImageSource>>

    @get:Query("select * from aiImageSources where enabled = 1 order by lastUpdateTime desc")
    val allEnabled: List<AiImageSource>

    @Query("select * from aiImageSources where id = :id")
    fun get(id: Long): AiImageSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(source: AiImageSource)

    @Update
    fun update(source: AiImageSource)

    @Delete
    fun delete(source: AiImageSource)
}
