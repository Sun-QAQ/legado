package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadStat

@Dao
interface ReadStatDao {

    @get:Query("select * from readStats")
    val all: List<ReadStat>

    @Query("select * from readStats where date >= :startDate and date <= :endDate order by date asc")
    fun getStats(startDate: Long, endDate: Long): List<ReadStat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg stat: ReadStat)

    @Query(
        """
        insert into readStats (bookName, date, readTime)
        values (:bookName, :date, :time)
        on conflict(bookName, date) do update set readTime = readTime + :time
        """
    )
    fun addTime(bookName: String, date: Long, time: Long)
}
