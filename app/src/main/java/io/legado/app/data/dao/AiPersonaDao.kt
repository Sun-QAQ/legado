package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiPersona
import kotlinx.coroutines.flow.Flow

@Dao
interface AiPersonaDao {

    @Query("select * from aiPersonas order by lastUpdateTime desc")
    fun observeAll(): Flow<List<AiPersona>>

    @get:Query("select * from aiPersonas order by lastUpdateTime desc")
    val all: List<AiPersona>

    @Query("select * from aiPersonas where id = :id")
    fun get(id: Long): AiPersona?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg aiPersona: AiPersona)

    @Update
    fun update(vararg aiPersona: AiPersona)

    @Query("delete from aiPersonas where id = :id")
    fun delete(id: Long)

}
