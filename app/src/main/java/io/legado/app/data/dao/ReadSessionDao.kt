package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadSessionDao {
    @get:Query("select * from readSessions order by startTime")
    val all: List<ReadSession>

    @Query("select * from readSessions where date = :date order by startTime desc, id")
    fun observeDay(date: Long): Flow<List<ReadSession>>

    @Query("select * from readSessions where id = :id")
    fun get(id: String): ReadSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg sessions: ReadSession)

    @Query("delete from readSessions where id = :id")
    fun delete(id: String)

    @Query("delete from readSessions where bookName = :name")
    fun deleteByName(name: String)

    @Query("delete from readSessions")
    fun clear()
}
