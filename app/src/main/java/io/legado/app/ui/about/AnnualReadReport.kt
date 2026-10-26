package io.legado.app.ui.about

import io.legado.app.data.entities.ReadStat
import java.time.LocalDate

/** Only dated reading statistics belong in an annual report, never lifetime totals. */
internal data class AnnualReadReport(
    val year: Int,
    val throughDate: Long,
    val generatedAt: Long,
    val totalTime: Long,
    val activeDays: Int,
    val longestStreak: Int,
    val firstReadDate: Long?,
    val busiestDate: Long?,
    val busiestTime: Long,
    val monthlyTimes: List<Long>,
    val books: List<Book>,
) {
    data class Book(val name: String, val readTime: Long)

    companion object {
        fun create(year: Int, today: LocalDate, stats: List<ReadStat>): AnnualReadReport {
            require(year in 1..today.year)
            val start = LocalDate.of(year, 1, 1)
            val end = minOf(today, LocalDate.of(year, 12, 31))
            val entries = stats.filter {
                it.date in start.toEpochDay()..end.toEpochDay() && it.readTime > 0
            }
            val days = entries.groupBy { it.date }.toSortedMap()
                .mapValues { (_, values) -> values.sumOf { it.readTime } }
            var streak = 0
            var longest = 0
            var previous: Long? = null
            days.keys.forEach { date ->
                streak = if (previous == date - 1) streak + 1 else 1
                longest = maxOf(longest, streak)
                previous = date
            }
            val busiest = days.maxByOrNull { it.value }
            val months = MutableList(12) { 0L }
            entries.forEach {
                val month = LocalDate.ofEpochDay(it.date).monthValue - 1
                months[month] += it.readTime
            }
            val books = entries.groupBy { it.bookName }.map { (name, values) ->
                Book(name, values.sumOf { it.readTime })
            }.sortedWith(compareByDescending<Book> { it.readTime }.thenBy { it.name })
            return AnnualReadReport(
                year, end.toEpochDay(), System.currentTimeMillis(), days.values.sum(),
                days.size, longest, days.keys.firstOrNull(), busiest?.key,
                busiest?.value ?: 0L, months, books,
            )
        }
    }
}

internal object AnnualReadReportPolicy {
    fun latestYear(today: LocalDate): Int =
        if (today.monthValue == 12) today.year else today.year - 1

    fun shouldPrompt(today: LocalDate, promptedYear: Int, hasReport: Boolean): Boolean =
        today.monthValue == 12 && promptedYear != today.year && !hasReport
}
