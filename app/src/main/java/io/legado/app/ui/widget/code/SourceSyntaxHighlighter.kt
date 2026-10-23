package io.legado.app.ui.widget.code

/** A tolerant lexer for editor display only. Never executes or rewrites source text. */
object SourceSyntaxHighlighter {
    enum class Language { RULE, JAVASCRIPT, JSON, PLAIN }
    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER, RULE, PROPERTY, OPERATOR }
    data class Token(val start: Int, val end: Int, val kind: Kind)

    // Bound the number of Android spans, even for generated or minified input.
    const val MAX_TOKENS = 12000
    private val keywords = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while",
        "do", "switch", "case", "break", "continue", "default", "try", "catch", "finally",
        "throw", "new", "this", "typeof", "instanceof", "in", "of", "delete", "void",
        "class", "extends", "super", "static", "import", "export", "from", "as",
        "async", "await", "yield", "true", "false", "null", "undefined"
    )
    private val expressionKeywords = setOf("return", "throw", "case", "typeof", "void", "delete", "new", "yield", "await", "in", "of")
    private val markers = listOf("@XPath:", "@Json:", "@css:", "@get:", "@put:", "@@", "&&", "||", "%%", "##")
    private val number = Regex("(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")
    private val plainFields = setOf(
        "bookSourceName", "bookSourceGroup", "bookSourceComment", "sourceName", "sourceGroup",
        "sourceComment", "variableComment", "concurrentRate", "bookUrlPattern", "style", "checkKeyWord"
    )

    fun languageForField(key: String): Language = when {
        key.endsWith("Js", true) || key == "jsLib" || key == "imageDecode" -> Language.JAVASCRIPT
        key == "header" || key == "loginUi" -> Language.JSON
        key in plainFields -> Language.PLAIN
        else -> Language.RULE
    }

    fun tokenize(
        text: String,
        language: Language = Language.RULE,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): List<Token> {
        if (language == Language.PLAIN) return emptyList()
        val tokens = ArrayList<Token>()
        var index = 0
        var javascript = language == Language.JAVASCRIPT
        var closing: String? = null
        var braces = 0
        var regexAllowed = true
        fun add(end: Int, kind: Kind) {
            tokens.add(Token(index, end, kind))
            index = end
        }
        while (index < text.length && tokens.size < MAX_TOKENS) {
            if (isCancelled()) return emptyList()
            val c = text[index]
            if (c.isWhitespace()) { index++; continue }
            val endMarker = closing
            if (javascript && endMarker != null && braces == 0 && text.startsWith(endMarker, index, true)) {
                add(index + endMarker.length, Kind.RULE)
                javascript = false
                closing = null
                continue
            }
            if (!javascript) {
                val jsMarker = when {
                    text.startsWith("@js:", index, true) -> "@js:"
                    text.startsWith("<js>", index, true) -> "<js>"
                    text.startsWith("{{", index) -> "{{"
                    else -> null
                }
                if (jsMarker != null) {
                    closing = when (jsMarker) { "<js>" -> "</js>"; "{{" -> "}}"; else -> null }
                    javascript = true
                    regexAllowed = true
                    braces = 0
                    add(index + jsMarker.length, Kind.RULE)
                    continue
                }
                val marker = markers.firstOrNull { text.startsWith(it, index, true) }
                if (marker != null) { add(index + marker.length, Kind.RULE); continue }
            }
            // Quotes and comments consume their entire region, preventing overlapping colors.
            if (c == '"' || c == '\'' || (javascript && c == '`')) {
                val end = quotedEnd(text, index, c)
                var next = end
                while (next < text.length && text[next].isWhitespace()) next++
                add(end, if (next < text.length && text[next] == ':') Kind.PROPERTY else Kind.STRING)
                regexAllowed = false
                continue
            }
            if (javascript && text.startsWith("//", index)) {
                add(text.indexOf('\n', index).let { if (it < 0) text.length else it }, Kind.COMMENT)
                continue
            }
            if (javascript && text.startsWith("/*", index)) {
                add(text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }, Kind.COMMENT)
                continue
            }
            if (javascript && c == '/' && regexAllowed) {
                val end = regexEnd(text, index)
                if (end > index + 1) { add(end, Kind.STRING); regexAllowed = false; continue }
            }
            if (c in '0'..'9' || (c == '.' && text.getOrNull(index + 1)?.isDigit() == true)) {
                val match = number.matchAt(text, index)
                if (match != null) { add(match.range.last + 1, Kind.NUMBER); regexAllowed = false; continue }
            }
            if (c.isLetter() || c == '_' || c == '$') {
                var end = index + 1
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
                val word = text.substring(index, end)
                if ((javascript && word in keywords) || (language == Language.JSON && word in setOf("true", "false", "null"))) {
                    add(end, Kind.KEYWORD)
                } else {
                    index = end
                }
                regexAllowed = word in expressionKeywords
                continue
            }
            if (javascript) {
                if (c == '{') braces++
                if (c == '}' && braces > 0) braces--
                if (c in "{}[]():;,.=+-*%<>!&|?~^/") {
                    add(index + 1, Kind.OPERATOR)
                    regexAllowed = c !in ")]}."
                    continue
                }
            } else if (c in "{}[]():,") {
                add(index + 1, Kind.OPERATOR)
                continue
            }
            index++
        }
        return tokens
    }

    private fun quotedEnd(text: String, start: Int, quote: Char): Int {
        var end = start + 1
        while (end < text.length) {
            val c = text[end++]
            if (c == '\\') end = (end + 1).coerceAtMost(text.length)
            else if (c == quote) break
            else if ((c == '\n' || c == '\r') && quote != '`') { end--; break }
        }
        return end
    }

    private fun regexEnd(text: String, start: Int): Int {
        var end = start + 1
        var inClass = false
        while (end < text.length) {
            when (text[end++]) {
                '\n', '\r' -> return start + 1
                '\\' -> end = (end + 1).coerceAtMost(text.length)
                '[' -> inClass = true
                ']' -> inClass = false
                '/' -> if (!inClass) {
                    while (end < text.length && text[end].isLetter()) end++
                    return end
                }
            }
        }
        return start + 1
    }
}
