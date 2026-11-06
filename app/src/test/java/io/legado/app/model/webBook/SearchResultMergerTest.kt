package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime

class SearchResultMergerTest {
    private fun book(name: String, author: String = "author", origin: String = "one") =
        SearchBook(name = name, author = author, origin = origin)

    @Test fun `matching groups and distinct name author tuples retain their semantics`() = runBlocking {
        val merger = SearchResultMerger("key")
        val result = merger.merge(listOf(book("other"), book("contains-key"), book("key"),
            book("a-b", "c"), book("a", "b-c")), false)
        assertEquals(listOf("key", "contains-key", "other", "a-b", "a"), result.map { it.name })
        assertEquals(5, result.size)
    }

    @Test fun `duplicate sources merge and previous tie ranking survives across pages`() = runBlocking {
        val merger = SearchResultMerger("key")
        merger.merge(listOf(book("key-a"), book("key-b")), false)
        assertEquals(listOf("key-b", "key-a"), merger.merge(listOf(book("key-b", origin = "two")), false).map { it.name })
        val tied = merger.merge(listOf(book("key-a", origin = "two"), book("key-a", origin = "two")), false)
        assertEquals(listOf("key-b", "key-a"), tied.map { it.name })
        assertTrue(tied.all { it.origins.size == 2 })
    }

    @Test fun `precision changes filter old unrelated results without retaining hidden entries`() = runBlocking {
        val merger = SearchResultMerger("key")
        merger.merge(listOf(book("other"), book("key")), false)
        assertEquals(2, merger.merge(emptyList(), true).size)
        assertEquals(listOf("key"), merger.merge(listOf(book("ignored")), true).map { it.name })
        assertEquals(listOf("key", "fresh"), merger.merge(listOf(book("fresh")), false).map { it.name })
    }

    @Test fun `cancelled merging does not insert results`() = runBlocking {
        val merger = SearchResultMerger("key")
        val cancelled = Job().apply { cancel() }
        try { withContext(cancelled) { merger.merge(listOf(book("key")), false) } } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(merger.merge(emptyList(), false).isEmpty())
    }

    // Reference behavior from the previous implementation, including stable ranking between batches.
    private fun original(previous: List<SearchBook>, batch: List<SearchBook>): ArrayList<SearchBook> {
        val groups = List(3) { arrayListOf<SearchBook>() }
        fun group(book: SearchBook) = when {
            book.name == "key" || book.author == "key" -> 0
            book.name.contains("key") || book.author.contains("key") -> 1
            else -> 2
        }
        previous.forEach { groups[group(it)].add(it) }
        batch.forEach { book ->
            val target = groups[group(book)]
            val existing = target.firstOrNull { it.name == book.name && it.author == book.author }
            if (existing == null) target.add(book) else existing.addOrigin(book.origin)
        }
        groups[0].sortByDescending { it.origins.size }
        groups[1].sortByDescending { it.origins.size }
        return arrayListOf<SearchBook>().apply { groups.forEach(::addAll) }
    }

    @Test fun `large multi-source batches match previous ordering and origin aggregation`() = runBlocking {
        val batches = (0 until 50).map { source -> (0 until 250).map { item ->
            book(if (item % 3 == 0) "key-$item" else "title-$item", "author-${source % 10}", "source-$source")
        } }
        var expected = arrayListOf<SearchBook>()
        val oldTime = measureNanoTime { batches.forEach { batch -> expected = original(expected, batch.map { it.copy() }) } }
        val merger = SearchResultMerger("key")
        var actual = arrayListOf<SearchBook>()
        val newTime = measureNanoTime { batches.forEach { actual = merger.merge(it.map { book -> book.copy() }, false) } }
        assertEquals(expected.map { Triple(it.name, it.author, it.origins.toList()) }, actual.map { Triple(it.name, it.author, it.origins.toList()) })
        println("Search merge comparison: original=${oldTime / 1_000_000}ms indexed=${newTime / 1_000_000}ms books=${actual.size}")
    }
}
