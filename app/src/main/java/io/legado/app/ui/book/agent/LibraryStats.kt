package io.legado.app.ui.book.agent

import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isUpError
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank

/**
 * 生成书库统计信息，供 AI 对话 library_stats 工具使用
 */
object LibraryStats {

    private const val UNGROUPED = "未分组"

    fun build(): String {
        val bookSources = appDb.bookSourceDao.all
        val shelfBooks = appDb.bookDao.all.filterNot { it.isNotShelf }
        val userGroupIdSum = appDb.bookGroupDao.idsSum
        val bookshelfGroups = appDb.bookGroupDao.all.map { group ->
            mapOf(
                "groupId" to group.groupId,
                "name" to group.groupName,
                "show" to group.show,
                "bookCount" to countBooks(shelfBooks, group, userGroupIdSum)
            )
        }
        return GSON.toJson(
            mapOf(
                "bookSourceCount" to appDb.bookSourceDao.allCount(),
                "rssSourceCount" to appDb.rssSourceDao.size,
                "bookSourceGroups" to buildBookSourceGroups(bookSources),
                "bookCount" to shelfBooks.size,
                "bookshelfGroups" to bookshelfGroups
            )
        )
    }

    private fun buildBookSourceGroups(sources: List<BookSource>): List<Map<String, Any>> {
        val groupCounts = linkedMapOf<String, IntArray>()
        fun ensure(name: String): IntArray = groupCounts.getOrPut(name) { intArrayOf(0, 0) }
        sources.forEach { source ->
            val groups = source.bookSourceGroup
                ?.splitNotBlank(AppPattern.splitGroupRegex)
                ?.toList()
                ?: emptyList()
            if (groups.isEmpty()) {
                ensure(UNGROUPED).also {
                    it[0]++
                    if (source.enabled) it[1]++
                }
            } else {
                groups.forEach { group ->
                    ensure(group).also {
                        it[0]++
                        if (source.enabled) it[1]++
                    }
                }
            }
        }
        return groupCounts.entries
            .sortedWith { o1, o2 -> o1.key.cnCompare(o2.key) }
            .map { (name, counts) ->
                mapOf(
                    "name" to name,
                    "count" to counts[0],
                    "enabledCount" to counts[1]
                )
            }
    }

    private fun countBooks(
        shelfBooks: List<Book>,
        group: BookGroup,
        userGroupIdSum: Long
    ): Int {
        return when (group.groupId) {
            BookGroup.IdAll -> shelfBooks.size
            BookGroup.IdLocal -> shelfBooks.count { it.isLocal }
            BookGroup.IdAudio -> shelfBooks.count { it.isAudio }
            BookGroup.IdError -> shelfBooks.count { it.isUpError }
            BookGroup.IdNetNone -> shelfBooks.count {
                !it.isLocal && !it.isAudio && (it.group and userGroupIdSum) == 0L
            }
            BookGroup.IdLocalNone -> shelfBooks.count {
                it.isLocal && !it.isAudio && (it.group and userGroupIdSum) == 0L
            }
            else -> shelfBooks.count { (it.group and group.groupId) != 0L }
        }
    }
}
