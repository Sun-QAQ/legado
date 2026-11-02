package io.legado.app.help.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceAiPlanTest {
    private val context = VoiceAiContext(
        listOf(VoiceAiBook("b0", "甲小说", "甲作者", 1L, "private://first"),
            VoiceAiBook("b1", "乙小说", "乙作者", 2L, "private://second")),
        listOf(VoiceAiGroup("g0", "漫画", 8)), "reader", "b1", "g0", listOf("b1", "b0")
    )

    @Test fun decodesMultiStepPlanWithExactSnapshotIdentity() {
        val result = VoiceAiProtocol.decode("""{"actions":[{"action":"open_book","book_id":"b1"},{"action":"eye_on"}]}""", context)
        assertEquals(VoiceAiDecision.Execute(listOf(VoiceCommand.OpenShelfBook("private://second"), VoiceCommand.EyePage(true))), result)
    }

    @Test fun ordinalAfterGroupSwitchUsesNewGroupOrder() {
        val result = VoiceAiProtocol.decode("""{"actions":[{"action":"switch_group","group_id":"g0"},{"action":"open_ordinal","number":4}]}""", context)
        assertEquals(VoiceAiDecision.Execute(listOf(VoiceCommand.SwitchShelfGroup(8), VoiceCommand.OpenOrdinal(4))), result)
    }

    @Test fun bareOrdinalUsesDisplayedSnapshotInsteadOfDatabaseIndex() {
        val result = VoiceAiProtocol.decode("""{"actions":[{"action":"open_ordinal","number":1}]}""", context)
        assertEquals(VoiceAiDecision.Execute(listOf(VoiceCommand.OpenShelfBook("private://second"))), result)
        assertNull(VoiceAiProtocol.decode("""{"actions":[{"action":"open_ordinal","number":3}]}""", context))
    }

    @Test fun rejectsEntirePlanIfTailIsUnknownOrReferencesUnknownEntity() {
        for (tail in listOf("""{"action":"delete_book"}""", """{"action":"open_book","book_id":"b999"}""",
            """{"action":"switch_group","group_id":"not-provided"}""")) {
            assertNull(VoiceAiProtocol.decode("""{"actions":[{"action":"bookshelf"},$tail]}""", context))
        }
    }

    @Test fun rejectsUrlsScriptsExtraFieldsAndNonStringIds() {
        for (action in listOf("""{"action":"open_book","bookUrl":"https://example.com"}""",
            """{"action":"eye_on","script":"anything"}""", """{"action":"open_book","book_id":1}""")) {
            assertNull(VoiceAiProtocol.decode("""{"actions":[$action]}""", context))
        }
    }

    @Test fun rejectsEmptyOversizedAndMixedCancelPlans() {
        assertNull(VoiceAiProtocol.decode("""{"actions":[]}""", context))
        assertNull(VoiceAiProtocol.decode("""{"actions":[{"action":"cancel"},{"action":"eye_on"}]}""", context))
        val actions = List(5) { """{"action":"next_page"}""" }.joinToString(",")
        assertNull(VoiceAiProtocol.decode("""{"actions":[$actions]}""", context))
        assertNull(VoiceAiProtocol.decode("x".repeat(16_001), context))
    }

    @Test fun clarificationPreservesTailAndRequiresDistinctValidOptions() {
        val json = """{"question":"哪本？","options":[{"label":"甲小说","actions":[{"action":"open_book","book_id":"b0"},{"action":"eye_on"}]},{"label":"乙小说","actions":[{"action":"open_book","book_id":"b1"},{"action":"eye_on"}]}]}"""
        val result = VoiceAiProtocol.decode(json, context) as VoiceAiDecision.Clarify
        assertEquals(2, result.options.size)
        assertEquals(VoiceCommand.EyePage(true), result.options[1].commands.last())
        assertNull(VoiceAiProtocol.decode(json.replace("b1", "missing"), context))
        assertNull(VoiceAiProtocol.decode(json.replace("乙小说", "甲小说"), context))
    }

    @Test fun allowsJsonFenceButRejectsNarrativeAndMixedResponseModes() {
        assertNotNull(VoiceAiProtocol.decode("```json\n{\"actions\":[{\"action\":\"bookshelf\"}]}\n```", context))
        assertNull(VoiceAiProtocol.decode("已经帮你完成了", context))
        assertNull(VoiceAiProtocol.decode("""{"actions":[{"action":"bookshelf"}],"unsupported":"no"}""", context))
    }

    @Test fun contextDoesNotExposeInternalUrlsAndHasReadableDates() {
        val json = context.toJson().toString()
        assertFalse(json.contains("private://"))
        assertFalse(json.contains("bookUrl"))
        assertTrue(json.contains("last_read_at"))
        assertTrue(json.contains("current_book_id"))
    }

    @Test fun executionStopsAfterFailureAndReportsCompletedSteps() = runBlocking {
        val seen = mutableListOf<VoiceCommand>()
        val result = VoicePlanRunner.run(listOf(VoiceCommand.OpenPage(false), VoiceCommand.EyePage(true), VoiceCommand.TurnPage(true)), {
            seen += it
            if (it is VoiceCommand.OpenPage) VoiceActionResult("已返回书架")
            else VoiceActionResult("请先打开书", status = VoiceActionStatus.FAILED)
        })
        assertEquals(2, seen.size)
        assertTrue(result.message.contains("已返回书架"))
        assertTrue(result.message.contains("后续操作未执行"))
    }

    @Test fun permissionOrCalibrationRequirementStopsRemainingSteps() = runBlocking {
        var calls = 0
        VoicePlanRunner.run(listOf(VoiceCommand.EyePage(true), VoiceCommand.TurnPage(true)), {
            calls++; VoiceActionResult("请先授权", status = VoiceActionStatus.NEEDS_USER_INPUT)
        })
        assertEquals(1, calls)
    }

    @Test fun bookChoiceRetainsRemainingCommands() = runBlocking {
        val outcome = VoicePlanRunner.run(listOf(VoiceCommand.OpenBook("小说"), VoiceCommand.EyePage(true)), {
            VoiceActionResult("选哪个", listOf("甲" to VoiceCommand.OpenShelfBook("private://first")))
        })
        assertEquals(listOf(VoiceCommand.OpenShelfBook("private://first"), VoiceCommand.EyePage(true)), outcome.choices.single().commands)
    }

    @Test fun exceptionsDoNotLosePartialSuccess() = runBlocking {
        var calls = 0
        val outcome = VoicePlanRunner.run(listOf(VoiceCommand.OpenPage(false), VoiceCommand.OpenPage(true)), {
            if (++calls == 1) VoiceActionResult("已返回书架") else error("database unavailable")
        })
        assertTrue(outcome.message.startsWith("已返回书架"))
        assertTrue(outcome.message.contains("第2步执行失败"))
    }

    @Test fun cancellationNeverExecutesFollowingStep() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            VoicePlanRunner.run(listOf(VoiceCommand.OpenPage(false), VoiceCommand.OpenPage(true)), {
                calls++; entered.complete(Unit); awaitCancellation()
            })
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(1, calls)
    }
}
