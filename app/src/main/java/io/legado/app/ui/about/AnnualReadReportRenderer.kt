package io.legado.app.ui.about

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.widget.NestedScrollView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.utils.dpToPx
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Journal pages share the app background and readable foreground colors. */
internal class AnnualReadReportStyle(private val context: Context) {
    private val palette = ThemePalette(context)
    private fun color(id: Int, monochrome: Int): Int =
        if (AppConfig.isEInkMode) monochrome else ContextCompat.getColor(context, id)

    val paper = palette.background
    val ink = palette.text
    val muted = palette.secondaryText
    val line = palette.divider
    val accent = color(R.color.annual_report_accent, Color.BLACK)
    val bar = color(R.color.annual_report_bar, Color.DKGRAY)
}

/** Native text and accessible chart controls, backed only by the saved report. */
internal class AnnualReadReportRenderer(
    private val context: Context,
    private val style: AnnualReadReportStyle,
) {
    private val pages = mutableListOf<NestedScrollView>()
    private val compact = context.resources.configuration.screenWidthDp < 350
    private val serif: Typeface = run {
        val fallback = Typeface.create("serif", Typeface.NORMAL)
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
        val font = File("/system/fonts/NotoSerifCJK-Regular.ttc")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && locale?.language == "zh" && font.isFile) {
            runCatching {
                val traditional = locale.script == "Hant" || locale.country in listOf("TW", "HK", "MO")
                Typeface.Builder(font).setTtcIndex(if (traditional) 3 else 2).build()
            }.getOrNull() ?: fallback
        } else fallback
    }

    fun render(report: AnnualReadReport): List<NestedScrollView> {
        pages.clear()
        cover(report)
        readingDays(report)
        val months = monthlyRhythm(report)
        if (report.books.isNotEmpty()) books(report)
        else text(months, R.string.annual_report_data_note, 11, style.muted, top = 28)
        return pages.toList()
    }

    private fun page(): LinearLayout {
        val content = column().apply { setPadding(dp(28), dp(18), dp(28), dp(16)) }
        pages.add(NestedScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(style.paper)
            addView(content, ViewGroup.LayoutParams(-1, -2))
        })
        return content
    }

    private fun cover(report: AnnualReadReport) = page().apply {
        val eyebrow = row()
        addView(eyebrow, params())
        text(eyebrow, R.string.annual_report_chapter_cover, 11, style.muted,
            layout = LinearLayout.LayoutParams(0, -2, 1f))
        text(eyebrow, "VOL. ${report.year}", 10, style.muted, serif = true,
            layout = LinearLayout.LayoutParams(-2, -2))
        title(this, R.string.annual_report_journal_hero,
            R.string.annual_report_journal_hero_accent, if (compact) 35 else 40)
        text(this, R.string.annual_report_journal_intro, 12, style.muted, top = 14)

        val art = FrameLayout(context).apply {
            minimumHeight = dp(190)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        addView(art, LinearLayout.LayoutParams(-1, 0, 1f))
        art.addView(TextView(context).apply {
            text = report.year.toString()
            typeface = Typeface.create("serif", Typeface.NORMAL)
            isSingleLine = true
            includeFontPadding = false
            textSize = if (compact) 96f else 112f
            setTextColor(style.ink)
            alpha = 0.06f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        art.addView(illustration(R.drawable.annual_report_book_art),
            FrameLayout.LayoutParams(-1, dp(230), Gravity.CENTER))
        text(this, R.string.annual_report_art_caption, 9, style.muted)
        divider(this, top = 20)
        text(this, R.string.annual_report_time_caption, 12, style.muted, top = 18)
        val time = if (report.totalTime in 1 until TimeUnit.MINUTES.toMillis(1)) {
            context.getString(R.string.annual_report_under_minute)
        } else duration(report.totalTime)
        numericText(this, time, if (compact) 44 else 50, top = 8)
        val readingLine = row()
        addView(readingLine, params(dp(4), top = 16))
        report.monthlyTimes.forEachIndexed { index, value ->
            readingLine.addView(View(context).apply {
                setBackgroundColor(if (index == report.monthlyTimes.indexOf(report.monthlyTimes.maxOrNull()))
                    style.accent else style.ink)
                alpha = if (value == 0L) 0.15f else 0.65f
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(3) })
        }
        text(this, context.getString(R.string.annual_report_period, report.year,
            LocalDate.ofEpochDay(report.throughDate).toString()), 10, style.muted, top = 12)
    }

    private fun readingDays(report: AnnualReadReport) = page().apply {
        eyebrow(this, R.string.annual_report_chapter_days)
        if (report.activeDays == 0) {
            text(this, R.string.annual_report_empty_title, 31, style.ink, top = 28, serif = true)
            addView(illustration(R.drawable.annual_report_book_art), params(dp(220), top = 24))
            text(this, R.string.annual_report_empty, 15, style.muted, top = 20)
            return@apply
        }
        title(this, R.string.annual_report_days_hero, R.string.annual_report_days_accent)
        val days = FrameLayout(context)
        addView(days, params(top = 24))
        days.addView(illustration(R.drawable.annual_report_orbit),
            FrameLayout.LayoutParams(dp(102), dp(102), Gravity.END or Gravity.CENTER_VERTICAL))
        days.addView(TextView(context).apply {
            textSize = 16f
            setTextColor(style.ink)
            text = numberSpans(context.getString(R.string.annual_report_days_count, report.activeDays), 80)
            setPadding(0, 0, dp(106), 0)
        }, FrameLayout.LayoutParams(-1, -2))
        text(this, R.string.annual_report_days_caption, 12, style.muted, top = 6)
        divider(this, top = 24)
        val stats = row()
        addView(stats, params(top = 22))
        val bookStat = column()
        stats.addView(bookStat, LinearLayout.LayoutParams(0, -2, 1f))
        text(bookStat, R.string.annual_report_books_label, 11, style.muted)
        numericText(bookStat, context.getString(R.string.annual_report_books_count, report.books.size), 36, top = 8)
        stats.addView(View(context).apply { setBackgroundColor(style.line) },
            LinearLayout.LayoutParams(dp(1), -1).apply { marginEnd = dp(22) })
        val streakStat = column()
        stats.addView(streakStat, LinearLayout.LayoutParams(0, -2, 1f))
        text(streakStat, R.string.annual_report_streak_label, 11, style.muted)
        numericText(streakStat, context.getString(R.string.annual_report_days_count, report.longestStreak), 36, top = 8)

        val moments = column()
        addView(moments, params(top = 28))
        report.firstReadDate?.let {
            moment(moments, R.string.annual_report_first_label,
                LocalDate.ofEpochDay(it).toString(), context.getString(R.string.annual_report_first_caption), false)
        }
        report.busiestDate?.let {
            moment(moments, R.string.annual_report_busiest_label,
                LocalDate.ofEpochDay(it).toString(), duration(report.busiestTime), true)
        }
        text(this, R.string.annual_report_days_closing, 14, style.muted, top = 24, serif = true)
    }

    private fun monthlyRhythm(report: AnnualReadReport): LinearLayout = page().apply {
        eyebrow(this, R.string.annual_report_chapter_months)
        title(this, R.string.annual_report_months_hero, R.string.annual_report_months_accent)
        text(this, R.string.annual_report_months_intro, 12, style.muted, top = 12)
        divider(this, top = 16)
        val peak = report.monthlyTimes.maxOrNull() ?: 0L
        val peakIndex = report.monthlyTimes.indexOf(peak).coerceAtLeast(0)
        if (peak > 0) {
            text(this, R.string.annual_report_peak_label, 11, style.muted, top = 12)
            val peakRow = row()
            addView(peakRow, params(top = 8))
            text(peakRow, numberSpans(context.getString(R.string.annual_report_month, peakIndex + 1), 52),
                14, style.ink, layout = LinearLayout.LayoutParams(0, -2, 1f))
            text(peakRow, duration(peak), 13, style.ink,
                layout = LinearLayout.LayoutParams(0, -2, 1f)).gravity = Gravity.END
        } else {
            text(this, R.string.annual_report_no_months, 16, style.ink, top = 22, serif = true)
        }
        val chartTitle = row()
        addView(chartTitle, params(top = 18))
        text(chartTitle, R.string.annual_report_monthly_label, 10, style.muted,
            layout = LinearLayout.LayoutParams(0, -2, 1f))
        text(chartTitle, R.string.annual_report_chart_hours, 10, style.muted,
            layout = LinearLayout.LayoutParams(-2, -2))
        monthlyChart(this, report.monthlyTimes, peakIndex)
        text(this, R.string.annual_report_months_closing, 16, style.ink, top = 12, serif = true)
            .gravity = Gravity.CENTER
    }

    private fun monthlyChart(parent: LinearLayout, times: List<Long>, selected: Int) {
        val maximum = times.maxOrNull() ?: 0L
        val hours = maximum.toDouble() / TimeUnit.HOURS.toMillis(1)
        val step = if (hours > 10) 5 else 1
        val ceiling = (ceil(hours / step) * step).coerceAtLeast(1.0)
        val chart = row().apply { gravity = Gravity.BOTTOM }
        parent.addView(chart, params(dp(152), top = 16))
        val axis = column()
        chart.addView(axis, LinearLayout.LayoutParams(dp(24), -1).apply { bottomMargin = dp(24) })
        listOf(ceiling, ceiling / 2, 0.0).forEachIndexed { index, value ->
            text(axis, String.format(Locale.getDefault(), if (value % 1 == 0.0) "%.0f" else "%.1f", value),
                9, style.muted, layout = LinearLayout.LayoutParams(-1, 0, 1f)).apply {
                gravity = when (index) { 0 -> Gravity.TOP; 1 -> Gravity.CENTER_VERTICAL; else -> Gravity.BOTTOM }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val detail = TextView(context).apply {
            tag = "annual_report_month_detail"
            textSize = 12f
            setTextColor(style.accent)
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val bars = mutableListOf<View>()
        val columns = mutableListOf<View>()
        fun select(index: Int) {
            bars.forEachIndexed { i, bar ->
                val drawable = ClipDrawable(rounded(if (i == index) style.accent else style.bar, 2),
                    Gravity.BOTTOM, ClipDrawable.VERTICAL)
                bar.background = drawable
                drawable.level = if (times[i] > 0) {
                    (times[i] / (ceiling * TimeUnit.HOURS.toMillis(1)) * 10_000)
                        .roundToInt().coerceIn(100, 10_000)
                } else 0
                columns[i].isSelected = i == index
            }
            detail.text = context.getString(R.string.annual_report_month_value,
                context.getString(R.string.annual_report_month, index + 1), duration(times[index]))
        }
        times.forEachIndexed { index, time ->
            val column = column().apply {
                tag = "annual_report_month_${index + 1}"
                isFocusable = true
                isClickable = true
                contentDescription = context.getString(R.string.annual_report_month_value,
                    context.getString(R.string.annual_report_month, index + 1), duration(time))
                setOnClickListener { select(index) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            columns.add(column)
            chart.addView(column, LinearLayout.LayoutParams(0, -1, 1f))
            val track = FrameLayout(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            column.addView(track, LinearLayout.LayoutParams(-1, 0, 1f))
            val bar = View(context)
            bars.add(bar)
            track.addView(bar, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
            text(column, (index + 1).toString(), 10, style.muted,
                layout = LinearLayout.LayoutParams(-1, dp(24))).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val quarters = row()
        parent.addView(quarters, params(top = 12).apply { marginStart = dp(24) })
        repeat(4) { index ->
            text(quarters, context.getString(R.string.annual_report_quarter, index + 1), 9,
                style.muted, layout = LinearLayout.LayoutParams(0, -2, 1f)).gravity = Gravity.CENTER
        }
        parent.addView(detail, params(top = 14))
        select(selected)
    }

    private fun books(report: AnnualReadReport) = page().apply {
        eyebrow(this, R.string.annual_report_chapter_books)
        title(this, R.string.annual_report_books_hero, R.string.annual_report_books_accent)
        text(this, R.string.annual_report_books_intro, 12, style.muted, top = 16)
        text(this, R.string.annual_report_book_list, 12, style.ink, top = 26)
        text(this, R.string.annual_report_books_hint, 10, style.muted, top = 5)
        divider(this, top = 12)
        val coverColors = intArrayOf(0xff344e3d.toInt(), 0xff343e51.toInt(),
            0xff626b48.toInt(), 0xff9c573c.toInt(), 0xff476569.toInt())
        report.books.take(10).forEachIndexed { index, book ->
            val bookRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
            addView(bookRow, params(top = 16))
            text(bookRow, String.format(Locale.getDefault(), "%02d", index + 1), 11, style.muted,
                serif = true, layout = LinearLayout.LayoutParams(dp(25), -2))
            val name = book.name.ifBlank { context.getString(R.string.read_record_untitled_book) }
            text(bookRow, name.codePoints().toArray().take(5)
                .joinToString("\n") { String(Character.toChars(it)) }, 9,
                if (AppConfig.isEInkMode) Color.BLACK else 0xfff4efdc.toInt(), serif = true,
                layout = LinearLayout.LayoutParams(dp(43), dp(65))).apply {
                gravity = Gravity.CENTER
                background = rounded(if (AppConfig.isEInkMode) Color.LTGRAY
                    else coverColors[index % coverColors.size], 1)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val info = column()
            bookRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            text(info, name, 16, style.ink, serif = true)
            text(info, context.getString(R.string.annual_report_book_rank, index + 1), 10, style.muted, top = 7)
            text(info, duration(book.readTime), 12, style.ink, top = 7)
            divider(this, top = 16)
        }
        text(this, R.string.annual_report_books_closing, 18, style.ink, top = 32, serif = true)
            .gravity = Gravity.CENTER
        text(this, report.year.toString(), 12, style.muted, top = 12, serif = true).gravity = Gravity.CENTER
        text(this, R.string.annual_report_data_note, 11, style.muted, top = 24)
    }

    private fun moment(parent: LinearLayout, @StringRes label: Int,
                       value: String, caption: String, accent: Boolean) {
        val row = row()
        parent.addView(row, params(top = if (parent.childCount == 0) 0 else 20))
        row.addView(View(context).apply { background = rounded(if (accent) style.accent else style.line, 4) },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(5); marginEnd = dp(12) })
        val content = column()
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        text(content, label, 11, style.muted)
        text(content, value, 14, style.ink, top = 7)
        text(content, caption, 12, if (accent) style.accent else style.muted, top = 6)
    }

    private fun title(parent: LinearLayout, @StringRes title: Int, @StringRes accentWord: Int,
                      size: Int = if (compact) 29 else 32,
                      color: Int = style.ink, accent: Int = style.accent) {
        val value = context.getString(title)
        val word = context.getString(accentWord)
        val styled = SpannableString(value)
        val start = value.indexOf(word)
        if (start >= 0) styled.setSpan(ForegroundColorSpan(accent), start, start + word.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text(parent, styled, size, color, top = 24, serif = true).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
            letterSpacing = 0.04f
        }
    }

    private fun eyebrow(parent: LinearLayout, @StringRes value: Int, color: Int = style.muted) {
        val row = row().apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, params())
        row.addView(View(context).apply { setBackgroundColor(color) },
            LinearLayout.LayoutParams(dp(17), dp(1)).apply { marginEnd = dp(8) })
        text(row, value, 11, color, layout = LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun numericText(parent: LinearLayout, value: String, size: Int, top: Int = 0) =
        text(parent, numberSpans(value, size), 13, style.ink, top = top)

    private fun numberSpans(value: String, size: Int) = SpannableString(value).apply {
        Regex("\\p{Nd}+").findAll(value).forEach { number ->
            val start = number.range.first
            val end = number.range.last + 1
            val pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                size.toFloat(), context.resources.displayMetrics).roundToInt()
            setSpan(AbsoluteSizeSpan(pixels), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TypefaceSpan("serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun text(parent: LinearLayout, @StringRes value: Int, size: Int, color: Int,
                     top: Int = 0, serif: Boolean = false, layout: LinearLayout.LayoutParams = params(top = top)) =
        text(parent, context.getString(value), size, color, top, serif, layout)

    private fun text(parent: LinearLayout, value: CharSequence, size: Int, color: Int,
                     top: Int = 0, serif: Boolean = false, layout: LinearLayout.LayoutParams = params(top = top)) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (serif) typeface = this@AnnualReadReportRenderer.serif
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            parent.addView(this, layout)
        }

    private fun illustration(drawable: Int) = ImageView(context).apply {
        setImageResource(drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (AppConfig.isEInkMode) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private fun divider(parent: LinearLayout, color: Int = style.line, top: Int = 0) {
        parent.addView(View(context).apply { setBackgroundColor(color) }, params(dp(1), top))
    }

    private fun duration(time: Long): String = if (time in 1 until TimeUnit.MINUTES.toMillis(1)) {
        context.getString(R.string.annual_report_under_minute)
    } else context.getString(R.string.annual_report_duration,
        time / TimeUnit.HOURS.toMillis(1), time / TimeUnit.MINUTES.toMillis(1) % 60)

    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun params(height: Int = -2, top: Int = 0) =
        LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(top) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = value.dpToPx()
}
