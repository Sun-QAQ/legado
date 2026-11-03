package io.legado.app.ui.about

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAnnualReadReportBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AnnualReadReportActivity : BaseActivity<ActivityAnnualReadReportBinding>(imageBg = false) {
    override val binding by viewBinding(ActivityAnnualReadReportBinding::inflate)
    private val store by lazy { AnnualReadReportStore(this) }
    internal val reportStyle by lazy { AnnualReadReportStyle(this) }
    private var years = emptyList<Int>()
    private var selectedYear = 0
    private var loading = false
    private var reportJob: Job? = null
    internal val pages = mutableListOf<NestedScrollView>()
    private var restoredPage = 0

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateNavigation(position)
        }
    }

    private fun updateNavigation(position: Int) {
        if (pages.isEmpty()) return
        binding.pageIndicator.text = getString(R.string.annual_report_page, position + 1, pages.size)
        applyPageTheme()
        repeat(binding.pageDots.childCount) { index ->
            val target = binding.pageDots.getChildAt(index) as FrameLayout
            target.isSelected = index == position
            val dot = target.getChildAt(0)
            dot.layoutParams = (dot.layoutParams as FrameLayout.LayoutParams).apply {
                width = (if (index == position) 15 else 4).dpToPx()
            }
            dot.background = GradientDrawable().apply {
                setColor(reportStyle.ink)
                cornerRadius = 4.dpToPx().toFloat()
            }
            dot.alpha = if (index == position) 1f else 0.25f
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.pageNavigation.applyNavigationBarPadding(withInitialPadding = true)
        applyPageTheme()
        binding.scrollView.applyNavigationBarPadding()
        restoredPage = savedInstanceState?.getInt("page") ?: 0
        binding.reportPager.registerOnPageChangeCallback(pageCallback)
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
        applyPageTheme()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_report_year -> {
                alert(R.string.annual_report_choose_year) {
                    items(years.map { getString(R.string.annual_report_year, it) }) { _, index ->
                        restoredPage = 0
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
            binding.reportPager.isVisible = false
            binding.scrollView.isVisible = false
            binding.pageNavigation.isVisible = false
            invalidateOptionsMenu()
            try {
                val today = LocalDate.now()
                val (availableYears, report, covers) = withContext(IO) {
                    val available = store.years(today)
                    val year = selectedYear.takeIf { it in available } ?: available.firstOrNull()
                    val report = year?.let {
                        if (refresh) store.generate(it, today)
                        else store.load(it) ?: store.generate(it, today)
                    }
                    Triple(available, report, report?.let { AnnualReadReportCovers.load(it) }.orEmpty())
                }
                years = availableYears
                selectedYear = report?.year ?: 0
                if (report == null) {
                    showMessage(R.string.annual_report_not_ready)
                } else {
                    renderReport(report, covers)
                }
                binding.scrollView.scrollTo(0, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage(R.string.annual_report_error)
                addMessage(getString(R.string.annual_report_retry), reportStyle.accent)
                    .setOnClickListener { loadReport(refresh) }
            } finally {
                loading = false
                binding.progress.isVisible = false
                invalidateOptionsMenu()
            }
        }
    }

    private fun showMessage(message: Int) {
        binding.reportPager.isVisible = false
        binding.pageNavigation.isVisible = false
        binding.scrollView.isVisible = true
        binding.content.removeAllViews()
        applyPageTheme()
        addMessage(getString(message), reportStyle.ink)
    }

    internal fun renderReport(report: AnnualReadReport, covers: Map<String, AnnualReadReportCover> = emptyMap()) {
        binding.reportPager.adapter = null
        pages.clear()
        binding.content.removeAllViews()
        binding.scrollView.isVisible = false
        selectedYear = report.year
        binding.titleBar.subtitle = null
        pages.addAll(AnnualReadReportRenderer(this, reportStyle, covers).render(report))
        createPageDots()
        binding.reportPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(android.widget.FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as ViewGroup
                container.removeAllViews()
                val page = pages[position]
                (page.parent as? ViewGroup)?.removeView(page)
                container.addView(page, ViewGroup.LayoutParams(-1, -1))
            }

            override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                (holder.itemView as ViewGroup).removeAllViews()
            }
        }
        binding.reportPager.isVisible = true
        binding.pageNavigation.isVisible = true
        val page = restoredPage.coerceIn(0, pages.lastIndex)
        binding.reportPager.setCurrentItem(page, false)
        updateNavigation(page)
        restoredPage = 0
    }

    private fun createPageDots() {
        binding.pageDots.removeAllViews()
        pages.indices.forEach { index ->
            val dot = View(this)
            val target = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.annual_report_open_chapter, index + 1, pages.size)
                addView(dot, FrameLayout.LayoutParams(4.dpToPx(), 4.dpToPx(), Gravity.CENTER))
                setOnClickListener { binding.reportPager.setCurrentItem(index, true) }
            }
            binding.pageDots.addView(target, LinearLayout.LayoutParams(36.dpToPx(), 48.dpToPx()))
        }
    }

    private fun applyPageTheme() {
        val background = reportStyle.paper
        val foreground = reportStyle.ink
        val secondary = reportStyle.muted
        binding.root.setBackgroundColor(background)
        binding.titleBar.apply {
            setBackgroundColor(background)
            elevation = 0f
            setTitleTextColor(foreground)
            setColorFilter(foreground)
        }
        binding.pageNavigation.setBackgroundColor(background)
        binding.pageIndicator.setTextColor(foreground)
        binding.reportSignature.setTextColor(secondary)
        binding.titleBar.menu.findItem(R.id.menu_report_year)?.let { item ->
            val year = if (selectedYear > 0) getString(R.string.annual_report_year, selectedYear)
                else getString(R.string.annual_report_choose_year)
            item.title = SpannableString(year).apply {
                setSpan(ForegroundColorSpan(foreground), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        setStatusBarColorAuto(background, true, fullScreen)
        setNavigationBarColorAuto(background)
    }

    private fun addMessage(value: String, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(color)
        setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
        binding.content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onDestroy() {
        binding.reportPager.unregisterOnPageChangeCallback(pageCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("year", selectedYear)
        outState.putInt("page", binding.reportPager.currentItem)
        super.onSaveInstanceState(outState)
    }
}
