package io.legado.app.ui.about

import io.legado.app.data.entities.ReadStat
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AnnualReadReportTest {
    private fun stat(name: String, date: String, time: Long) =
        ReadStat(name, LocalDate.parse(date).toEpochDay(), time)

    @Test
    fun `year boundaries and future dates never leak into a report`() {
        val report = AnnualReadReport.create(2026, LocalDate.of(2026, 12, 3), listOf(
            stat("old", "2025-12-31", 9000), stat("first", "2026-01-01", 100),
            stat("last", "2026-12-03", 200), stat("future", "2026-12-04", 9000),
            stat("next year", "2027-01-01", 9000),
        ))
        assertEquals(300, report.totalTime)
        assertEquals(2, report.activeDays)
        assertEquals(listOf("last", "first"), report.books.map { it.name })
        assertEquals(100, report.monthlyTimes[0])
        assertEquals(200, report.monthlyTimes[11])
        assertEquals(LocalDate.of(2026, 12, 3).toEpochDay(), report.throughDate)
    }

    @Test
    fun `days aggregate across books and streaks span leap days and months`() {
        val report = AnnualReadReport.create(2024, LocalDate.of(2026, 1, 1), listOf(
            stat("A", "2024-02-28", 10), stat("B", "2024-02-28", 20),
            stat("A", "2024-02-29", 40), stat("A", "2024-03-01", 50),
            stat("A", "2024-03-03", 60), stat("C", "2024-03-04", 0),
            stat("D", "2024-03-05", -10),
        ))
        assertEquals(180, report.totalTime)
        assertEquals(4, report.activeDays)
        assertEquals(3, report.longestStreak)
        assertEquals(LocalDate.of(2024, 2, 28).toEpochDay(), report.firstReadDate)
        assertEquals(LocalDate.of(2024, 3, 3).toEpochDay(), report.busiestDate)
        assertEquals(60, report.busiestTime)
        assertEquals(70, report.monthlyTimes[1])
        assertEquals(110, report.monthlyTimes[2])
        assertEquals(160, report.books.first().readTime)
        assertEquals(2, report.books.size)
        assertEquals(LocalDate.of(2024, 12, 31).toEpochDay(), report.throughDate)
    }

    @Test
    fun `empty year has no invented reading history`() {
        val report = AnnualReadReport.create(2025, LocalDate.of(2026, 1, 1), emptyList())
        assertEquals(0, report.totalTime)
        assertEquals(0, report.activeDays)
        assertEquals(0, report.longestStreak)
        assertNull(report.firstReadDate)
        assertNull(report.busiestDate)
        assertTrue(report.books.isEmpty())
        assertEquals(List(12) { 0L }, report.monthlyTimes)
    }

    @Test
    fun `invitation starts in December and is suppressed after dismissal or generation`() {
        val november = LocalDate.of(2026, 11, 30)
        val december = november.plusDays(1)
        assertFalse(AnnualReadReportPolicy.shouldPrompt(november, 0, false))
        assertTrue(AnnualReadReportPolicy.shouldPrompt(december, 2025, false))
        assertFalse(AnnualReadReportPolicy.shouldPrompt(december, 2026, false))
        assertFalse(AnnualReadReportPolicy.shouldPrompt(december, 0, true))
        assertFalse(AnnualReadReportPolicy.shouldPrompt(LocalDate.of(2027, 1, 1), 2026, false))
        assertTrue(AnnualReadReportPolicy.shouldPrompt(LocalDate.of(2027, 12, 1), 2026, false))
    }

    @Test
    fun `current year becomes available in December while past reports remain available`() {
        assertEquals(2025, AnnualReadReportPolicy.latestYear(LocalDate.of(2026, 1, 1)))
        assertEquals(2025, AnnualReadReportPolicy.latestYear(LocalDate.of(2026, 11, 30)))
        assertEquals(2026, AnnualReadReportPolicy.latestYear(LocalDate.of(2026, 12, 1)))
        assertEquals(2026, AnnualReadReportPolicy.latestYear(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `tied book rankings and busiest days are deterministic`() {
        val report = AnnualReadReport.create(2026, LocalDate.of(2026, 12, 31), listOf(
            stat("B", "2026-01-02", 100), stat("A", "2026-01-01", 100),
        ))
        assertEquals(listOf("A", "B"), report.books.map { it.name })
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), report.busiestDate)
    }
}
