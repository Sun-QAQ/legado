package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentImageSourceSelectionTest {

    @Test
    fun `默认供应商不可用时自动选择首个已启用供应商`() {
        var saved = 99L
        val selection = AgentImageSourceSelection({ saved }, { saved = it })

        assertEquals(7L, selection.resolve(listOf(7L, 8L)))
        assertEquals(7L, saved)
    }

    @Test
    fun `没有已启用供应商时清空默认选择`() {
        var saved = 7L
        val selection = AgentImageSourceSelection({ saved }, { saved = it })

        assertEquals(0L, selection.resolve(emptyList()))
        assertEquals(0L, saved)
    }
}
