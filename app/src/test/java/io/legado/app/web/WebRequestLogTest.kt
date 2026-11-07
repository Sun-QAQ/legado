package io.legado.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebRequestLogTest {
    @Test
    fun `legacy access keys remain redacted from request logs`() {
        assertEquals(
            "path=image&access_token=<redacted>&width=100",
            WebRequestLog.redactQuery("path=image&access_token=secret&width=100")
        )
        assertEquals("%61ccess_token=<redacted>", WebRequestLog.redactQuery("%61ccess_token=secret"))
        assertEquals(null, WebRequestLog.redactQuery(null))
        assertEquals("path=image&width=100", WebRequestLog.redactQuery("path=image&width=100"))
    }
}
