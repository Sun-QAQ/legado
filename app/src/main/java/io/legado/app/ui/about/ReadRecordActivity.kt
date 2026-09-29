package io.legado.app.ui.about

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getInt
import io.legado.app.utils.putInt
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class ReadRecordActivity : BaseActivity<ActivityReadRecordBinding>() {

    private enum class RecordFilter {
        ALL, TODAY, WEEK, EARLIER
    }

    private val adapter by lazy { RecordAdapter(this) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var recordFilter = RecordFilter.ALL
    private var searchKey = ""
    private var sortMode
        get() = if (LocalConfig.contains("readRecordSort")) {
            LocalConfig.getInt("readRecordSort")
        } else {
            2
        }
        set(value) {
            LocalConfig.putInt("readRecordSort", value)
        }
    private val filterViews by lazy {
        mapOf(
            RecordFilter.ALL to binding.tvFilterAll,
            RecordFilter.TODAY to binding.tvFilterToday,
            RecordFilter.WEEK to binding.tvFilterWeek,
            RecordFilter.EARLIER to binding.tvFilterEarlier,
        )
    }

    override val binding by viewBinding(ActivityReadRecordBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initAllTime()
        initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read_record, menu)
        initSearchView(menu.findItem(R.id.menu_search))
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_record)?.isChecked = AppConfig.enableReadRecord
        when (sortMode) {
            1 -> menu.findItem(R.id.menu_sort_read_long)?.isChecked = true
            2 -> menu.findItem(R.id.menu_sort_read_time)?.isChecked = true
            else -> menu.findItem(R.id.menu_sort_name)?.isChecked = true
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_name -> {
                sortMode = 0
                item.isChecked = true
                initData()
            }

            R.id.menu_sort_read_long -> {
                sortMode = 1
                item.isChecked = true
                initData()
            }

            R.id.menu_sort_read_time -> {
                sortMode = 2
                item.isChecked = true
                initData()
            }

            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        applyTheme()
        binding.recyclerView.apply {
            adapter = this@ReadRecordActivity.adapter
            applyNavigationBarPadding()
            setEdgeEffectColor(accentColor)
        }
        binding.tvClearAll.setOnClickListener {
            alert(R.string.delete, R.string.sure_del) {
                yesButton {
                    appDb.readRecordDao.clear()
                    initAllTime()
                    initData()
                }
                noButton()
            }
        }
        filterViews.forEach { (filter, view) ->
            view.setOnClickListener {
                if (recordFilter != filter) {
                    recordFilter = filter
                    updateFilterStyle()
                    initData()
                }
            }
        }
        updateFilterStyle()
    }

    private fun initSearchView(searchMenu: MenuItem) {
        (searchMenu.actionView as? SearchView)?.apply {
            applyTint(primaryTextColor)
            maxWidth = resources.displayMetrics.widthPixels
            isSubmitButtonEnabled = true
            queryHint = getString(R.string.search)
            setOnCloseListener {
                initData("")
                false
            }
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    clearFocus()
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    initData(newText.orEmpty())
                    return false
                }
            })
        }
    }

    private fun applyTheme() {
        val palette = ThemePalette(this)
        val accent = accentColor
        val summaryText = if (ColorUtils.isColorLight(accent)) Color.BLACK else Color.WHITE
        val endColor = ColorUtils.blendColors(accent, palette.background, 0.28f)
        binding.readSummary.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(accent, endColor),
        ).apply {
            cornerRadius = 22.dpToPx().toFloat()
        }
        binding.tvSummaryTitle.setTextColor(ColorUtils.withAlpha(summaryText, 0.78f))
        binding.tvReadingTime.setTextColor(summaryText)
        binding.tvWeekReadingTime.setTextColor(ColorUtils.withAlpha(summaryText, 0.78f))
        binding.tvClearAll.setTextColor(ColorUtils.withAlpha(summaryText, 0.84f))
        binding.tvRecordSectionTitle.setTextColor(palette.text)
        binding.tvRecordCount.setTextColor(palette.secondaryText)
    }

    private fun updateFilterStyle() {
        val palette = ThemePalette(this)
        val accent = accentColor
        val selectedText = if (ColorUtils.isColorLight(accent)) Color.BLACK else Color.WHITE
        filterViews.forEach { (filter, view) ->
            val selected = filter == recordFilter
            view.background = roundedBackground(
                if (selected) accent else palette.surface,
                12,
            )
            view.setTextColor(if (selected) selectedText else palette.secondaryText)
        }
    }

    private fun initAllTime() {
        lifecycleScope.launch {
            val times = withContext(IO) {
                val weekStart = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                appDb.readRecordDao.allTime to (appDb.readRecordDao.readTimeSince(weekStart) ?: 0L)
            }
            binding.tvReadingTime.text = formatHoursAndMinutes(times.first)
            binding.tvWeekReadingTime.text = getString(
                R.string.read_record_week_summary,
                formatHoursAndMinutes(times.second),
            )
        }
    }

    private fun initData(query: String = searchKey) {
        searchKey = query
        lifecycleScope.launch {
            val readRecords = withContext(IO) {
                sortRecords(appDb.readRecordDao.search(searchKey))
            }
            val filteredRecords = filterRecords(readRecords)
            adapter.setItems(filteredRecords)
            binding.tvRecordCount.text = getString(
                R.string.read_record_count,
                filteredRecords.size,
            )
        }
    }

    private fun sortRecords(records: List<ReadRecordShow>): List<ReadRecordShow> {
        return when (sortMode) {
            1 -> records.sortedByDescending { it.readTime }
            2 -> records.sortedByDescending { it.lastRead }
            else -> records.sortedWith { o1, o2 -> o1.bookName.cnCompare(o2.bookName) }
        }
    }

    private fun filterRecords(records: List<ReadRecordShow>): List<ReadRecordShow> {
        val todayStart = startOfToday()
        val weekStart = todayStart - TimeUnit.DAYS.toMillis(6)
        return records.filter { record ->
            when (recordFilter) {
                RecordFilter.ALL -> true
                RecordFilter.TODAY -> record.lastRead >= todayStart
                RecordFilter.WEEK -> record.lastRead >= weekStart
                RecordFilter.EARLIER -> record.lastRead in 1L until weekStart
            }
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun formatLastRead(lastRead: Long): String {
        if (lastRead <= 0) return ""
        val todayStart = startOfToday()
        return when {
            lastRead >= todayStart -> getString(
                R.string.read_record_today_at,
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(lastRead),
            )
            lastRead >= todayStart - TimeUnit.DAYS.toMillis(1) -> {
                getString(R.string.read_record_yesterday)
            }
            lastRead >= todayStart - TimeUnit.DAYS.toMillis(7) -> {
                getString(
                    R.string.read_record_days_ago,
                    ((todayStart - lastRead) / TimeUnit.DAYS.toMillis(1) + 1).toInt(),
                )
            }
            else -> dateFormat.format(lastRead)
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.dpToPx().toFloat()
        }
    }

    inner class RecordAdapter(context: Context) :
        RecyclerAdapter<ReadRecordShow, ItemReadRecordBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemReadRecordBinding {
            return ItemReadRecordBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadRecordBinding,
            item: ReadRecordShow,
            payloads: MutableList<Any>,
        ) {
            val palette = ThemePalette(context)
            binding.apply {
                root.background = roundedBackground(palette.surface, 16)
                tvBookInitial.background = roundedBackground(palette.accentContainer, 12)
                tvBookInitial.setTextColor(context.accentColor)
                val displayName = item.bookName.ifBlank { getString(R.string.read_record_untitled_book) }
                tvBookInitial.text = displayName.firstOrNull()?.toString().orEmpty()
                tvBookName.setTextColor(context.primaryTextColor)
                tvBookName.text = displayName
                tvReadingTimeTag.setTextColor(context.secondaryTextColor)
                tvReadingTimeTag.text = getString(R.string.reading_time_tag) + formatDuring(item.readTime)
                tvLastReadTime.setTextColor(context.secondaryTextColor)
                tvLastReadTime.text = formatLastRead(item.lastRead)
                tvRemove.setColorFilter(context.secondaryTextColor)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadRecordBinding) {
            binding.apply {
                root.setOnClickListener {
                    val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
                    lifecycleScope.launch {
                        val book = withContext(IO) {
                            appDb.bookDao.findByName(item.bookName).firstOrNull()
                        }
                        if (book == null) {
                            SearchActivity.start(this@ReadRecordActivity, item.bookName)
                        } else {
                            startActivityForBook(book)
                        }
                    }
                }
                tvRemove.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { item ->
                        sureDelAlert(item)
                    }
                }
            }
        }

        private fun sureDelAlert(item: ReadRecordShow) {
            alert(R.string.delete) {
                setMessage(getString(R.string.sure_del_any, item.bookName))
                yesButton {
                    appDb.readRecordDao.deleteByName(item.bookName)
                    initAllTime()
                    initData()
                }
                noButton()
            }
        }
    }

    fun formatDuring(mss: Long): String {
        val days = mss / (1000 * 60 * 60 * 24)
        val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
        val seconds = mss % (1000 * 60) / 1000
        val d = if (days > 0) "${days}天" else ""
        val h = if (hours > 0) "${hours}小时" else ""
        val m = if (minutes > 0) "${minutes}分钟" else ""
        val s = if (seconds > 0) "${seconds}秒" else ""
        return "$d$h$m$s".ifBlank { "0秒" }
    }

    private fun formatHoursAndMinutes(mss: Long): String {
        val hours = mss / TimeUnit.HOURS.toMillis(1)
        val minutes = mss % TimeUnit.HOURS.toMillis(1) / TimeUnit.MINUTES.toMillis(1)
        return "${hours}小时${minutes}分钟"
    }
}
