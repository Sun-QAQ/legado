package io.legado.app.help.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreKindsParserTest {
    @Test
    fun `web discovery preserves newlines scripts and category delimiters`() {
        val html = "<!doctype html>\n<style>:root{color:red}</style>\n<script>var x='a::b&&c';</script>"
        val kinds = ExploreKindsParser.parse(" \n<USEWEB>$html</USEWEB>\n ")
        assertEquals(1, kinds.size)
        assertEquals(html, kinds.single().html)
        assertNull(kinds.single().url)
    }

    @Test
    fun `json categories retain URLs and custom layout`() {
        val kind = ExploreKindsParser.parse("""[{"title":"分类","url":"https://example.org/list?page={{page}}","style":{"layout_flexBasisPercent":1}}]""").single()
        assertEquals("分类", kind.title)
        assertEquals("https://example.org/list?page={{page}}", kind.url)
        assertEquals(1f, kind.style().layout_flexBasisPercent)
        assertNull(kind.html)
    }

    @Test
    fun `text categories retain headings and both separators`() {
        val kinds = ExploreKindsParser.parse("分组\n热门::https://example.org/hot&&新书::https://example.org/new")
        assertEquals(listOf("分组", "热门", "新书"), kinds.map { it.title })
        assertNull(kinds.first().url)
        assertEquals("https://example.org/new", kinds.last().url)
    }
}
