package io.legado.app.help.source

import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray

internal object ExploreKindsParser {
    fun parse(rule: String): List<ExploreKind> {
        val text = rule.trim()
        if (text.startsWith("<useweb>", true) && text.endsWith("</useweb>", true)) {
            return listOf(ExploreKind(html = text.substring(8, text.length - 9)))
        }
        if (text.isJsonArray()) {
            return GSON.fromJsonArray<ExploreKind>(text).getOrThrow()
        }
        return text.split("(&&|\n)+".toRegex()).map { kind ->
            val config = kind.split("::")
            ExploreKind(config.first(), config.getOrNull(1))
        }
    }
}
