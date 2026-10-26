package io.legado.app.ui.book.explore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Bounded requests, failure isolation and sampling shared by every recommendation refresh. */
internal class RandomRecommendLoader<C, B>(
    private val key: (B) -> Any,
    private val fetch: suspend (C, Int) -> List<B>,
    private val random: Random = Random.Default
) {
    data class Result<B>(val books: List<B>, val failedCategories: Int)

    suspend fun load(categories: List<C>): Result<B> = coroutineScope {
        val books = linkedMapOf<Any, B>()
        val failed = hashSetOf<C>()
        var active = categories.distinct().shuffled(random)
        // First pages provide a candidate pool across all selections. Continue only if needed.
        for (page in 1..3) {
            val next = arrayListOf<C>()
            active.chunked(3).forEach { batch ->
                batch.map { category ->
                    async {
                        try {
                            category to withTimeoutOrNull(30_000) { fetch(category, page) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            category to null
                        }
                    }
                }.awaitAll().forEach { (category, result) ->
                    coroutineContext.ensureActive()
                    if (result == null) {
                        failed.add(category)
                    } else {
                        var added = false
                        result.shuffled(random).forEach { book ->
                            if (books.putIfAbsent(key(book), book) == null) added = true
                        }
                        // Empty or repeated pages cannot fill the pool further.
                        if (added) next.add(category)
                    }
                }
            }
            if (books.size >= 20 || next.isEmpty()) break
            active = next.shuffled(random)
        }
        Result(books.values.shuffled(random).take(20), failed.size)
    }
}
