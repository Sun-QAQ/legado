package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

object BookSourceParser {
    fun parse(text: String): List<BookSource> {
        val value = text.trimStart('\uFEFF').trim()
        val sources = when {
            value.startsWith("[") -> GSON.fromJsonArray<BookSource>(value).getOrThrow()
            value.startsWith("{") -> listOf(GSON.fromJsonObject<BookSource>(value).getOrThrow())
            else -> listOf(NgJsSource.parse(value))
        }
        require(sources.all { !it.bookSourceUrl.isNullOrBlank() && !it.bookSourceName.isNullOrBlank() }) {
            "不是有效书源：缺少书源地址或名称"
        }
        return sources
    }
}
