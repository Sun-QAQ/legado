package io.legado.app.lib.webdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavTest {

    @Test
    fun `当前目录有无末尾斜杠都能识别`() {
        assertTrue(isSameWebDavResource(
            "https://example.com/dav/GoogleDrive",
            "https://example.com/dav/GoogleDrive/"
        ))
        assertTrue(isSameWebDavResource(
            "https://example.com/dav/GoogleDrive/",
            "https://example.com/dav/GoogleDrive/"
        ))
        assertTrue(isSameWebDavResource("https://example.com", "https://example.com/"))
    }

    @Test
    fun `当前目录的中文空格和转义字符按解码后的路径比较`() {
        assertTrue(isSameWebDavResource(
            "https://example.com/dav/玄幻 小说/",
            "https://example.com/dav/%E7%8E%84%E5%B9%BB%20%E5%B0%8F%E8%AF%B4"
        ))
        assertTrue(isSameWebDavResource(
            "https://EXAMPLE.com:443/dav/GoogleDrive",
            "https://example.com/dav/%47oogleDrive/"
        ))
    }

    @Test
    fun `真实子目录和同名后缀目录不会被过滤`() {
        val current = "https://example.com/dav/GoogleDrive"
        assertFalse(isSameWebDavResource(current, "$current/玄幻小说/"))
        assertFalse(isSameWebDavResource(current, "${current}Backup/"))
        assertFalse(isSameWebDavResource(current, "$current/GoogleDrive/"))
    }

    @Test
    fun `保留路径大小写和转义斜杠的区别`() {
        assertFalse(isSameWebDavResource(
            "https://example.com/dav/GoogleDrive/",
            "https://example.com/dav/googledrive/"
        ))
        assertFalse(isSameWebDavResource(
            "https://example.com/dav/a%2Fb/",
            "https://example.com/dav/a/b/"
        ))
    }

    @Test
    fun `不同服务器和查询参数不是当前资源`() {
        val current = "https://example.com/dav/GoogleDrive/"
        assertFalse(isSameWebDavResource(current, "http://example.com/dav/GoogleDrive/"))
        assertFalse(isSameWebDavResource(current, "https://other.example/dav/GoogleDrive/"))
        assertFalse(isSameWebDavResource(current, "https://example.com:8443/dav/GoogleDrive/"))
        assertFalse(isSameWebDavResource(current, "${current}?version=2"))
    }
}
