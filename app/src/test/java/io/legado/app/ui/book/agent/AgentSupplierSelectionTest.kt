package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSupplierSelectionTest {

    @Test
    fun `重新创建后恢复上次选择的供应商`() {
        var persistedId = 0L
        val firstSession = AgentSupplierSelection(
            load = { persistedId },
            save = { persistedId = it }
        )
        firstSession.select(202L)

        val restartedSession = AgentSupplierSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        assertEquals(202L, restartedSession.currentId)
    }

    @Test
    fun `重新读取外部修改后的供应商`() {
        var persistedId = 101L
        val selection = AgentSupplierSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        persistedId = 202L

        assertEquals(202L, selection.reload())
    }

    @Test
    fun `当前供应商不可用时回退到第一个可用项`() {
        var persistedId = 101L
        val selection = AgentSupplierSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        val resolvedId = selection.resolve(listOf(202L, 303L))

        assertEquals(202L, resolvedId)
        assertEquals(202L, persistedId)
    }

    @Test
    fun `没有可用供应商时清除选择`() {
        var persistedId = 101L
        val selection = AgentSupplierSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        val resolvedId = selection.resolve(emptyList())

        assertEquals(0L, resolvedId)
        assertEquals(0L, persistedId)
    }
}
