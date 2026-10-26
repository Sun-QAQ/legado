package io.legado.app.ui.about

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadSession
import io.legado.app.databinding.ActivityReadTimelineBinding
import io.legado.app.databinding.ItemReadSessionBinding
import io.legado.app.help.globalExecutor
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.model.ReadSessionStorage
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReadTimelineActivity : BaseActivity<ActivityReadTimelineBinding>() {
    override val binding by viewBinding(ActivityReadTimelineBinding::inflate)
    private val palette by lazy { ThemePalette(this) }
    private val buttonTextColor by lazy {
        if (ColorUtils.isColorLight(palette.accent)) Color.BLACK else Color.WHITE
    }
    private val adapter by lazy { SessionAdapter(this) }
    private var date = LocalDate.now()
    private var loadJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        date = LocalDate.ofEpochDay(savedInstanceState?.getLong("date")
            ?: intent.getLongExtra("date", date.toEpochDay()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.summary.setTextColor(palette.text)
        binding.notice.setTextColor(palette.secondaryText)
        binding.empty.setTextColor(palette.secondaryText)
        listOf(binding.previousDay, binding.nextDay).forEach {
            it.backgroundTintList = null
            it.background = dateNavigationBackground(palette.surface)
            it.imageTintList = ColorStateList.valueOf(palette.text)
        }
        binding.selectDate.backgroundTintList = null
        binding.selectDate.background = dateNavigationBackground(palette.accentContainer)
        binding.selectDate.setTextColor(palette.text)
        TextViewCompat.setCompoundDrawableTintList(binding.selectDate, ColorStateList.valueOf(palette.secondaryText))
        binding.previousDay.setOnClickListener { selectDate(date.minusDays(1)) }
        binding.nextDay.setOnClickListener { selectDate(date.plusDays(1)) }
        binding.selectDate.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                selectDate(LocalDate.of(year, month + 1, day))
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        selectDate(date)
    }

    private fun dateNavigationBackground(fill: Int): RippleDrawable {
        val radius = 14.dpToPx().toFloat()
        val surface = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(1.dpToPx(), palette.divider)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(palette.accentContainerPressed), surface, mask)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, R.id.menu_timeline_today, 0, R.string.read_timeline_today)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_timeline_today) {
            selectDate(LocalDate.now())
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun selectDate(selected: LocalDate) {
        date = selected
        binding.selectDate.text = date.toString()
        binding.selectDate.contentDescription = getString(R.string.read_timeline_choose_date, date.toString())
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            // Drain the reader's final checkpoint before allowing deletion of an older snapshot.
            withContext(IO) { globalExecutor.submit {}.get() }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    appDb.readSessionDao.observeDay(selected.toEpochDay()).collect { renderSessions(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    binding.empty.isVisible = true
                    binding.empty.setText(R.string.read_timeline_error)
                }
            }
        }
    }

    internal fun renderSessions(sessions: List<ReadSession>) {
        adapter.setItems(sessions)
        binding.empty.setText(R.string.read_timeline_empty)
        binding.empty.isVisible = sessions.isEmpty()
        binding.summary.text = getString(R.string.read_timeline_summary, sessions.size,
            sessions.map { it.bookUrl }.distinct().size, duration(sessions.sumOf { it.readTime }))
    }

    private fun duration(time: Long): String = getString(R.string.read_timeline_duration,
        time / 3_600_000, time / 60_000 % 60, time / 1_000 % 60)

    private fun timeRange(session: ReadSession): String {
        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val format = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        val end = if (Instant.ofEpochMilli(session.endTime).atZone(zone).toLocalDate().toEpochDay() > session.date) {
            "24:00:00"
        } else format.format(Instant.ofEpochMilli(session.endTime))
        return getString(R.string.read_timeline_range, format.format(Instant.ofEpochMilli(session.startTime)), end)
    }

    inner class SessionAdapter(context: Context) : RecyclerAdapter<ReadSession, ItemReadSessionBinding>(context) {
        override fun getViewBinding(parent: ViewGroup) = ItemReadSessionBinding.inflate(inflater, parent, false)

        override fun convert(holder: ItemViewHolder, binding: ItemReadSessionBinding,
                             item: ReadSession, payloads: MutableList<Any>) {
            binding.root.background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = 20.dpToPx().toFloat()
            }
            binding.timeRange.setTextColor(palette.text)
            binding.timeRange.text = timeRange(item)
            binding.bookName.setTextColor(palette.text)
            binding.bookName.text = item.bookName.ifBlank { getString(R.string.read_record_untitled_book) }
            binding.detail.setTextColor(palette.secondaryText)
            binding.detail.text = listOf(item.author, duration(item.readTime), item.zoneId)
                .filter { it.isNotBlank() }.joinToString(" · ")
            binding.delete.contentDescription = getString(R.string.read_timeline_delete_description,
                binding.timeRange.text, binding.bookName.text)
            binding.delete.backgroundTintList = ColorStateList.valueOf(palette.accent)
            binding.delete.setTextColor(buttonTextColor)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadSessionBinding) {
            binding.delete.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                alert(R.string.delete, R.string.read_timeline_delete_confirm) {
                    yesButton {
                        lifecycleScope.launch {
                            try {
                                withContext(IO) { globalExecutor.submit { ReadSessionStorage.delete(appDb, item.id) }.get() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                toastOnUi(R.string.read_timeline_error)
                            }
                        }
                    }
                    noButton()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("date", date.toEpochDay())
        super.onSaveInstanceState(outState)
    }
}
