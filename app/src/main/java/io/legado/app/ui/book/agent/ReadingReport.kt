package io.legado.app.ui.book.agent

import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 生成阅读周报/月报数据
 */
object ReadingReport {

    fun build(period: String): String {
        val today = LocalDate.now()
        val start = when (period) {
            "month" -> today.withDayOfMonth(1)
            else -> today.with(DayOfWeek.MONDAY)
        }
        val startDay = start.toEpochDay()
        val endDay = today.toEpochDay()
        val stats = appDb.readStatDao.getStats(startDay, endDay)
        val books = stats.groupBy { it.bookName }
            .map { (name, list) ->
                mapOf(
                    "bookName" to name,
                    "readTimeMs" to list.sumOf { it.readTime }
                )
            }
            .sortedByDescending { it["readTimeMs"] as Long }
        val daily = stats.groupBy { it.date }
            .map { (day, list) ->
                mapOf(
                    "date" to LocalDate.ofEpochDay(day).toString(),
                    "readTimeMs" to list.sumOf { it.readTime }
                )
            }
            .sortedBy { it["date"] as String }
        val readInPeriod = if (stats.isEmpty()) {
            val startMillis = start.atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            appDb.readRecordDao.all
                .filter { it.lastRead >= startMillis }
                .groupBy { it.bookName }
                .map { (name, list) ->
                    mapOf(
                        "bookName" to name,
                        "cumulativeReadTimeMs" to list.sumOf { it.readTime }
                    )
                }
                .sortedByDescending { it["cumulativeReadTimeMs"] as Long }
        } else {
            emptyList<Map<String, Any>>()
        }
        return GSON.toJson(
            mapOf(
                "period" to period,
                "startDate" to start.toString(),
                "endDate" to today.toString(),
                "totalReadTimeMs" to stats.sumOf { it.readTime },
                "readDays" to stats.map { it.date }.distinct().size,
                "dailyReadTimeMs" to daily,
                "books" to books,
                "statsAvailable" to stats.isNotEmpty(),
                "booksReadInPeriod" to readInPeriod
            )
        )
    }
}
