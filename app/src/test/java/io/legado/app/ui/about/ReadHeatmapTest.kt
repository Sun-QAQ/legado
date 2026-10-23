package io.legado.app.ui.about

import io.legado.app.data.entities.ReadStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ReadHeatmapTest {
    private val today = LocalDate.of(2026, 10, 23)

    @Test
    fun `empty calendar covers one year and fills missing days with zero`() {
        val heatmap = ReadHeatmap(today, emptyList())
        assertEquals(365, heatmap.days.size)
        assertEquals(LocalDate.of(2025, 10, 24), heatmap.start)
        assertEquals(today, heatmap.days.last().date)
        assertTrue(heatmap.days.all { it.readTime == 0L && it.level == 0 })
        assertEquals(0, heatmap.activeDays)
    }

    @Test
    fun `multiple books are combined and out of range or negative times excluded`() {
        val heatmap = ReadHeatmap(today, listOf(
            ReadStat("a", today.toEpochDay(), 60_000),
            ReadStat("b", today.toEpochDay(), 120_000),
            ReadStat("c", today.toEpochDay(), -60_000),
            ReadStat("a", today.minusDays(2).toEpochDay(), 60_000),
            ReadStat("old", today.minusYears(1).toEpochDay(), 999_000),
            ReadStat("future", today.plusDays(1).toEpochDay(), 999_000),
        ))
        assertEquals(180_000L, heatmap.days.last().readTime)
        assertEquals(0L, heatmap.days[heatmap.days.lastIndex - 1].readTime)
        assertEquals(240_000L, heatmap.totalTime)
        assertEquals(2, heatmap.activeDays)
    }

    @Test
    fun `color levels change at thirty sixty and one hundred twenty minutes`() {
        val times = listOf(0L, 1L, 29 * 60_000L, 30 * 60_000L, 59 * 60_000L,
            60 * 60_000L, 119 * 60_000L, 120 * 60_000L, TimeUnit.HOURS.toMillis(24))
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 4, 4),
            times.map { ReadHeatmap.Day(today, it).level })
    }

    @Test
    fun `calendar keeps leap day and uses calendar dates across daylight saving`() {
        val heatmap = ReadHeatmap(LocalDate.of(2024, 3, 10), emptyList())
        assertEquals(366, heatmap.days.size)
        assertTrue(heatmap.days.any { it.date == LocalDate.of(2024, 2, 29) })
        heatmap.days.zipWithNext { first, second ->
            assertEquals(first.date.plusDays(1), second.date)
        }
    }

    @Test
    fun `columns align weekdays and never contain future days`() {
        for (offset in 0L..6L) {
            val heatmap = ReadHeatmap(today.plusDays(offset), emptyList())
            heatmap.days.forEach { day ->
                assertEquals(day.date.dayOfWeek.value - 1, heatmap.cellIndex(day) % 7)
                assertTrue(heatmap.cellIndex(day) / 7 < heatmap.columnCount)
            }
            assertEquals(today.plusDays(offset), heatmap.days.last().date)
        }
    }

    @Test
    fun `leap year ending on sunday may need fifty four columns`() {
        val heatmap = ReadHeatmap(LocalDate.of(2024, 12, 29), emptyList())
        assertEquals(366, heatmap.days.size)
        assertEquals(53, heatmap.columnCount)
        val mondayEnd = ReadHeatmap(LocalDate.of(2024, 12, 30), emptyList())
        assertEquals(54, mondayEnd.columnCount)
    }
}
