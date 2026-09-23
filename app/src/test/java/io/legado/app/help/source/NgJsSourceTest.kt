package io.legado.app.help.source

import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class NgJsSourceTest {
    private val script = """
        // NG JS
        var config = {bookSourceUrl:'https://example.org/#ng', bookSourceName:'测试', enabledCookieJar:false};
        function search(key,page) {return [];}
        function getBookInfo(book) {return book;}
        function getChapters(book) {return [];}
        function getContent(chapter,book,next) {return '正文';}
        function loginUi(state) {return {rows:[]};}
        throw new Error('导入时不应执行');
    """.trimIndent()

    @Test fun importsWithoutExecutingAndRoundTripsThroughJson() {
        val source = BookSourceParser.parse("\uFEFF$script").single()
        assertEquals("测试", source.bookSourceName)
        assertFalse(source.enabledCookieJar!!)
        assertTrue(NgJsSource.isNg(source))
        assertEquals("[]", source.loginUi)
        assertEquals("$.title", source.ruleToc?.chapterName)
        val restored = BookSourceParser.parse(GSON.toJson(listOf(source))).single()
        assertEquals(source.jsLib, restored.jsLib)
        assertEquals(source.ruleSearch, restored.ruleSearch)
    }

    @Test fun rejectsExecutableConfigAndMissingFunctions() {
        assertThrows(IllegalStateException::class.java) {
            NgJsSource.parse(script.replace("bookSourceName:'测试'", "bookSourceName:java.ajax('https://example.org')"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NgJsSource.parse(script.replace("function getContent", "function other"))
        }
    }

    @Test fun keepsTraditionalJsonSources() {
        val source = BookSourceParser.parse("""[{"bookSourceUrl":"https://example.org","bookSourceName":"JSON","searchUrl":"/search"}]""").single()
        assertFalse(NgJsSource.isNg(source))
        assertEquals("/search", source.searchUrl)
        assertEquals(source, BookSourceParser.parse(GSON.toJson(source)).single())
    }

    @Test fun supportsEscapedConfigAndRejectsInvalidMetadata() {
        assertEquals("测试", NgJsSource.parse(script.replace("测试", "\\u6d4b\\u8bd5")).bookSourceName)
        assertThrows(IllegalArgumentException::class.java) {
            NgJsSource.parse(script.replace("bookSourceName:'测试'", "bookSourceName:null"))
        }
    }
}
