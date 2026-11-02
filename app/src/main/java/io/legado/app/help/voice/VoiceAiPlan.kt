package io.legado.app.help.voice

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

data class VoicePlanChoice(val label: String, val commands: List<VoiceCommand>)

sealed interface VoiceAiDecision {
    data class Execute(val commands: List<VoiceCommand>) : VoiceAiDecision
    data class Clarify(val question: String, val options: List<VoicePlanChoice>) : VoiceAiDecision
    data class Unsupported(val message: String) : VoiceAiDecision
}

data class VoiceAiBook(
    val id: String, val name: String, val author: String, val lastReadAt: Long,
    val bookUrl: String,
    val groupIds: List<String> = emptyList()
)

data class VoiceAiGroup(val id: String, val name: String, val groupId: Long)

/** Opaque per-request IDs keep source URLs and internal database identifiers off the wire. */
data class VoiceAiContext(
    val books: List<VoiceAiBook>,
    val groups: List<VoiceAiGroup>,
    val page: String,
    val currentBookId: String? = null,
    val currentGroupId: String? = null,
    val visibleBookIds: List<String> = emptyList(),
    val totalBooks: Int = books.size,
    val now: Long = System.currentTimeMillis(),
    val timeZone: String = java.util.TimeZone.getDefault().id
) {
    fun toJson(): JsonObject {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).apply {
            this.timeZone = java.util.TimeZone.getTimeZone(this@VoiceAiContext.timeZone)
        }
        return JsonObject().apply {
            addProperty("page", page)
            addProperty("current_book_id", currentBookId)
            addProperty("current_group_id", currentGroupId)
            addProperty("current_time", date.format(java.util.Date(now)))
            addProperty("time_zone", timeZone)
            addProperty("total_books", totalBooks)
            addProperty("books_truncated", books.size < totalBooks)
            add("visible_book_ids", JsonArray().apply { visibleBookIds.forEach(::add) })
            add("books", JsonArray().apply {
                books.forEach { book -> add(JsonObject().apply {
                    addProperty("id", book.id)
                    addProperty("name", book.name.take(120))
                    addProperty("author", book.author.take(80))
                    add("group_ids", JsonArray().apply { book.groupIds.forEach(::add) })
                    if (book.lastReadAt > 0) addProperty("last_read_at", date.format(java.util.Date(book.lastReadAt)))
                }) }
            })
            add("groups", JsonArray().apply { groups.forEach { group -> add(JsonObject().apply {
                addProperty("id", group.id)
                addProperty("name", group.name.take(120))
            }) } })
        }
    }
}

/** Validate the whole plan before any operation runs. A malformed tail never runs a valid prefix. */
object VoiceAiProtocol {
    private fun JsonObject.string(key: String, limit: Int): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?.trim()?.takeIf { it.isNotEmpty() && it.length <= limit }

    fun decode(content: String, context: VoiceAiContext): VoiceAiDecision? = runCatching {
        if (content.length > 16_000) return null
        val text = content.trim().let {
            if (it.startsWith("```json\n") && it.endsWith("```")) it.removePrefix("```json\n").removeSuffix("```").trim()
            else if (it.startsWith("```\n") && it.endsWith("```")) it.removePrefix("```\n").removeSuffix("```").trim()
            else it
        }
        val json = JsonParser.parseString(text).asJsonObject
        when {
            json.keySet() == setOf("actions") -> parseActions(json["actions"], context)?.let(VoiceAiDecision::Execute)
            json.keySet() == setOf("question", "options") -> {
                val question = json.string("question", 160) ?: return null
                val options = json["options"].takeIf { it.isJsonArray }?.asJsonArray ?: return null
                if (options.size() !in 2..5) return null
                val choices = options.map { value ->
                    val option = value.asJsonObject
                    if (option.keySet() != setOf("label", "actions")) return null
                    val label = option.string("label", 100) ?: return null
                    val commands = parseActions(option["actions"], context) ?: return null
                    VoicePlanChoice(label, commands)
                }
                if (choices.map { it.label }.distinct().size != choices.size) return null
                if (choices.map { it.commands }.distinct().size != choices.size) return null
                VoiceAiDecision.Clarify(question, choices)
            }
            json.keySet() == setOf("unsupported") -> json.string("unsupported", 160)?.let(VoiceAiDecision::Unsupported)
            else -> null
        }
    }.getOrNull()

