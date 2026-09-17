package io.legado.app.model.localBook

import org.jsoup.nodes.Element

object EpubNote {

    const val displayChar = "※"
    const val cacheMarker = "\uE100LEGADO_EPUB_NOTE_V1\uE101"
    private const val noteStart = '\uE102'
    private const val noteEnd = '\uE103'

    fun encode(content: String): String {
        val safeContent = content
            .replace(noteStart, ' ')
            .replace(noteEnd, ' ')
            .trim()
        return if (safeContent.isEmpty()) "" else "$noteStart$safeContent$noteEnd"
    }

    fun extract(text: String, notes: MutableCollection<String>): String {
        val source = text.replace(cacheMarker, "")
        val result = StringBuilder(source.length)
        var position = 0
        while (position < source.length) {
            val start = source.indexOf(noteStart, position)
            if (start < 0) {
                result.append(source, position, source.length)
                break
            }
            result.append(source, position, start)
            val end = source.indexOf(noteEnd, start + 1)
            if (end < 0) {
                result.append(source, start, source.length)
                break
            }
            val content = source.substring(start + 1, end).trim()
            if (content.isNotEmpty()) {
                notes.add(content)
                result.append(displayChar)
            }
            position = end + 1
        }
        return result.toString()
    }

    fun isCurrentCache(content: String): Boolean = content.contains(cacheMarker)

    fun isNoteReference(reference: Element, target: Element?): Boolean {
        if (reference.hasToken("epub:type", "noteref") ||
            reference.hasToken("role", "doc-noteref") ||
            reference.classNames().any { it.isReferenceClass() }
        ) {
            return true
        }
        return generateSequence(target) { it.parent() }.any(::isNoteTarget)
    }

    fun isNoteTarget(element: Element): Boolean {
        return element.hasAnyToken("epub:type", noteTypes) ||
                element.hasAnyToken("role", noteRoles) ||
                element.classNames().any { it.lowercase() in noteClasses }
    }

    private fun Element.hasToken(attribute: String, token: String): Boolean {
        return attr(attribute).split(tokenSeparator).any { it.equals(token, true) }
    }

    private fun Element.hasAnyToken(attribute: String, tokens: Set<String>): Boolean {
        return attr(attribute).split(tokenSeparator).any { it.lowercase() in tokens }
    }

    private fun String.isReferenceClass(): Boolean {
        return lowercase() in referenceClasses
    }

    private val tokenSeparator = Regex("\\s+")
    private val noteTypes = setOf("footnote", "endnote", "rearnote", "note")
    private val noteRoles = setOf("doc-footnote", "doc-endnote")
    private val noteClasses = setOf("footnote", "endnote", "rearnote", "note")
    private val referenceClasses = setOf(
        "noteref",
        "note-ref",
        "footnote-ref",
        "endnote-ref",
        "footnote-link",
        "endnote-link"
    )
}
