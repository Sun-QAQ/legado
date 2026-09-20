package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentInputInsetsTest {

    @Test
    fun `键盘显示时避让键盘并始终避让底栏`() {
        assertEquals(
            1010,
            resolveAgentInputBottomMargin(
                imeHeight = 1000,
                navigationBarHeight = 80,
                bottomBarOffset = 88,
                keyboardGap = 10
            )
        )
        assertEquals(
            168,
            resolveAgentInputBottomMargin(
                imeHeight = 0,
                navigationBarHeight = 80,
                bottomBarOffset = 88,
                keyboardGap = 10
            )
        )
        assertEquals(
            168,
            resolveAgentInputBottomMargin(
                imeHeight = 50,
                navigationBarHeight = 80,
                bottomBarOffset = 88,
                keyboardGap = 10
            )
        )
    }
}
