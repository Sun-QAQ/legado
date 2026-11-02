package io.legado.app.help.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun parsesRequestedCommands() {
        assertEquals(VoiceCommand.OpenBook("遮天"), VoiceCommandParser.parse("打开遮天"))
        assertEquals(VoiceCommand.OpenPage(true), VoiceCommandParser.parse("打开发现"))
        assertEquals(VoiceCommand.SwitchGroup("漫画"), VoiceCommandParser.parse("切换到漫画分组"))
        assertEquals(VoiceCommand.OpenOrdinal(4), VoiceCommandParser.parse("打开第4本书"))
        assertEquals(VoiceCommand.TurnPage(true), VoiceCommandParser.parse("下一页"))
        assertEquals(VoiceCommand.EyePage(true), VoiceCommandParser.parse("打开眼动翻页"))
    }

    @Test fun handlesWakePrefixPunctuationAndPoliteRequests() {
        assertEquals(VoiceCommand.OpenBook("遮天"), VoiceCommandParser.parse("你好小悦，请帮我打开《遮天》，谢谢。"))
        assertEquals(VoiceCommand.TurnPage(false), VoiceCommandParser.parse("请往前翻一下。"))
    }

    @Test fun supportsChineseOrdinals() {
        for ((spoken, expected) in mapOf("四" to 4, "十二" to 12, "二十" to 20, "一百零四" to 104, "一百一十四" to 114)) {
            assertEquals(VoiceCommand.OpenOrdinal(expected), VoiceCommandParser.parse("打开第${spoken}本书"))
        }
    }

    @Test fun invalidAndOutOfRangeOrdinalsDoNotBecomeBookNames() {
        for (spoken in listOf("0", "1000", "一二", "一百零十")) {
            assertNull(VoiceCommandParser.parse("打开第${spoken}本书"))
        }
    }

    @Test fun negatedRequestsNeverMatch() {
        for (text in listOf("不要下一页", "不要打开遮天", "请不要开启眼动翻页", "不用切换到漫画分组")) {
            assertNull(VoiceCommandParser.parse(text))
            assertTrue(VoiceCommandParser.isNegated(text))
        }
    }

    @Test fun supportsAliasesAndDisablingFeatures() {
        assertEquals(VoiceCommand.EyePage(false), VoiceCommandParser.parse("关闭眼动翻页"))
        assertEquals(VoiceCommand.OpenPage(false), VoiceCommandParser.parse("回到书架"))
        assertEquals(VoiceCommand.Cancel, VoiceCommandParser.parse("算了"))
    }

    @Test fun doesNotClaimUnsupportedCommands() {
        assertNull(VoiceCommandParser.parse("删除第四本书"))
        assertNull(VoiceCommandParser.parse("把亮度调低一点"))
        assertNull(VoiceCommandParser.parse(""))
    }

    @Test fun followupChoiceIsOneBasedAndBounded() {
        assertEquals(2, VoiceCommandParser.choice("选第二个"))
        assertEquals(4, VoiceCommandParser.choice("第4本"))
        assertNull(VoiceCommandParser.choice("0"))
        assertNull(VoiceCommandParser.choice("下一页"))
    }

    @Test fun exactTitleWinsOverPartialMatches() {
        assertEquals(listOf("遮天"), VoiceEntityMatcher.match("遮天", listOf("遮天", "遮天续集")) { it })
    }

    @Test fun keepsAllVersionsForClarification() {
        val books = listOf("遮天" to "甲", "遮天" to "乙")
        assertEquals(books, VoiceEntityMatcher.match("遮天", books) { it.first })
    }

    @Test fun emptyAndMissingTitlesDoNotMatchEverything() {
        assertTrue(VoiceEntityMatcher.match("", listOf("遮天")) { it }.isEmpty())
        assertTrue(VoiceEntityMatcher.match("不存在", listOf("遮天")) { it }.isEmpty())
    }

    @Test fun partialTitlesProduceCandidatesWithoutChangingOrder() {
        assertEquals(listOf("三体一", "三体二"), VoiceEntityMatcher.match("三体", listOf("三体一", "遮天", "三体二")) { it })
    }

    @Test fun aiCanOnlyReturnSupportedActions() {
        assertEquals(VoiceCommand.OpenBook("遮天"), VoiceAiInterpreter.decode("""{"action":"open_book","name":"遮天"}"""))
        assertEquals(VoiceCommand.TurnPage(true), VoiceAiInterpreter.decode("""{"action":"next_page"}"""))
        assertNull(VoiceAiInterpreter.decode("""{"action":"delete_book","name":"遮天"}"""))
        assertNull(VoiceAiInterpreter.decode("""{"action":"open_shelf_book","bookUrl":"some-url"}"""))
    }

    @Test fun rejectsMalformedAndInvalidAiArguments() {
        assertNull(VoiceAiInterpreter.decode("已经帮你打开了"))
        assertNull(VoiceAiInterpreter.decode("""{"action":"open_book","name":" "}"""))
        assertNull(VoiceAiInterpreter.decode("""{"action":"open_ordinal","number":0}"""))
        assertNull(VoiceAiInterpreter.decode("""{"action":"open_ordinal","number":2.5}"""))
        assertNull(VoiceAiInterpreter.decode("""{"action":"unknown"}"""))
    }
}
