package io.legado.app.ui.book.explore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomRecommendLoaderTest {
    @Test
    fun `empty configuration never requests books`() = runBlocking {
        val loader = RandomRecommendLoader<String, Int>(key = { it }, fetch = { _, _ ->
            error("Must not request an unconfigured category")
        })
        assertTrue(loader.load(emptyList()).books.isEmpty())
    }

    @Test
    fun `samples twenty unique books from all selected categories`() = runBlocking {
        val requested = mutableSetOf<String>()
        val loader = RandomRecommendLoader<String, Int>(
            key = { it },
            fetch = { category, page ->
                assertEquals(1, page)
                requested.add(category)
                if (category == "first") (1..30).toList() else (20..50).toList()
            }, random = Random(7)
        )
        val result = loader.load(listOf("first", "second", "first"))
        assertEquals(setOf("first", "second"), requested)
        assertEquals(20, result.books.size)
        assertEquals(20, result.books.toSet().size)
        assertTrue(result.books != (1..20).toList())
        assertEquals(0, result.failedCategories)
    }

    @Test
    fun `loads later pages to fill short results`() = runBlocking {
        val pages = mutableListOf<Int>()
        val loader = RandomRecommendLoader<String, Int>(key = { it }, fetch = { _, page ->
            pages.add(page)
            ((page - 1) * 8 until page * 8).toList()
        })
        assertEquals(20, loader.load(listOf("selected")).books.size)
        assertEquals(listOf(1, 2, 3), pages)
    }

    @Test
    fun `repeated pages stop and preserve fewer than twenty books`() = runBlocking {
        var requests = 0
        val loader = RandomRecommendLoader<String, Int>(key = { it }, fetch = { _, _ ->
            requests++
            listOf(1, 1, 2)
        })
        assertEquals(setOf(1, 2), loader.load(listOf("selected")).books.toSet())
        assertEquals(2, requests)
    }

    @Test
    fun `failed categories do not discard other results`() = runBlocking {
        val loader = RandomRecommendLoader<String, Int>(key = { it }, fetch = { category, _ ->
            if (category == "failed") error("Unavailable category")
            listOf(1, 2, 3)
        })
        val result = loader.load(listOf("failed", "working"))
        assertEquals(setOf(1, 2, 3), result.books.toSet())
        assertEquals(1, result.failedCategories)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates instead of becoming a failed category`() {
        runBlocking {
            RandomRecommendLoader<String, Int>(key = { it }, fetch = { _, _ ->
                throw CancellationException("Refresh replaced")
            }).load(listOf("selected"))
        }
    }

    @Test
    fun `requests at most three categories concurrently`() = runBlocking {
        var concurrent = 0
        var maximum = 0
        val loader = RandomRecommendLoader<Int, Int>(key = { it }, fetch = { category, _ ->
            concurrent++
            maximum = maxOf(maximum, concurrent)
            delay(10)
            concurrent--
            listOf(category)
        })
        loader.load((1..8).toList())
        assertEquals(3, maximum)
        assertEquals(0, concurrent)
    }
}
