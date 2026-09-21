package io.legado.app.ui.main.explore

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView as AppCompatSearchView
import androidx.core.view.isVisible
import com.google.android.material.tabs.TabLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogExploreSourceMenuBinding
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.SearchView
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 发现界面：常驻搜索 + 分组筛选 + 卡片式书源（展开后展示三列发现分类）
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val searchView: SearchView by lazy { binding.searchView }
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null

    /**
     * 当前选中的分组，null 表示全部
     */
    private var selectedGroup: String? = null

    /**
     * 切换分组时清空搜索框会触发文本监听，这里做一次抑制
     */
    private var suppressSearchChange = false

    /**
     * 重建分组 Tab 时抑制选中回调
     */
    private var suppressTabChange = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.vwStatusBar.applyStatusBarPadding()
        initSearchView()
        initCollapseAll()
        initRecyclerView()
        initGroupData()
        upExploreData()
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.screen_find)
        searchView.setOnQueryTextListener(object : AppCompatSearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!suppressSearchChange) {
                    upExploreData()
                }
                return false
            }
        })
    }

    private fun initCollapseAll() {
        binding.tvCollapseAll.setTextColor(requireContext().accentColor)
        binding.tvCollapseAll.setOnClickListener {
            adapter.compressExplore()
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
    }

    private fun initGroupData() {
        initGroupTabs()
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    if (selectedGroup != null && !groups.contains(selectedGroup)) {
                        selectedGroup = null
                    }
                    upGroupTabs()
                    delay(500)
                }
        }
    }

    /**
     * 分组筛选条：与书架页分组一致的下划线 TabLayout 样式
     */
    private fun initGroupTabs() {
        binding.tabGroup.apply {
            isTabIndicatorFullWidth = false
            tabMode = TabLayout.MODE_SCROLLABLE
            setSelectedTabIndicatorColor(requireContext().accentColor)
            setTabTextColors(
                tabTextColors?.defaultColor
                    ?: context.getCompatColor(R.color.secondaryText),
                requireContext().accentColor
            )
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (suppressTabChange) return
                    val title = tab.text?.toString()
                    selectGroup(title?.takeIf { it != getString(R.string.all) })
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
    }

    /**
     * 重建分组 Tab，首个为「全部」
     */
    private fun upGroupTabs() {
        val tabLayout = binding.tabGroup
        suppressTabChange = true
        tabLayout.removeAllTabs()
        tabLayout.addTab(
            tabLayout.newTab().setText(R.string.all),
            selectedGroup == null
        )
        groups.forEach { group ->
            tabLayout.addTab(
                tabLayout.newTab().setText(group),
                group == selectedGroup
            )
        }
        suppressTabChange = false
    }

    private fun selectGroup(group: String?) {
        if (selectedGroup == group) return
        selectedGroup = group
        if (!searchView.query.isNullOrEmpty()) {
            suppressSearchChange = true
            searchView.setQuery("", false)
            suppressSearchChange = false
        }
        upExploreData()
        binding.rvFind.scrollToPosition(0)
    }

    private fun upExploreData() {
        exploreFlowJob?.cancel()
        val keyword = searchView.query?.toString()?.trim().orEmpty()
        val group = selectedGroup
        val sourceFlow = when {
            keyword.isNotEmpty() -> appDb.bookSourceDao.flowExplore(keyword)
            group != null -> appDb.bookSourceDao.flowGroupExplore(group)
            else -> appDb.bookSourceDao.flowExplore()
        }
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            sourceFlow.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.tvSourceCount.text = getString(R.string.explore_source_count, it.size)
                binding.llEmpty.isVisible = it.isEmpty()
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(bookSource).toString())
        }
    }

    /**
     * 书源操作：底部面板
     */
    override fun showSourceMenu(source: BookSourcePart) {
        val sheet = DialogExploreSourceMenuBinding.inflate(layoutInflater)
        sheet.tvSheetSourceName.text = source.bookSourceName
        sheet.btnLogin.isVisible = source.hasLoginUrl
        val dialog = AlertDialog.Builder(requireContext())
            .setView(sheet.root)
            .create()
        // 动画必须在 show() 之前设置才会生效
        dialog.window?.setWindowAnimations(R.style.Animation_Legado_BottomSheet)
        dialog.show()
        // 位置与尺寸在 show() 之后设置，面板才会贴着屏幕底部弹出
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        sheet.btnSearch.setOnClickListener {
            dialog.dismiss()
            searchBook(source)
        }
        sheet.btnLogin.setOnClickListener {
            dialog.dismiss()
            loginSource(source)
        }
        sheet.btnRefresh.setOnClickListener {
            dialog.dismiss()
            adapter.refreshSource(source)
        }
        sheet.btnTop.setOnClickListener {
            dialog.dismiss()
            toTop(source)
        }
        sheet.btnEdit.setOnClickListener {
            dialog.dismiss()
            editSource(source.bookSourceUrl)
        }
        sheet.btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteSource(source)
        }
    }

    private fun loginSource(source: BookSourcePart) {
        startActivity<SourceLoginActivity> {
            putExtra("type", "bookSource")
            putExtra("key", source.bookSourceUrl)
        }
    }

    fun compressExplore() {
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

}
