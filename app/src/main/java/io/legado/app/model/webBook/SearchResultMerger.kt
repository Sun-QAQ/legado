package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class SearchResultMerger(private val searchKey: String) {
    private data class Key(val name: String, val author: String)
    private val index = HashMap<Key, SearchBook>()
    private val equal = arrayListOf<SearchBook>()
    private val contains = arrayListOf<SearchBook>()
    private val other = arrayListOf<SearchBook>()

    suspend fun merge(batch: List<SearchBook>, precision: Boolean): ArrayList<SearchBook> {
        coroutineContext.ensureActive()
        if (batch.isNotEmpty()) {
            if (precision) {
                other.forEach { index.remove(Key(it.name, it.author)) }
                other.clear()
            }
            for (book in batch) {
                coroutineContext.ensureActive()
                val target = when {
                    book.name == searchKey || book.author == searchKey -> equal
                    book.name.contains(searchKey) || book.author.contains(searchKey) -> contains
                    !precision -> other
                    else -> continue
                }
                val key = Key(book.name, book.author)
                val existing = index[key]
                if (existing != null) existing.addOrigin(book.origin) else {
                    index[key] = book
                    target.add(book)
                }
            }
            // Stable sorts retain the previous ranking when origin counts become equal again.
            equal.sortByDescending { it.origins.size }
            contains.sortByDescending { it.origins.size }
        }
        coroutineContext.ensureActive()
        return ArrayList<SearchBook>(index.size).apply {
            addAll(equal)
            addAll(contains)
            addAll(other)
        }
    }
}
