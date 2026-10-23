package io.legado.app.ui.widget.code

import io.legado.app.ui.widget.code.SourceSyntaxHighlighter.Kind
import io.legado.app.ui.widget.code.SourceSyntaxHighlighter.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSyntaxHighlighterTest {
    private fun parts(text: String, language: Language = Language.RULE) =
        SourceSyntaxHighlighter.tokenize(text, language).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `JS strings comments and templates shield embedded keywords and rule markers`() {
        val text = "const a = \"if @js: //\"; /* return 42 */ let b = `true\n@@`; // const\nreturn 0x2a;"
        val tokens = parts(text, Language.JAVASCRIPT)
        assertEquals(listOf("const", "let", "return"), tokens.filter { it.second == Kind.KEYWORD }.map { it.first })
        assertEquals(2, tokens.count { it.second == Kind.COMMENT })
        assertEquals(2, tokens.count { it.second == Kind.STRING })
        assertTrue(tokens.contains("0x2a" to Kind.NUMBER))
        assertFalse(tokens.any { it.second == Kind.RULE })
    }

    @Test
    fun `JSON accepts unicode and punctuation in keys without highlighting string contents`() {
        val tokens = parts("{\"中文-key\": \"const // @js:\", \"enabled\": true, \"count\": 1.5e-2}", Language.JSON)
        assertTrue(tokens.contains("\"中文-key\"" to Kind.PROPERTY))
        assertTrue(tokens.contains("true" to Kind.KEYWORD))
        assertTrue(tokens.contains("1.5e-2" to Kind.NUMBER))
        assertFalse(tokens.any { it.second == Kind.COMMENT || it.second == Kind.RULE })
    }

    @Test
    fun `rule URLs and XPath are not interpreted as JS comments`() {
        val tokens = parts("https://example.invalid/{{key}} @XPath://div/@class || @css:span @@ text")
        assertFalse(tokens.any { it.second == Kind.COMMENT })
        assertEquals(listOf("{{", "}}", "@XPath:", "||", "@css:", "@@"), tokens.filter { it.second == Kind.RULE }.map { it.first })
    }

    @Test
    fun `JS regions close outside nested objects strings and comments`() {
        val tokens = parts("{{({a: '}}', b: {n: 1}}).b}} @css:div <js>let n = 2; /* </js> */</JS> @@text")
        assertEquals(listOf("{{", "}}", "@css:", "<js>", "</JS>", "@@"), tokens.filter { it.second == Kind.RULE }.map { it.first })
        assertTrue(tokens.contains("let" to Kind.KEYWORD))
    }

    @Test
    fun `JS regular expressions protect escaped slashes and character classes`() {
        val tokens = parts("const r = /[//]a\\/b/g; const n = 8 / 2; return /if/.test('a');", Language.JAVASCRIPT)
        assertTrue(tokens.contains("/[//]a\\/b/g" to Kind.STRING))
        assertTrue(tokens.contains("/if/" to Kind.STRING))
        assertFalse(tokens.any { it.second == Kind.COMMENT })
        assertTrue(tokens.contains("/" to Kind.OPERATOR))
    }

    @Test
    fun `unfinished input stays in bounds and recovers after newline`() {
        for (text in listOf("@js:'unfinished\nreturn 2", "@js:/* unfinished", "@js:`unfinished", "@js:\"escaped\\")) {
            val tokens = SourceSyntaxHighlighter.tokenize(text)
            assertTrue(tokens.all { it.start >= 0 && it.end > it.start && it.end <= text.length })
            assertTrue(tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
        }
        assertTrue(parts("@js:'unfinished\nreturn 2").contains("return" to Kind.KEYWORD))
    }

    @Test
    fun `long scripts highlight beyond 1024 characters and cap span count`() {
        val text = " ".repeat(4096) + "@js:const count = 42;"
        assertTrue(parts(text).contains("const" to Kind.KEYWORD))
        val many = "let n = 1;\n".repeat(20000)
        assertEquals(SourceSyntaxHighlighter.MAX_TOKENS, SourceSyntaxHighlighter.tokenize(many, Language.JAVASCRIPT).size)
    }

    @Test
    fun `fields select raw script JSON rules or plain text`() {
        assertEquals(Language.JAVASCRIPT, SourceSyntaxHighlighter.languageForField("loginCheckJs"))
        assertEquals(Language.JAVASCRIPT, SourceSyntaxHighlighter.languageForField("jsLib"))
        assertEquals(Language.JAVASCRIPT, SourceSyntaxHighlighter.languageForField("imageDecode"))
        assertEquals(Language.JSON, SourceSyntaxHighlighter.languageForField("header"))
        assertEquals(Language.PLAIN, SourceSyntaxHighlighter.languageForField("bookSourceName"))
        assertEquals(Language.RULE, SourceSyntaxHighlighter.languageForField("ruleContent"))
        assertEquals(Language.RULE, SourceSyntaxHighlighter.languageForField("chapterName"))
        assertTrue(parts("const 42", Language.PLAIN).isEmpty())
        assertTrue(parts("@js:return true", Language.JSON).contains("return" to Kind.KEYWORD))
    }
}
