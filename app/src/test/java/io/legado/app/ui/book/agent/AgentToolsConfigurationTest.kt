package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsConfigurationTest {

    @Test
    fun `禁用工具会从模型声明和摘要中移除`() {
        val disabled = setOf("web_search", "generate_image")

        val json = AgentTools.toJsonArray(disabled)
        val names = json.map {
            it.asJsonObject.getAsJsonObject("function").get("name").asString
        }

        assertFalse("web_search" in names)
        assertFalse("generate_image" in names)
        assertFalse(AgentTools.overview(disabled).contains("web_search"))
        assertNull(AgentTools.find("web_search", disabled))
        assertTrue(AgentTools.infos(disabled).first { it.name == "web_search" }.enabled.not())
    }

    @Test
    fun `默认启用全部工具`() {
        assertEquals(AgentTools.infos().size, AgentTools.toJsonArray().size())
        assertTrue(AgentTools.infos().all { it.enabled })
    }
}
