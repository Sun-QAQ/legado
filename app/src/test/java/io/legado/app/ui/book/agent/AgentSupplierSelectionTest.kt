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
}
