package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
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
    private var loading = false
    private val adapter by lazy { SourceAdapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        selections = RandomRecommendConfig.read(this)
        onlySelected = savedInstanceState?.getBoolean("onlySelected") ?: false
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore().collect {
                sources = it
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
        super.onSaveInstanceState(outState)
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
        val visibleSources = if (onlySelected) {
            sources.filter { it.bookSourceUrl in selectedUrls }
        } else {
            sources
        }
        adapter.setItems(visibleSources)
        binding.tvHint.setText(when {
            sources.isEmpty() -> R.string.random_recommend_no_sources
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
