package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReadSessionTrackerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val a = ReadSessionTracker.Book("local:a", "甲书", "作者甲")
    private val b = ReadSessionTracker.Book("local:b", "乙书", "作者乙")
    private fun time(value: String, zone: ZoneId = this.zone) =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `checkpoints extend one session and pause flushes the tail once`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-10-26T20:00:00")
        assertTrue(tracker.update(true, true, a, start, 0, zone).isEmpty())
        val first = tracker.update(true, true, a, start + 30_000, 30_000, zone).single()
        val last = tracker.update(false, true, null, start + 45_000, 45_000, zone).single()
        assertEquals(first.session.id, last.session.id)
        assertEquals(45_000L, last.session.readTime)
        assertEquals(45_000L, first.addedTime + last.addedTime)
        assertTrue(tracker.update(false, true, null, start + 60_000, 60_000, zone).isEmpty())
    }

    @Test
    fun `pause gap is excluded and resuming starts a new session`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-10-26T20:00:00")
        tracker.update(true, true, a, start, 0, zone)
        val before = tracker.update(false, true, null, start + 10_000, 10_000, zone).single()
        assertTrue(tracker.update(true, true, a, start + 3_600_000, 3_600_000, zone).isEmpty())
        val after = tracker.update(false, true, null, start + 3_620_000, 3_620_000, zone).single()
        assertNotEquals(before.session.id, after.session.id)
        assertEquals(30_000L, before.addedTime + after.addedTime)
    }

    @Test
    fun `switching books flushes the old identity before the new one`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-10-26T20:00:00")
        tracker.update(true, true, a, start, 0, zone)
        val old = tracker.update(true, true, b, start + 10_000, 10_000, zone).single()
        val new = tracker.update(false, true, null, start + 30_000, 30_000, zone).single()
        assertEquals(a.name, old.session.bookName)
        assertEquals(b.name, new.session.bookName)
        assertEquals(10_000L, old.addedTime)
        assertEquals(20_000L, new.addedTime)
    }

    @Test
    fun `midnight and year boundaries split sessions without losing milliseconds`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-12-31T23:59:50")
        tracker.update(true, true, a, start, 0, zone)
        val parts = tracker.update(false, true, null, start + 30_000, 30_000, zone)
        assertEquals(2, parts.size)
        assertEquals(listOf(10_000L, 20_000L), parts.map { it.addedTime })
        assertNotEquals(parts[0].session.id, parts[1].session.id)
        assertEquals(parts[0].session.endTime, parts[1].session.startTime)
        assertEquals(1L, parts[1].session.date - parts[0].session.date)
    }

    @Test
    fun `disabling flushes prior time but excludes disabled time`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-10-26T20:00:00")
        tracker.update(true, true, a, start, 0, zone)
        val before = tracker.update(true, false, a, start + 10_000, 10_000, zone).single()
        assertTrue(tracker.update(true, true, a, start + 20_000, 20_000, zone).isEmpty())
        val after = tracker.update(false, true, null, start + 30_000, 30_000, zone).single()
        assertNotEquals(before.session.id, after.session.id)
        assertEquals(20_000L, before.addedTime + after.addedTime)
    }

    @Test
    fun `clock changes never invent long or negative reading periods`() {
        for (jump in listOf(-86_400_000L, 86_400_000L)) {
            val tracker = ReadSessionTracker("device")
            val start = time("2026-10-26T20:00:00")
            tracker.update(true, true, a, start, 0, zone)
            assertTrue(tracker.update(true, true, a, start + jump + 30_000, 30_000, zone).isEmpty())
            val next = tracker.update(false, true, null, start + jump + 40_000, 40_000, zone).single()
            assertEquals(10_000L, next.addedTime)
            assertEquals(start + jump + 30_000, next.session.startTime)
        }
    }

    @Test
    fun `initializing without a book does not record loading time`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-10-26T20:00:00")
        tracker.update(true, true, null, start, 0, zone)
        assertTrue(tracker.update(true, true, a, start + 30_000, 30_000, zone).isEmpty())
        assertEquals(10_000L,
            tracker.update(false, true, null, start + 40_000, 40_000, zone).single().addedTime)
    }

    @Test
    fun `timezone changes create separate correctly dated segments`() {
        val tracker = ReadSessionTracker("device")
        val start = time("2026-10-26T00:00:00")
        val other = ZoneId.of("America/New_York")
        tracker.update(true, true, a, start, 0, zone)
        assertTrue(tracker.update(true, true, a, start + 30_000, 30_000, other).isEmpty())
        val last = tracker.update(false, true, null, start + 60_000, 60_000, other).single()
        assertEquals(other.id, last.session.zoneId)
        assertEquals(java.time.LocalDate.of(2026, 10, 25).toEpochDay(), last.session.date)
        assertEquals(30_000L, last.addedTime)
    }

    @Test
    fun `DST transition respects the length of the local calendar day`() {
        val dstZone = ZoneId.of("America/New_York")
        val tracker = ReadSessionTracker("device")
        val start = time("2026-03-08T00:00:00", dstZone)
        val end = time("2026-03-09T00:01:00", dstZone)
        tracker.update(true, true, a, start, 0, dstZone)
        val parts = tracker.update(false, true, null, end, end - start, dstZone)
        assertEquals(listOf(23 * 3_600_000L, 60_000L), parts.map { it.addedTime })
        assertEquals(end - start, parts.sumOf { it.addedTime })
    }
}