    private fun parseActions(value: JsonElement?, context: VoiceAiContext): List<VoiceCommand>? {
        val actions = value?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (actions.size() !in 1..4) return null
        var switchedGroup = false
        val result = actions.map { element ->
            val action = element.asJsonObject
            val kind = action.string("action", 40) ?: return null
            when (kind) {
                "open_book" -> {
                    if (action.keySet() != setOf("action", "book_id")) return null
                    val book = context.books.singleOrNull { it.id == action.string("book_id", 20) } ?: return null
                    VoiceCommand.OpenShelfBook(book.bookUrl)
                }
                "switch_group" -> {
                    if (action.keySet() != setOf("action", "group_id")) return null
                    val group = context.groups.singleOrNull { it.id == action.string("group_id", 20) } ?: return null
                    switchedGroup = true
                    VoiceCommand.SwitchShelfGroup(group.groupId)
                }
                "open_ordinal" -> {
                    if (action.keySet() != setOf("action", "number")) return null
                    val number = action["number"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?.asString?.toIntOrNull()?.takeIf { it in 1..999 } ?: return null
                    if (switchedGroup) VoiceCommand.OpenOrdinal(number) else {
                        val id = context.visibleBookIds.getOrNull(number - 1) ?: return null
                        val book = context.books.singleOrNull { it.id == id } ?: return null
                        VoiceCommand.OpenShelfBook(book.bookUrl)
                    }
                }
                else -> {
                    if (action.keySet() != setOf("action")) return null
                    when (kind) {
                        "bookshelf" -> VoiceCommand.OpenPage(false)
                        "discovery" -> VoiceCommand.OpenPage(true)
                        "next_page" -> VoiceCommand.TurnPage(true)
                        "previous_page" -> VoiceCommand.TurnPage(false)
                        "eye_on" -> VoiceCommand.EyePage(true)
                        "eye_off" -> VoiceCommand.EyePage(false)
                        "cancel" -> VoiceCommand.Cancel
                        else -> return null
                    }
                }
            }
        }
        if (VoiceCommand.Cancel in result && result.size != 1) return null
        return result
    }
}

data class VoicePlanOutcome(val message: String, val choices: List<VoicePlanChoice> = emptyList())

object VoicePlanRunner {
    suspend fun run(
        commands: List<VoiceCommand>,
        execute: suspend (VoiceCommand) -> VoiceActionResult,
        progress: (Int, Int) -> Unit = { _, _ -> }
    ): VoicePlanOutcome {
        require(commands.size in 1..4)
        val completed = mutableListOf<String>()
        for ((index, command) in commands.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(index + 1, commands.size)
            val result = try { execute(command) } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                VoiceActionResult("第${index + 1}步执行失败，请重试", status = VoiceActionStatus.FAILED)
            }
            currentCoroutineContext().ensureActive()
            if (result.status != VoiceActionStatus.SUCCESS) {
                val remaining = commands.drop(index + 1)
                val message = (completed + result.message).joinToString("；")
                return VoicePlanOutcome(
                    message + if (remaining.isNotEmpty() && result.choices.isEmpty()) "。后续操作未执行" else "",
                    result.choices.map { (label, chosen) -> VoicePlanChoice(label, listOf(chosen) + remaining) }
                )
            }
            completed += result.message
        }
        return VoicePlanOutcome(completed.joinToString("；"))
    }
}
