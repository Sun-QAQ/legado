package io.legado.app.ui.book.explore

import io.legado.app.data.entities.BookSourcePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomRecommendSourceFilterTest {
    private val sources = listOf(
        BookSourcePart("first", "Alpha小说", "小说, 科幻"),
        BookSourcePart("second", "Beta漫画", "漫画；科幻"),
        BookSourcePart("third", "Gamma", "小说精选"),
        BookSourcePart("fourth", "Delta"),
        BookSourcePart("fifth", "Epsilon", " ， ; ")
    )

    @Test
    fun `splits all supported group separators and removes empty and repeated groups`() {
        val source = BookSourcePart(bookSourceGroup = " 小说,科幻；小说，漫画; ")
        assertEquals(listOf("小说", "科幻", "漫画"), RandomRecommendSourceFilter.groups(source))
    }

    @Test
    fun `group membership is exact and supports sources in multiple groups`() {
        assertEquals(listOf("first"), filtered(group = "小说"))
        assertEquals(listOf("first", "second"), filtered(group = "科幻"))
    }

    @Test
    fun `search matches names addresses and groups without case or surrounding whitespace`() {
        assertEquals(listOf("first"), filtered(query = "  aLpHa  "))
        assertEquals(listOf("second"), filtered(query = "SECOND"))
        assertEquals(listOf("first", "second"), filtered(query = "科幻"))
    }

    @Test
    fun `search group and selected filters intersect without changing source order`() {
        assertEquals(listOf("second"), filtered(query = "科幻", group = "漫画",
            onlySelected = true, selected = setOf("first", "second")))
        assertTrue(filtered(query = "Alpha", group = "漫画", onlySelected = true,
            selected = setOf("first", "second")).isEmpty())
        assertEquals(listOf("first", "second"), filtered(onlySelected = true,
            selected = setOf("second", "first")))
    }

    @Test
    fun `ungrouped includes null empty and delimiter only groups`() {
        assertEquals(listOf("fourth", "fifth"), filtered(group = ""))
        assertEquals(sources.map { it.bookSourceUrl }, filtered())
    }

    @Test
    fun `selected filter with no selected sources produces no rows`() {
        assertTrue(filtered(onlySelected = true).isEmpty())
        assertEquals(listOf("second"), filtered(group = "科幻", onlySelected = true,
            selected = setOf("second")))
        assertEquals(listOf("first", "second"), filtered(group = "科幻"))
    }

    private fun filtered(
        query: String = "",
        group: String? = null,
        onlySelected: Boolean = false,
        selected: Set<String> = emptySet()
    ) = RandomRecommendSourceFilter.filter(sources, query, group, onlySelected, selected)
        .map { it.bookSourceUrl }
}
