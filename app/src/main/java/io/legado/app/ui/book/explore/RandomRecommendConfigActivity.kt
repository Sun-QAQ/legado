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
    private var loading = false
    private val adapter by lazy { SourceAdapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        selections = RandomRecommendConfig.read(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore().collect { sources ->
                adapter.setItems(sources)
                binding.tvHint.setText(
                    if (sources.isEmpty()) R.string.random_recommend_no_sources
                    else R.string.random_recommend_config_hint
                )
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.random_recommend_config, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
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
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
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
                val selectable = kinds.filter {
                    !it.url.isNullOrBlank() && !it.title.startsWith("ERROR:")
                }.distinctBy { it.url }
                if (selectable.isEmpty()) {
                    alert(source.bookSourceName, getString(R.string.random_recommend_no_categories)) {
                        okButton()
                    }
                    return@launch
                }
                val selectedUrls = selections.filter { it.sourceUrl == source.bookSourceUrl }
                    .map { it.url }.toSet()
                val checked = BooleanArray(selectable.size) { selectable[it].url in selectedUrls }
                alert(source.bookSourceName) {
                    multiChoiceItems(selectable.map { it.title }.toTypedArray(), checked) { _, i, value ->
                        checked[i] = value
                    }
                    okButton {
                        val chosen = selectable.filterIndexed { i, _ -> checked[i] }.map {
                            RandomRecommendCategory(source.bookSourceUrl, it.title, it.url!!)
                        }
                        save(selections.filterNot { it.sourceUrl == source.bookSourceUrl } + chosen)
                    }
                    cancelButton()
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
