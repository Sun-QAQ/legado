package io.legado.app.ui.main.explore

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
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
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.vwStatusBar.applyStatusBarPadding()
        initSearchBar()
        initRecyclerView()
        initGroupData()
        upExploreData()
    }

    override fun onPause() {
        super.onPause()
        binding.etSearch.clearFocus()
    }

    private fun initSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                binding.ivClearSearch.isVisible = !s.isNullOrEmpty()
                if (!suppressSearchChange) {
                    upExploreData()
                }
            }
        })
        binding.ivClearSearch.setOnClickListener {
            binding.etSearch.setText("")
        }
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
                    upGroupChips()
                    delay(500)
                }
        }
    }

    /**
     * 重建分组筛选条，首个为「全部」
     */
    private fun upGroupChips() {
        val container = binding.llGroups
        container.removeAllViews()
        val accent = requireContext().accentColor
        val normal = requireContext().secondaryTextColor
        addGroupChip(container, null, accent, normal)
        groups.forEach { addGroupChip(container, it, accent, normal) }
    }

    private fun addGroupChip(
        container: LinearLayout,
        group: String?,
        accentColor: Int,
        normalColor: Int
    ) {
        val chip = TextView(requireContext()).apply {
            text = group ?: getString(R.string.all)
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(15.dpToPx(), 0, 15.dpToPx(), 0)
            setBackgroundResource(R.drawable.bg_explore_chip)
            isSelected = group == selectedGroup
            setTextColor(if (isSelected) accentColor else normalColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                34.dpToPx()
            ).apply {
                marginEnd = 8.dpToPx()
            }
            setOnClickListener { selectGroup(group) }
        }
        container.addView(chip)
    }

    private fun selectGroup(group: String?) {
        if (selectedGroup == group) return
        selectedGroup = group
        upGroupChips()
        if (!binding.etSearch.text.isNullOrEmpty()) {
            suppressSearchChange = true
            binding.etSearch.setText("")
            suppressSearchChange = false
        }
        upExploreData()
        binding.rvFind.scrollToPosition(0)
    }

    private fun upExploreData() {
        exploreFlowJob?.cancel()
        val keyword = binding.etSearch.text?.toString()?.trim().orEmpty()
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
        val dialog = alert {
            customView { sheet.root }
        }
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
