package io.legado.app.help.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.model.analyzeRule.AnalyzeUrl

object NgImage {
    fun hasInlineImage(content: String): Boolean {
        val matcher = io.legado.app.constant.AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val style = options(matcher.group(1) ?: continue)?.get("style")
            if (style?.isJsonPrimitive == true && style.asString.equals("TEXT", true)) return true
        }
        return false
    }

    fun options(src: String): JsonObject? = runCatching {
        val match = AnalyzeUrl.paramPattern.matcher(src)
        if (!match.find()) return null
        JsonParser.parseString(src.substring(match.end())).asJsonObject
    }.getOrNull()

    fun click(src: String): String? = options(src)?.get("click")?.let {
        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
    }
}
