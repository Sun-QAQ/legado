package io.legado.app.ui.about

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAnnualReadReportBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AnnualReadReportActivity : BaseActivity<ActivityAnnualReadReportBinding>() {
    override val binding by viewBinding(ActivityAnnualReadReportBinding::inflate)
    private val store by lazy { AnnualReadReportStore(this) }
    private val palette by lazy { ThemePalette(this) }
    private var years = emptyList<Int>()
    private var selectedYear = 0
    private var loading = false
    private var reportJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.scrollView.applyNavigationBarPadding()
        selectedYear = savedInstanceState?.getInt("year") ?: intent.getIntExtra("year", 0)
        loadReport()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.annual_read_report, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_report_year)?.isEnabled = !loading && years.isNotEmpty()
        menu.findItem(R.id.menu_report_refresh)?.isVisible = !loading && selectedYear in years
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_report_year -> {
                alert(R.string.annual_report_choose_year) {
                    items(years.map { getString(R.string.annual_report_year, it) }) { _, index ->
                        selectedYear = years[index]
                        loadReport()
                    }
                }
                return true
            }
            R.id.menu_report_refresh -> {
                alert(R.string.annual_report_refresh, R.string.annual_report_refresh_confirm) {
                    okButton { loadReport(refresh = true) }
                    cancelButton()
                }
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun loadReport(refresh: Boolean = false) {
        reportJob?.cancel()
        reportJob = lifecycleScope.launch {
            loading = true
            binding.progress.isVisible = true
            binding.content.removeAllViews()
            invalidateOptionsMenu()
            try {
                val today = LocalDate.now()
                val (availableYears, report) = withContext(IO) {
                    val available = store.years(today)
                    val year = selectedYear.takeIf { it in available } ?: available.firstOrNull()
                    available to year?.let {
                        if (refresh) store.generate(it, today)
                        else store.load(it) ?: store.generate(it, today)
                    }
                }
                years = availableYears
                selectedYear = report?.year ?: 0
                if (report == null) {
                    showMessage(R.string.annual_report_not_ready)
                } else {
                    renderReport(report)
                }
                binding.scrollView.scrollTo(0, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage(R.string.annual_report_error)
                addText(binding.content, getString(R.string.annual_report_retry), 16, palette.accent)
                    .setOnClickListener { loadReport(refresh) }
            } finally {
                loading = false
                binding.progress.isVisible = false
                invalidateOptionsMenu()
            }
        }
    }

    private fun showMessage(message: Int) {
        binding.content.removeAllViews()
        addText(binding.content, getString(message), 17, palette.text)
    }

    internal fun renderReport(report: AnnualReadReport) {
        binding.content.removeAllViews()
        binding.titleBar.subtitle = getString(R.string.annual_report_year, report.year)
        val heroText = if (ColorUtils.isColorLight(palette.accent)) Color.BLACK else Color.WHITE
        card(palette.accent).apply {
            addText(this, getString(R.string.annual_report_eyebrow, report.year), 15, heroText)
            addText(this, getString(R.string.annual_report_hero), 30, heroText, bold = true)
            addText(this, formatTime(report.totalTime), 26, heroText, bold = true)
            addText(this, getString(R.string.annual_report_through,
                LocalDate.ofEpochDay(report.throughDate).toString()), 13, heroText)
        }
        if (report.activeDays == 0) {
            card().apply {
                addText(this, getString(R.string.annual_report_empty_title), 21, palette.text, true)
                addText(this, getString(R.string.annual_report_empty), 15, palette.secondaryText)
            }
        } else {
            card().apply {
                addText(this, getString(R.string.annual_report_summary_title), 21, palette.text, true)
                addText(this, getString(R.string.annual_report_summary,
                    report.books.size, report.activeDays, report.longestStreak), 18, palette.text)
                addText(this, getString(R.string.annual_report_first_day,
                    LocalDate.ofEpochDay(checkNotNull(report.firstReadDate)).toString()),
                    15, palette.secondaryText)
                addText(this, getString(R.string.annual_report_busiest_day,
                    LocalDate.ofEpochDay(checkNotNull(report.busiestDate)).toString(),
                    formatTime(report.busiestTime)), 15, palette.secondaryText)
            }
        }
        card().apply {
            addText(this, getString(R.string.annual_report_months), 21, palette.text, true)
            val max = report.monthlyTimes.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            report.monthlyTimes.forEachIndexed { index, time ->
                val month = getString(R.string.annual_report_month, index + 1)
                val row = LinearLayout(this@AnnualReadReportActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = 32.dpToPx()
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = getString(R.string.annual_report_month_value, month, formatTime(time))
                }
                addView(row, LinearLayout.LayoutParams(-1, -2))
                TextView(this@AnnualReadReportActivity).apply {
                    text = month
                    textSize = 13f
                    setTextColor(palette.secondaryText)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    row.addView(this, LinearLayout.LayoutParams(42.dpToPx(), -2))
                }
                val track = LinearLayout(this@AnnualReadReportActivity)
                row.addView(track, LinearLayout.LayoutParams(0, 10.dpToPx(), 1f))
                track.background = rounded(palette.divider, 5)
                if (time > 0) {
                    val proportion = (time.toDouble() / max).toFloat().coerceIn(0.01f, 1f)
                    track.weightSum = 1f
                    track.addView(View(this@AnnualReadReportActivity).apply {
                        background = rounded(palette.accent, 5)
                    }, LinearLayout.LayoutParams(0, -1, proportion))
                }
                TextView(this@AnnualReadReportActivity).apply {
                    text = formatTime(time)
                    textSize = 12f
                    gravity = android.view.Gravity.END
                    setTextColor(palette.secondaryText)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    row.addView(this, LinearLayout.LayoutParams(112.dpToPx(), -2))
                }
            }
        }
        if (report.books.isNotEmpty()) {
            card().apply {
                addText(this, getString(R.string.annual_report_books), 21, palette.text, true)
                addText(this, getString(R.string.annual_report_books_hint), 13, palette.secondaryText)
                report.books.take(10).forEachIndexed { index, book ->
                    addText(this, getString(R.string.annual_report_book_name, index + 1,
                        book.name.ifBlank { getString(R.string.read_record_untitled_book) }),
                        17, palette.text, true)
                    addText(this, formatTime(book.readTime), 14, palette.accent)
                }
            }
        }
        addText(binding.content, getString(R.string.annual_report_data_note), 13, palette.secondaryText)
    }

    private fun card(color: Int = palette.surface): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 18.dpToPx(), 20.dpToPx(), 18.dpToPx())
            background = rounded(color, 22)
            binding.content.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 18.dpToPx()
            })
        }

    private fun addText(parent: LinearLayout, value: String, size: Int, color: Int,
                        bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.dpToPx().toFloat()
    }

    private fun formatTime(time: Long): String {
        if (time in 1 until TimeUnit.MINUTES.toMillis(1)) {
            return getString(R.string.annual_report_under_minute)
        }
        return getString(R.string.annual_report_duration,
            time / TimeUnit.HOURS.toMillis(1), time / TimeUnit.MINUTES.toMillis(1) % 60)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("year", selectedYear)
        super.onSaveInstanceState(outState)
    }
}
