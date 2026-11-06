package io.legado.app.model.localBook

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList

class EpubNoteTest {

    @Test fun `fragment references retain current document and decode identifiers`() {
        assertEquals("OEBPS/chapter.xhtml" to "注+释", EpubNote.resolveTarget("OEBPS/chapter.xhtml", "#%E6%B3%A8+%E9%87%8A"))
    }

    @Test fun `cross document fragments resolve relative paths without encoding the separator`() {
        assertEquals("Notes/end notes.xhtml" to "n1", EpubNote.resolveTarget("OEBPS/chapter.xhtml", "../Notes/end%20notes.xhtml#n1"))
    }

    @Test fun `ordinary links and empty fragments are not note targets`() {
        assertEquals(null, EpubNote.resolveTarget("chapter.xhtml", "next.xhtml"))
        assertEquals(null, EpubNote.resolveTarget("chapter.xhtml", "#"))
    }

    @Test
    fun `注释标记可转换为显示符号和正文`() {
        val notes = LinkedList<String>()
        val encoded = "正文${EpubNote.encode("第一条注释")}结束"

        val display = EpubNote.extract(EpubNote.cacheMarker + encoded, notes)

        assertEquals("正文${EpubNote.displayChar}结束", display)
        assertEquals(listOf("第一条注释"), notes)
    }

    @Test
    fun `识别EPUB3注释引用及目标`() {
        val document = Jsoup.parse(
            """
            <p>正文<a id="ref" epub:type="noteref" href="#note">1</a></p>
            <aside id="note" epub:type="footnote">注释正文</aside>
            """.trimIndent()
        )
        val reference = document.getElementById("ref")!!
        val target = document.getElementById("note")!!

        assertTrue(EpubNote.isNoteReference(reference, target))
        assertTrue(EpubNote.isNoteTarget(target))
    }

    @Test
    fun `普通内部链接不会误判为注释`() {
        val document = Jsoup.parse(
            """
            <a id="ref" href="#chapter">跳转</a>
            <h2 id="chapter">下一节</h2>
            """.trimIndent()
        )

        assertFalse(
            EpubNote.isNoteReference(
                document.getElementById("ref")!!,
                document.getElementById("chapter")!!
            )
        )
    }
}
