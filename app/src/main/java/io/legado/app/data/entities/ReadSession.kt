package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A foreground reading interval, split at local midnight. Times are Unix milliseconds. */
@Entity(tableName = "readSessions", indices = [Index(value = ["date", "startTime"]), Index("bookName")])
data class ReadSession(
    @PrimaryKey val id: String,
    val deviceId: String,
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val date: Long,
    val startTime: Long,
    val endTime: Long,
    val readTime: Long,
    val zoneId: String,
)
