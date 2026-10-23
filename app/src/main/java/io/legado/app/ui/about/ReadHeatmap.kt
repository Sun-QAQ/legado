package io.legado.app.ui.about

import io.legado.app.data.entities.ReadStat
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Three trailing calendar months, with weeks laid out Monday to Sunday. */
internal class ReadHeatmap(today: LocalDate, stats: List<ReadStat>) {

    data class Day(val date: LocalDate, val readTime: Long) {
        val level: Int
            get() = when {
                readTime <= 0 -> 0
                readTime < TimeUnit.MINUTES.toMillis(30) -> 1
                readTime < TimeUnit.HOURS.toMillis(1) -> 2
                readTime < TimeUnit.HOURS.toMillis(2) -> 3
                else -> 4
            }
    }

    val start: LocalDate = startDate(today)
    val gridStart: LocalDate = start.minusDays(start.dayOfWeek.value - 1L)
    private val timesByDate = stats.groupBy { it.date }
        .mapValues { (_, entries) -> entries.sumOf { it.readTime.coerceAtLeast(0) } }
    val days: List<Day> = generateSequence(start) { it.plusDays(1) }
        .takeWhile { it <= today }
        .map { Day(it, timesByDate[it.toEpochDay()] ?: 0L) }
        .toList()
    val columnCount = ((today.toEpochDay() - gridStart.toEpochDay()) / 7 + 1).toInt()
    val totalTime: Long = days.sumOf { it.readTime }
    val activeDays: Int = days.count { it.readTime > 0 }

    fun cellIndex(day: Day): Int = (day.date.toEpochDay() - gridStart.toEpochDay()).toInt()

    companion object {
        fun startDate(today: LocalDate): LocalDate = today.minusMonths(3).plusDays(1)
    }
}
