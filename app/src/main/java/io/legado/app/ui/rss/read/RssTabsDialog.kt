package io.legado.app.ui.rss.read

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.databinding.ItemRssWebTabBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx

class RssTabsDialog : DialogFragment() {
    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.applyTint()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = requireActivity() as MainActivity
        val rows = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        fun populate() {
            rows.removeAllViews()
            val tabs = host.rssTabs.tabs
            if (tabs.isEmpty()) {
                rows.addView(TextView(requireContext()).apply {
                    setText(R.string.rss_no_web_tabs)
                    setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 24.dpToPx())
                    setTextColor(primaryTextColor)
                })
            }
            tabs.forEach { tab ->
                val row = ItemRssWebTabBinding.inflate(layoutInflater, rows, false)
                val title = tab.pageTitle.ifBlank { tab.arguments?.getString("title").orEmpty() }
                row.tabTitle.text = title
                row.tabUrl.text = tab.pageUrl.ifBlank { tab.arguments?.getString("origin") }
                row.tabTitle.setTextColor(primaryTextColor)
                row.tabUrl.setTextColor(primaryTextColor)
                row.tabClose.setColorFilter(primaryTextColor)
                row.tabOpen.isSelected = host.rssTabs.activeTab == tab
                row.tabOpen.setOnClickListener {
                    host.rssTabs.resume(tab.sourceKey!!)
                    dismiss()
                }
                row.tabClose.contentDescription = getString(R.string.rss_close_named_tab, title)
                row.tabClose.setOnClickListener {
                    host.closeRssTab(tab.sourceKey!!)
                    populate()
                }
                rows.addView(row.root)
            }
        }
        populate()
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.rss_web_tabs)
            .setView(ScrollView(requireContext()).apply { addView(rows) })
            .setPositiveButton(R.string.close, null)
            .create()
    }
}
