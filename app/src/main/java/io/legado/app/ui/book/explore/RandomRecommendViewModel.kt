package io.legado.app.ui.book.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RandomRecommendViewModel(application: Application) : BaseViewModel(application) {
    data class State(
        val books: List<SearchBook> = emptyList(),
        val loading: Boolean = false,
        val message: String = "",
        val failedCategories: List<String> = emptyList()
    )

    val state = MutableLiveData(State())
    val shelfKeys = MutableLiveData<Set<String>>(emptySet())
    private var lastConfig: List<RandomRecommendCategory>? = null
    private var loadJob: Job? = null

    init {
        execute {
            appDb.bookDao.flowAll().collect { books ->
                shelfKeys.postValue(buildSet {
                    books.filterNot { it.isNotShelf }.forEach {
                        add(it.bookUrl)
                        add(if (it.author.isBlank()) it.name else "${it.name}-${it.author}")
                    }
                })
            }
        }
    }

    fun refresh(force: Boolean = false) {
        val config = RandomRecommendConfig.read(context)
        if (!force && lastConfig == config) return
        lastConfig = config
        loadJob?.cancel()
        if (config.isEmpty()) {
            state.value = State(message = context.getString(R.string.random_recommend_unconfigured))
            return
        }
        state.value = State(loading = true, message = context.getString(R.string.random_recommend_loading))
        loadJob = viewModelScope.launch {
            try {
                val (result, failedCategories) = withContext(IO) {
                    val loader = RandomRecommendLoader<RandomRecommendCategory, SearchBook>(
                        key = { book ->
                            if (book.author.isNotBlank()) listOf(book.name, book.author)
                            else listOf(book.origin, book.bookUrl)
                        },
                        fetch = { category, page ->
                            val source = appDb.bookSourceDao.getBookSource(category.sourceUrl)
                            check(source != null && source.enabledExplore) { "书源已删除或已关闭发现" }
                            WebBook.exploreBookAwait(source, category.url, page)
                                .filter { it.bookUrl.isNotBlank() && it.name.isNotBlank() }
                        }
                    )
                    val result = loader.load(config)
                    // BookInfoActivity resolves the selected book from this table.
                    appDb.searchBookDao.insert(*result.books.toTypedArray())
                    val sourceNames = result.failedCategories.map { it.sourceUrl }.distinct()
                        .associateWith { url ->
                            appDb.bookSourceDao.getBookSource(url)?.bookSourceName
                                ?.takeIf { it.isNotBlank() } ?: url
                        }
                    result to result.failedCategories.map { category ->
                        "${sourceNames.getValue(category.sourceUrl)} · ${category.title.ifBlank { category.url }}"
                    }
                }
                val message = when {
                    result.failedCategories.isNotEmpty() -> context.getString(
                        R.string.random_recommend_partial, result.books.size, result.failedCategories.size
                    )
                    result.books.isEmpty() -> context.getString(R.string.random_recommend_empty)
                    else -> ""
                }
                state.value = State(result.books, message = message, failedCategories = failedCategories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = State(message = context.getString(R.string.random_recommend_error))
            }
        }
    }

    fun isInBookshelf(book: SearchBook): Boolean {
        val keys = shelfKeys.value.orEmpty()
        val nameKey = if (book.author.isBlank()) book.name else "${book.name}-${book.author}"
        return book.bookUrl in keys || nameKey in keys
    }
}
