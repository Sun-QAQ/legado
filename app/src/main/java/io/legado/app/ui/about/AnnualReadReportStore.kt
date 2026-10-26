package io.legado.app.ui.about

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import io.legado.app.data.appDb
import java.io.File
import java.time.LocalDate

/** Private local snapshots remain available after December and across app restarts. */
internal class AnnualReadReportStore(context: Context) {
    private val directory = File(context.filesDir, "annual_read_reports")
    private val gson = Gson()

    fun hasReport(year: Int): Boolean = file(year).exists()

    fun years(today: LocalDate): List<Int> {
        val saved = directory.listFiles().orEmpty().mapNotNull {
            it.name.removeSuffix(".json").toIntOrNull()
        }
        val recorded = appDb.readStatDao.recordedDates.map { LocalDate.ofEpochDay(it).year }
        val latest = AnnualReadReportPolicy.latestYear(today)
        return (saved + recorded + listOfNotNull(today.year.takeIf { today.monthValue == 12 }))
            .filter { it in 1..latest }.distinct().sortedDescending()
    }

    fun load(year: Int): AnnualReadReport? {
        if (!hasReport(year)) return null
        return runCatching {
            AtomicFile(file(year)).openRead().bufferedReader().use {
                gson.fromJson(it, AnnualReadReport::class.java)
            }.also {
                require(it.year == year && it.monthlyTimes.size == 12)
                require(LocalDate.ofEpochDay(it.throughDate).year == year)
                require(it.books.all { book -> book.readTime > 0 })
            }
        }.getOrNull()
    }

    fun generate(year: Int, today: LocalDate): AnnualReadReport {
        require(year in 1..AnnualReadReportPolicy.latestYear(today))
        val end = minOf(today, LocalDate.of(year, 12, 31))
        val report = AnnualReadReport.create(
            year, today, appDb.readStatDao.getStats(
                LocalDate.of(year, 1, 1).toEpochDay(), end.toEpochDay(),
            ),
        )
        save(report)
        return report
    }

    fun save(report: AnnualReadReport) {
        check(directory.isDirectory || directory.mkdirs())
        val target = AtomicFile(file(report.year))
        val output = target.startWrite()
        try {
            output.write(gson.toJson(report).toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (e: Exception) {
            target.failWrite(output)
            throw e
        }
    }

    private fun file(year: Int) = File(directory, "$year.json")
}
