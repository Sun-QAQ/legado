package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRepositoryTest {

    @Test
    fun `解析仓库搜索卡片并只生成固定域名下载地址`() {
        val html = """
            <div class="ylist">
              <input class="class_one" value="7840">
              <h2>
                <a href="/yuedu/shuyuan/content/id/7840.html">Wenku8 https://www.wenku8.net</a>
                <p class="m-right">2026-09-16</p>
              </h2>
              <span>3.X</span><span>发 搜 图</span>
              <span title="UID:123">用户: 测试者</span><span>下载: 456</span>
            </div>
        """.trimIndent()

        val item = SourceRepository.parseSearchResults(html, 10).single()

        assertEquals(7840L, item.id)
        assertEquals("Wenku8", item.name)
        assertEquals("https://www.wenku8.net", item.sourceUrl)
        assertEquals(listOf("发现", "搜索", "图片"), item.capabilities)
        assertEquals("测试者", item.author)
        assertEquals(456, item.downloads)
        assertEquals(
            "https://www.yckceo.com/yuedu/shuyuan/json/id/7840.json",
            item.jsonUrl
        )
    }

    @Test
    fun `规范化用户转义的域名并限制结果数量`() {
        val html = (1..3).joinToString("") { id ->
            """<div class="ylist"><input class="class_one" value="$id"><h2><a>源$id</a></h2></div>"""
        }

        assertEquals(
            "www.example.com",
            SourceRepository.normalizeQuery("https://www\\.example.com/books/123")
        )
        assertEquals(2, SourceRepository.parseSearchResults(html, 2).size)
        assertTrue(SourceRepository.parseSearchResults("", 10).isEmpty())
    }
}
