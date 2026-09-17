package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPersonaSelectionTest {

    @Test
    fun `重启恢复已选人格且删除后回退默认人格`() {
        var persistedId = 0L
        AgentPersonaSelection(
            load = { persistedId },
            save = { persistedId = it }
        ).select(202L)

        val restarted = AgentPersonaSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        assertEquals(202L, restarted.resolve(setOf(101L, 202L)))
        assertEquals(0L, restarted.resolve(setOf(101L)))
        assertEquals(0L, persistedId)
    }
}
