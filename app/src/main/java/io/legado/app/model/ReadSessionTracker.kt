package io.legado.app.model

import io.legado.app.data.entities.ReadSession
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** Pure clock/state logic; callers serialize transitions and persist each increment once. */
internal class ReadSessionTracker(private val deviceId: String) {
    data class Book(val url: String, val name: String, val author: String)
    data class Increment(val session: ReadSession, val addedTime: Long)

    private var active = false
    private var enabled = false
    private var book: Book? = null
    private var wall = 0L
    private var elapsed = 0L
    private var zone: ZoneId? = null
    private var segment: ReadSession? = null

    fun update(
        active: Boolean, enabled: Boolean, book: Book?,
        wall: Long, elapsed: Long, zone: ZoneId,
    ): List<Increment> {
        val result = mutableListOf<Increment>()
        val delta = elapsed - this.elapsed
        val end = this.wall + delta
        val clockStable = delta >= 0 && this.zone == zone && abs(wall - end) <= 2_000
        if (this.active && this.enabled && this.book != null && clockStable && delta > 0) {
            var cursor = this.wall
            val previousBook = checkNotNull(this.book)
            while (cursor < end) {
                val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val partEnd = minOf(end, midnight)
                val current = segment?.takeIf { it.date == date.toEpochDay() } ?: ReadSession(
                    UUID.randomUUID().toString(), deviceId, previousBook.url, previousBook.name,
                    previousBook.author, date.toEpochDay(), cursor, cursor, 0, zone.id,
                )
                segment = current.copy(endTime = partEnd, readTime = partEnd - current.startTime)
                result.add(Increment(checkNotNull(segment), partEnd - cursor))
                cursor = partEnd
            }
        }
        if (!clockStable || !active || !enabled || this.book != book || !this.active || !this.enabled) {
            segment = null
        }
        this.wall = if (clockStable && this.active && this.enabled && this.book != null) end else wall
        this.elapsed = elapsed
        this.zone = zone
        this.active = active
        this.enabled = enabled
        this.book = book
        return result
    }
}
