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

    @Test
    fun `重新读取管理页修改后的人格`() {
        var persistedId = 101L
        val selection = AgentPersonaSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        persistedId = 202L

        assertEquals(202L, selection.reload())
    }

    @Test
    fun `默认人格始终可以被选择`() {
        var persistedId = 202L
        val selection = AgentPersonaSelection(
            load = { persistedId },
            save = { persistedId = it }
        )

        selection.select(0L)

        assertEquals(0L, selection.resolve(emptySet()))
        assertEquals(0L, persistedId)
    }
}
