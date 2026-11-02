package io.legado.app.help.voice

import android.os.Build
import androidx.annotation.RequiresApi

object VoiceBookNames {
    /** Homophones are suggestions requiring confirmation, including a single suggestion. */
    fun <T> suggestions(query: String, items: List<T>, name: (T) -> String): List<T> {
        if (Build.VERSION.SDK_INT < 29 || query.length < 2) return emptyList()
        return runCatching { matchPinyin(query, items, name) }.getOrDefault(emptyList())
    }

    @RequiresApi(29)
    private fun <T> matchPinyin(query: String, items: List<T>, name: (T) -> String): List<T> {
        val converter = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        fun key(text: String) = converter.transliterate(VoiceCommandParser.normalize(text))
            .replace(Regex("\\s+"), "")
        val target = key(query)
        return items.filter { key(name(it)) == target }
    }
}
