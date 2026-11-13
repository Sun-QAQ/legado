package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReadRecordFilterTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `today excludes yesterday and includes midnight`() {
        assertBoundary(ReadRecordFilter.TODAY, today)
    }

    @Test
    fun `week includes today and the previous six days`() {
        assertBoundary(ReadRecordFilter.WEEK, today.minusDays(6))
    }

    @Test
    fun `month includes today and the previous twenty nine days`() {
        assertBoundary(ReadRecordFilter.MONTH, today.minusDays(29))
    }

    @Test
    fun `all includes old and undated records`() {
        val start = ReadRecordFilter.ALL.startTime(today, zone)
        assertTrue(0L >= start)
        assertTrue(today.minusYears(10).atStartOfDay(zone).toInstant().toEpochMilli() >= start)
    }

    @Test
    fun `ranges follow local calendar days across daylight saving`() {
        val end = LocalDate.of(2026, 11, 3)
        val newYork = ZoneId.of("America/New_York")
        assertEquals(
            end.minusDays(6).atStartOfDay(newYork).toInstant().toEpochMilli(),
            ReadRecordFilter.WEEK.startTime(end, newYork),
        )
    }

    private fun assertBoundary(filter: ReadRecordFilter, firstDay: LocalDate) {
        val boundary = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = filter.startTime(today, zone)
        assertEquals(boundary, start)
        assertFalse(boundary - 1 >= start)
        assertTrue(boundary >= start)
        assertTrue(today.atStartOfDay(zone).toInstant().toEpochMilli() >= start)
    }
}
