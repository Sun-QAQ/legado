package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ActivityRandomRecommendConfigBinding
import io.legado.app.databinding.ItemRandomRecommendSourceBinding
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RandomRecommendConfigActivity : BaseActivity<ActivityRandomRecommendConfigBinding>() {
    override val binding by viewBinding(ActivityRandomRecommendConfigBinding::inflate)
    private var selections = emptyList<RandomRecommendCategory>()
    private var sources = emptyList<BookSourcePart>()
    private var onlySelected = false
    private var searchQuery = ""
    private var selectedGroup: String? = null
    private var updatingTabs = false
    private var loading = false
    private val adapter by lazy { SourceAdapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        selections = RandomRecommendConfig.read(this)
        onlySelected = savedInstanceState?.getBoolean("onlySelected") ?: false
        searchQuery = savedInstanceState?.getString("searchQuery").orEmpty()
        selectedGroup = savedInstanceState?.getString("selectedGroup")
        initSearchView()
        initGroupTabs()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore().collect {
                sources = it
                updateGroupTabs()
                updateSources()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.random_recommend_config, menu)
        menu.findItem(R.id.menu_random_selected).isChecked = onlySelected
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("onlySelected", onlySelected)
        outState.putString("searchQuery", searchQuery)
        outState.putString("selectedGroup", selectedGroup)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        binding.searchView.clearFocus()
    }

    private fun initSearchView() {
        binding.searchView.apply {
            applyTint(primaryTextColor)
            setQuery(searchQuery, false)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText.orEmpty()
                    updateSources()
                    binding.recyclerView.scrollToPosition(0)
                    return true
                }
            })
        }
    }

    private fun initGroupTabs() {
        binding.tabGroup.apply {
            isTabIndicatorFullWidth = false
            setSelectedTabIndicatorColor(context.accentColor)
            setTabTextColors(context.getCompatColor(R.color.secondaryText), context.accentColor)
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (updatingTabs) return
                    selectedGroup = tab.tag as? String
                    updateSources()
                    binding.recyclerView.scrollToPosition(0)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
    }

    private fun updateGroupTabs() {
        val groups = sources.flatMap(RandomRecommendSourceFilter::groups).distinct()
            .sortedWith { first, second -> first.cnCompare(second) }
        val hasUngrouped = sources.any { RandomRecommendSourceFilter.groups(it).isEmpty() }
        if (selectedGroup != null && selectedGroup !in groups
            && !(selectedGroup == "" && hasUngrouped)
        ) {
            selectedGroup = null
        }
        updatingTabs = true
        binding.tabGroup.apply {
            removeAllTabs()
            addTab(newTab().setText(R.string.all), selectedGroup == null)
            if (hasUngrouped) {
                addTab(newTab().setText(R.string.no_group).setTag(""), selectedGroup == "")
            }
            groups.forEach { group ->
                addTab(newTab().setText(group).setTag(group), selectedGroup == group)
            }
        }
        updatingTabs = false
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_random_selected) {
            onlySelected = !onlySelected
            item.isChecked = onlySelected
            updateSources()
            binding.recyclerView.scrollToPosition(0)
            return true
        }
        if (item.itemId == R.id.menu_random_clear) {
            alert(R.string.random_recommend_clear, R.string.random_recommend_clear_confirm) {
                yesButton { save(emptyList()) }
                noButton()
            }
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun save(categories: List<RandomRecommendCategory>) {
        selections = categories
        RandomRecommendConfig.save(this, categories)
        updateSources()
    }

    private fun updateSources() {
        val selectedUrls = selections.mapTo(hashSetOf()) { it.sourceUrl }
        val visibleSources = RandomRecommendSourceFilter.filter(
            sources, searchQuery, selectedGroup, onlySelected, selectedUrls
        )
        adapter.setItems(visibleSources)
        binding.tvHint.setText(when {
            sources.isEmpty() -> R.string.random_recommend_no_sources
            visibleSources.isEmpty() && (searchQuery.isNotBlank() || selectedGroup != null) ->
                R.string.random_recommend_no_matching_sources
            onlySelected && visibleSources.isEmpty() -> R.string.random_recommend_no_selected_sources
            else -> R.string.random_recommend_config_hint
        })
    }

    private fun selectCategories(source: BookSourcePart) {
        if (loading) return
        loading = true
        binding.progressBar.isVisible = true
        lifecycleScope.launch {
            try {
                val kinds = withContext(IO) {
                    withTimeoutOrNull(30_000) { source.exploreKinds() }
                } ?: error(getString(R.string.random_recommend_categories_error))
                val categories = kinds.filterNot { it.title.startsWith("ERROR:") }
                if (categories.isEmpty()) {
                    alert(source.bookSourceName, getString(R.string.random_recommend_no_categories)) {
                        okButton()
                    }
                    return@launch
                }
                val selectedUrls = selections.filter { it.sourceUrl == source.bookSourceUrl }
                    .map { it.url }.toSet()
                val checked = BooleanArray(categories.size) {
                    !categories[it].url.isNullOrBlank() && categories[it].url in selectedUrls
                }
                val dialog = alert(source.bookSourceName) {
                    multiChoiceItems(categories.map { it.title }.toTypedArray(), checked) { _, i, value ->
                        checked[i] = value
                    }
                    okButton {
                        val chosen = categories.filterIndexed { i, kind ->
                            checked[i] && !kind.url.isNullOrBlank()
                        }.distinctBy { it.url }.map {
                            RandomRecommendCategory(source.bookSourceUrl, it.title, it.url!!)
                        }
                        save(selections.filterNot { it.sourceUrl == source.bookSourceUrl } + chosen)
                    }
                    cancelButton()
                }
                dialog.listView.apply {
                    adapter = RandomRecommendCategoryAdapter(adapter, categories)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alert(source.bookSourceName, getString(R.string.random_recommend_categories_error)) {
                    okButton()
                }
            } finally {
                loading = false
                binding.progressBar.isVisible = false
            }
        }
    }

    private inner class SourceAdapter :
        RecyclerAdapter<BookSourcePart, ItemRandomRecommendSourceBinding>(this@RandomRecommendConfigActivity) {
        override fun getViewBinding(parent: ViewGroup) =
            ItemRandomRecommendSourceBinding.inflate(inflater, parent, false)

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemRandomRecommendSourceBinding,
            item: BookSourcePart,
            payloads: MutableList<Any>
        ) {
            binding.tvName.text = item.bookSourceName
            val groups = RandomRecommendSourceFilter.groups(item)
            binding.tvGroup.text = groups.joinToString(" · ")
            binding.tvGroup.isVisible = groups.isNotEmpty()
            val selected = selections.filter { it.sourceUrl == item.bookSourceUrl }
            binding.tvSelection.text = if (selected.isEmpty()) {
                getString(R.string.random_recommend_not_selected)
            } else {
                getString(R.string.random_recommend_selected, selected.size,
                    selected.joinToString("、") { it.title })
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemRandomRecommendSourceBinding) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let(::selectCategories)
            }
        }
    }
}
