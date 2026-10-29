package io.legado.app.ui.rss.read

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData

/** 标签属于宿主的 FragmentManager；隐藏时保留视图，关闭时释放，不缓存 Activity。 */
class RssTabManager(
    private val activity: FragmentActivity,
    private val container: FrameLayout,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    private companion object {
        const val MAX_TABS = 5
    }

    private val fragments get() = activity.supportFragmentManager
    val tabs: List<ReadRssFragment>
        get() = fragments.fragments.filterIsInstance<ReadRssFragment>()
            .filter { it.sourceKey != null && !it.isRemoving }
    val count = MutableLiveData(0)
    var activeTab: ReadRssFragment? = null
        private set

    fun restore(state: Bundle?) {
        val activeKey = state?.getString("activeRssTab")
        // 兼容旧版本保存的标签，FragmentManager 的添加顺序即首次打开顺序。
        trimTo(MAX_TABS)
        select(tabs.firstOrNull { it.sourceKey == activeKey })
    }

    fun saveState(state: Bundle) {
        state.putString("activeRssTab", activeTab?.sourceKey)
    }

    fun resume(sourceKey: String): Boolean {
        val tab = tabs.firstOrNull { it.sourceKey == sourceKey } ?: return false
        if (fragments.isStateSaved) return false
        select(tab)
        return true
    }

    fun open(sourceKey: String, title: String, url: String) {
        if (fragments.isStateSaved || activity.isFinishing) return
        if (resume(sourceKey)) return
        trimTo(MAX_TABS - 1)
        val tab = ReadRssFragment().apply {
            arguments = Bundle().apply {
                putString("tabSource", sourceKey)
                putString("sourceKey", sourceKey)
                putString("title", title)
                putString("origin", url)
            }
        }
        fragments.beginTransaction().setReorderingAllowed(true)
            .add(container.id, tab, "rss_tab:$sourceKey")
            .hide(tab)
            .setMaxLifecycle(tab, Lifecycle.State.STARTED)
            .commitNow()
        select(tab)
    }

    fun minimize() = select(null)

    fun close(sourceKey: String) {
        if (fragments.isStateSaved) return
        val tab = tabs.firstOrNull { it.sourceKey == sourceKey } ?: return
        if (activeTab == tab) select(null)
        fragments.beginTransaction().remove(tab).commitNow()
        count.value = tabs.size
    }

    private fun trimTo(limit: Int) {
        tabs.dropLast(limit).forEach { close(it.sourceKey!!) }
    }

    private fun select(selected: ReadRssFragment?) {
        if (fragments.isStateSaved) return
        val transaction = fragments.beginTransaction().setReorderingAllowed(true)
        tabs.forEach { tab ->
            if (tab == selected) {
                transaction.show(tab).setMaxLifecycle(tab, Lifecycle.State.RESUMED)
            } else {
                transaction.hide(tab).setMaxLifecycle(tab, Lifecycle.State.STARTED)
            }
        }
        transaction.commitNow()
        activeTab = selected
        container.visibility = if (selected == null) View.GONE else View.VISIBLE
        onVisibilityChanged(selected != null)
        count.value = tabs.size
    }
}
