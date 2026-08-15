package io.legado.app.data.entities

import androidx.room.Entity

/**
 * 按日期累计的阅读时长
 */
@Entity(tableName = "readStats", primaryKeys = ["bookName", "date"])
data class ReadStat(
    var bookName: String = "",
    var date: Long = 0L,
    var readTime: Long = 0L
)
