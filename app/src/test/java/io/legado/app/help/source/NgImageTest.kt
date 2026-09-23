package io.legado.app.help.source

import org.junit.Assert.*
import org.junit.Test

class NgImageTest {
    @Test fun readsInlineStyleAndClickWithoutBreakingDataUri() {
        val src = "data:image/svg+xml;base64,PHN2Zy8+,{\"click\":\"show('1')\",\"style\":\"TEXT\"}"
        assertEquals("show('1')", NgImage.click(src))
        assertTrue(NgImage.hasInlineImage("正文<img src=\"$src\">"))
        assertFalse(NgImage.hasInlineImage("正文中出现 \"style\":\"TEXT\""))
        assertNull(NgImage.click("https://example.org/image.jpg,{broken}"))
        assertNull(NgImage.click("https://example.org/image.jpg"))
    }
}
