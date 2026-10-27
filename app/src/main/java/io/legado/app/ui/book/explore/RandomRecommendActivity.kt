package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityRandomRecommendBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

class RandomRecommendActivity :
    VMBaseActivity<ActivityRandomRecommendBinding, RandomRecommendViewModel>(),
    ExploreShowAdapter.CallBack {

    override val binding by viewBinding(ActivityRandomRecommendBinding::inflate)
    override val viewModel by viewModels<RandomRecommendViewModel>()
    private val adapter by lazy { ExploreShowAdapter(this, this, showSourceName = true) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.applyNavigationBarPadding()
        viewModel.state.observe(this) { state ->
            binding.progressBar.isVisible = state.loading
            val status = (listOf(state.message) + state.failedCategories.take(3))
                .filter { it.isNotBlank() }.joinToString("\n")
            binding.tvStatus.text = status
            binding.tvStatus.isVisible = status.isNotBlank()
            binding.tvFailedCategories.apply {
                isVisible = state.failedCategories.isNotEmpty()
                text = getString(R.string.random_recommend_view_failed_categories, state.failedCategories.size)
                setTextColor(context.accentColor)
                setOnClickListener {
                    alert(R.string.random_recommend_failed_categories) {
                        setMessage(state.failedCategories.joinToString("\n\n"))
                        okButton()
                    }
                }
            }
            adapter.setItems(state.books)
        }
        viewModel.shelfKeys.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf("isInBookshelf" to null))
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.random_recommend, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_random_config -> {
                startActivity<RandomRecommendConfigActivity>()
                true
            }
            R.id.menu_random_refresh -> {
                binding.recyclerView.scrollToPosition(0)
                viewModel.refresh(force = true)
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    override fun isInBookshelf(book: SearchBook) = viewModel.isInBookshelf(book)

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }
}
