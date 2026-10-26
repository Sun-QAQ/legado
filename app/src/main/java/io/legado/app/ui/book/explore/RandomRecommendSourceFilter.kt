package io.legado.app.ui.book.explore

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.splitNotBlank

internal object RandomRecommendSourceFilter {
    fun groups(source: BookSourcePart): List<String> {
        return source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)
            ?.distinct().orEmpty()
    }

    /** null selects all groups; an empty string selects ungrouped sources. */
    fun filter(
        sources: List<BookSourcePart>,
        query: String,
        group: String?,
        onlySelected: Boolean,
        selectedUrls: Set<String>
    ): List<BookSourcePart> {
        val keyword = query.trim()
        return sources.filter { source ->
            val matchesGroup = when (group) {
                null -> true
                "" -> groups(source).isEmpty()
                else -> group in groups(source)
            }
            val matchesKeyword = keyword.isEmpty()
                || source.bookSourceName.contains(keyword, ignoreCase = true)
                || source.bookSourceUrl.contains(keyword, ignoreCase = true)
                || source.bookSourceGroup.orEmpty().contains(keyword, ignoreCase = true)
            matchesGroup && matchesKeyword && (!onlySelected || source.bookSourceUrl in selectedUrls)
        }
    }
}
