package io.legado.app.ui.about

import java.time.LocalDate
import java.time.ZoneId

internal enum class ReadRecordFilter(private val days: Long?) {
    TODAY(1),
    WEEK(7),
    MONTH(30),
    ALL(null);

    fun startTime(today: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val rangeDays = days ?: return Long.MIN_VALUE
        return today.minusDays(rangeDays - 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}
