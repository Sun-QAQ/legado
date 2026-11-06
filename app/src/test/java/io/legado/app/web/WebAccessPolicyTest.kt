package io.legado.app.web

import org.junit.Assert.*
import org.junit.Test

class WebAccessPolicyTest {
    private val origin = "http://reader.test:8080"
    private fun enabled() = WebAccessPolicy().apply { configure(true, "test-key") }

    @Test fun `disabled authentication preserves old clients`() {
        assertTrue(WebAccessPolicy().authorize(null, null, "http://other.test", false))
    }

    @Test fun `key is required for native clients and query is restricted`() {
        val policy = enabled()
        assertFalse(policy.authorize(null, null, null, false))
        assertFalse(policy.authorize("Bearer wrong", null, null, false))
        assertTrue(policy.authorize("Bearer test-key", null, null, false))
        assertFalse(policy.authorize(null, "test-key", null, false))
        assertTrue(policy.authorize(null, "test-key", null, true))
    }

    @Test fun `browser origin must pair with the correct key`() {
        val policy = enabled()
        assertFalse(policy.authorize("Bearer test-key", null, origin, false))
        assertFalse(policy.pair("wrong", origin))
        assertTrue(policy.pair("test-key", origin))
        assertTrue(policy.allowsOrigin(origin))
        assertTrue(policy.authorize("Bearer test-key", null, origin, false))
        assertTrue(policy.authorize(null, "test-key", origin, true))
        assertFalse(policy.authorize(null, "test-key", "http://unpaired.test", true))
    }

    @Test fun `reset revokes old credentials and paired origins`() {
        val policy = enabled()
        policy.pair("test-key", origin)
        policy.configure(true, "new-key")
        assertFalse(policy.allowsOrigin(origin))
        assertFalse(policy.authorize("Bearer test-key", null, null, false))
        assertTrue(policy.pair("new-key", origin))
        assertTrue(policy.authorize("Bearer new-key", null, origin, false))
        policy.configure(false, "")
        policy.configure(true, "new-key")
        assertFalse(policy.allowsOrigin(origin))
    }

    @Test fun `malformed opaque and non-web origins cannot pair`() {
        val policy = enabled()
        listOf("null", "file:///local", "http://reader.test/path", "http://user@reader.test", "https://reader.test?x=1").forEach {
            assertFalse(policy.pair("test-key", it))
        }
    }

    @Test fun `only static assets are public and token is redacted from logs`() {
        listOf("/", "/vue/", "/vue/index.html", "/assets/a.js").forEach { assertTrue(WebAccessPolicy.isPublicAsset(it)) }
        listOf("/getBookshelf", "/cover", "/aiChat", "/addLocalBook").forEach { assertFalse(WebAccessPolicy.isPublicAsset(it)) }
        assertEquals("path=image&access_token=<redacted>&width=100", WebAccessPolicy.redactQuery("path=image&access_token=secret&width=100"))
        assertEquals("%61ccess_token=<redacted>", WebAccessPolicy.redactQuery("%61ccess_token=secret"))
        assertEquals(64, WebAccessPolicy.newKey().length)
        assertNotEquals(WebAccessPolicy.newKey(), WebAccessPolicy.newKey())
    }
}
