package io.legado.app.ui.book.agent

import java.security.MessageDigest

data class ChapterReference(val index: Int, val title: String, val url: String)

data class ChapterContext(
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val chapters: List<ChapterReference>,
    val truncated: Boolean,
    val contentHash: String,
    val summary: String? = null,
    val summaryVersion: Int? = null
)

/** 只投影请求中的章节正文，不删除会话原文或工具调用配对。 */
internal object AgentContextManager {
    const val SUMMARY_VERSION = 1
    const val MAX_SUMMARY_CHARS = 2400

    fun hash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun compactIndices(
        turns: List<AgentChatTurn>,
        recentRounds: Int = 2,
        historyChars: Int = 24000
    ): Set<Int> {
        val userStarts = turns.indices.filter {
            turns[it].role == "user" && turns[it].internalPrompt != true
        }
        // 最新用户轮次仍在执行；其中所有新工具结果均保留。
        val currentStart = userStarts.lastOrNull() ?: turns.size
        val recentStart = userStarts.getOrNull((userStarts.size - 1 - recentRounds).coerceAtLeast(0))
            ?: currentStart
        val historical = turns.indices.filter {
            it < currentStart && turns[it].role == "tool" && turns[it].readingContext != null
        }
        val compact = historical.filterTo(linkedSetOf()) { it < recentStart }
        var remaining = historical.filter { it !in compact }.sumOf { turns[it].content.length.toLong() }
        for (index in historical) {
            if (remaining <= historyChars) break
            if (compact.add(index)) remaining -= turns[index].content.length
        }
        return compact
    }

    fun cachedSummary(turn: AgentChatTurn): String? = turn.readingContext?.let {
        it.summary?.takeIf { summary ->
            summary.isNotBlank() && it.summaryVersion == SUMMARY_VERSION && it.contentHash == hash(turn.content)
        }
    }

    fun location(context: ChapterContext): String = buildString {
        append("书籍：《${context.bookName}》 作者：${context.author}\n")
        append("bookUrl=${context.bookUrl}\n")
        context.chapters.forEach {
            append("第${it.index + 1}章 ${it.title}：startIndex=${it.index}, count=1, url=${it.url}\n")
        }
        if (context.truncated) append("原读取结果已截断，资料不完整，摘要仅覆盖实际返回的部分。\n")
    }

    fun project(turns: List<AgentChatTurn>, compact: Set<Int>): List<AgentChatTurn> =
        turns.mapIndexed { index, turn ->
            val context = turn.readingContext
            if (index !in compact || context == null) turn else turn.copy(content = buildString {
                append(location(context))
                append("历史正文已从本次上下文省略。\n")
                append(cachedSummary(turn) ?: "摘要暂不可用，需要正文时重新读取。")
                append("\n摘要不可用于逐字引用。需要准确措辞或细节时，使用 bookUrl 和 startIndex 重新读取；工具禁用或读取失败时如实说明。")
            })
        }
}
