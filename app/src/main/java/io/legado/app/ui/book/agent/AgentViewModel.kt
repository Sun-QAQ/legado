package io.legado.app.ui.book.agent

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Agent 对话
 */
class AgentViewModel(application: Application) : BaseViewModel(application) {

    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages

    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting

    private val _supplierName = MutableStateFlow("")
    val supplierName: StateFlow<String> = _supplierName

    private val _currentSupplierId = MutableStateFlow(0L)
    val currentSupplierId: StateFlow<Long> = _currentSupplierId

    private val history = arrayListOf<ChatTurn>()
    private var selectedSupplierId: Long = 0L
    private var lastBooks: List<SearchBook> = emptyList()

    fun selectSupplier(id: Long, name: String) {
        selectedSupplierId = id
        _currentSupplierId.value = id
        _supplierName.value = name
    }

    fun clearChat() {
        history.clear()
        _messages.value = emptyList()
    }

    fun send(text: String) {
        val key = text.trim()
        if (key.isEmpty() || _waiting.value) return
        addUserMessage(key)
        viewModelScope.launch {
            val supplier = if (selectedSupplierId > 0) {
                appDb.aiSourceDao.get(selectedSupplierId)
            } else {
                appDb.aiSourceDao.allEnabled.firstOrNull()
            }
            if (supplier == null || supplier.model.isBlank()) {
                searchDirect(key, extractSearchKey(key))
            } else {
                agentLoop(supplier, key)
            }
        }
    }

    fun searchBook(key: String) {
        if (key.isBlank() || _waiting.value) return
        addUserMessage(key)
        viewModelScope.launch {
            searchDirect(key)
        }
    }

    private fun addUserMessage(text: String) {
        history.add(ChatTurn(ROLE_USER, text))
        _messages.value = _messages.value + AgentMessage(true, text)
    }

    private fun addAgentMessage(text: String, books: List<SearchBook> = emptyList()) {
        history.add(ChatTurn(ROLE_ASSISTANT, text))
        _messages.value = _messages.value + AgentMessage(false, text, books)
    }

    private suspend fun searchDirect(key: String, searchKey: String = key) {
        _waiting.value = true
        try {
            val books = withContext(Dispatchers.IO) {
                searchBooks(searchKey, 1)
            }
            if (books.isEmpty()) {
                addAgentMessage(getString(R.string.agent_no_result))
            } else {
                addAgentMessage("", books)
            }
        } catch (e: Exception) {
            context.toastOnUi(e.localizedMessage ?: e.message ?: "搜索失败")
            addAgentMessage(e.localizedMessage ?: "搜索失败")
        } finally {
            _waiting.value = false
        }
    }

    /**
     * Agent 循环：模型决定是否调用搜索工具，最多执行 3 轮工具调用
     */
    private suspend fun agentLoop(supplier: io.legado.app.data.entities.AiSource, key: String) {
        _waiting.value = true
        try {
            var hasBooks = false
            var finalText = ""
            lastBooks = emptyList()
            var finished = false
            for (round in 0 until 3) {
                if (finished) break
                val request = buildChatRequest(supplier)
                val response = withContext(Dispatchers.IO) {
                    chatCompletion(supplier, request)
                }
                val message = response.get("message").asJsonObject
                val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (content.isNotBlank()) {
                    finalText = content
                }
                val toolCalls = message.get("tool_calls")?.takeIf { it.isJsonArray }
                if (toolCalls == null || toolCalls.asJsonArray.size() == 0) {
                    addAgentMessage(finalText, if (hasBooks) lastBooks else emptyList())
                    finished = true
                    break
                }
                for (call: JsonElement in toolCalls.asJsonArray) {
                    val callObject = call.asJsonObject
                    val toolCallId =
                        callObject.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val function = callObject.get("function").asJsonObject
                    val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val arguments =
                        function.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    if (name == "search_books") {
                        val query = parseQuery(arguments)
                        if (query.isNotBlank()) {
                            val books = withContext(Dispatchers.IO) {
                                searchBooks(query, 1)
                            }
                            hasBooks = hasBooks || books.isNotEmpty()
                            lastBooks = books
                            history.add(
                                ChatTurn(
                                    ROLE_TOOL,
                                    GSON.toJson(books.map { it.toToolResult() }),
                                    toolCallId
                                )
                            )
                        }
                    }
                }
            }
            if (!finished) {
                addAgentMessage(finalText.ifBlank { getString(R.string.agent_waiting) })
            }
        } catch (e: Exception) {
            AppLog.put("Agent 对话出错", e)
            addAgentMessage(e.localizedMessage ?: e.message ?: "请求失败")
        } finally {
            _waiting.value = false
        }
    }

    private fun buildChatRequest(supplier: io.legado.app.data.entities.AiSource): JsonObject {
        val root = JsonObject()
        root.addProperty("model", supplier.model)
        val messages = JsonArray()
        messages.add(
            JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", SYSTEM_PROMPT)
            }
        )
        history.forEach { turn ->
            messages.add(
                JsonObject().apply {
                    addProperty("role", turn.role)
                    addProperty("content", turn.content)
                    if (turn.role == ROLE_TOOL) {
                        addProperty("tool_call_id", turn.toolCallId.orEmpty())
                    }
                }
            )
        }
        root.add("messages", messages)
        root.add("tools", searchTools())
        return root
    }

    private fun searchTools(): JsonArray {
        val tools = JsonArray()
        val function = JsonObject()
        function.addProperty("name", "search_books")
        function.addProperty("description", "根据书名搜索书籍，返回搜索结果列表")
        val parameters = JsonObject()
        val properties = JsonObject()
        properties.add(
            "query",
            JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "书名关键字")
            }
        )
        parameters.add("properties", properties)
        parameters.add("required", JsonArray().apply { add("query") })
        parameters.addProperty("type", "object")
        function.add("parameters", parameters)
        tools.add(JsonObject().apply {
            addProperty("type", "function")
            add("function", function)
        })
        return tools
    }

    private suspend fun chatCompletion(
        supplier: io.legado.app.data.entities.AiSource,
        body: JsonObject
    ): JsonObject {
        val client = okHttpClient.newBuilder()
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        val headers = HashMap(supplier.getHeaderMap())
        if (supplier.apiKey.isNotBlank() && !headers.containsKey("Authorization")) {
            headers["Authorization"] = if (supplier.apiKey.startsWith("Bearer ")) {
                supplier.apiKey
            } else {
                "Bearer ${supplier.apiKey}"
            }
        }
        val response = client.newCallStrResponse {
            addHeaders(headers)
            url(supplier.baseUrl.trimEnd('/') + "/chat/completions")
            post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
        }
        val json = JsonParser.parseString(response.body).asJsonObject
        val choice = json.getAsJsonArray("choices")[0].asJsonObject
        return choice.getAsJsonObject("message")
    }

    /**
     * 搜索书籍，返回合并后的结果
     */
    private suspend fun searchBooks(key: String, page: Int): List<SearchBook> {
        val sources = appDb.bookSourceDao.allEnabled
        if (sources.isEmpty()) return emptyList()
        val result = arrayListOf<SearchBook>()
        withTimeout(120_000L) {
            sources.forEach { source ->
                kotlin.runCatching {
                    val books = WebBook.searchBookAwait(source, key, page)
                    if (books.isNotEmpty()) {
                        result.addAll(books)
                    }
                }.onFailure {
                    AppLog.put("Agent 搜索出错 ${source.bookSourceName}", it)
                }
                if (result.size >= 30) {
                    return@forEach
                }
            }
        }
        kotlin.runCatching {
            appDb.searchBookDao.insert(*result.toTypedArray())
        }
        return result.sortedByDescending { it.originOrder }.take(30)
    }

    private fun parseQuery(arguments: String): String {
        return kotlin.runCatching {
            val obj = JsonParser.parseString(arguments).asJsonObject
            obj.get("query")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        }.getOrDefault("")
    }

    /**
     * 从自然语言中提取书名
     */
    private fun extractSearchKey(text: String): String {
        val bracket = Regex("《([^》]+)》").find(text)
        if (bracket != null) return bracket.groupValues[1].trim()
        val quote = Regex("[\"“]([^\"”]+)[\"”]").find(text)
        if (quote != null) return quote.groupValues[1].trim()
        val search = Regex("(?:搜索|查找|找一下|帮我找)(?:一下|一本|这本|书籍|小说)?([^，。！？,.!?]{2,30})")
            .find(text)
        if (search != null) {
            val key = search.groupValues[1]
                .replace("这本小说", "")
                .replace("的小说", "")
                .trim()
            if (key.isNotBlank()) return key
        }
        return text
    }

    private fun SearchBook.toToolResult(): Map<String, Any?> {
        return mapOf(
            "bookUrl" to bookUrl,
            "origin" to origin,
            "originName" to originName,
            "name" to name,
            "author" to author,
            "kind" to kind,
            "coverUrl" to coverUrl,
            "intro" to intro,
            "wordCount" to wordCount,
            "latestChapterTitle" to latestChapterTitle,
            "tocUrl" to tocUrl
        )
    }

    private fun getString(resId: Int): String {
        return context.getString(resId)
    }

    data class ChatTurn(
        val role: String,
        val content: String,
        val toolCallId: String? = null
    )

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"
        private const val SYSTEM_PROMPT =
            "你是阅读App中的AI助手，可以用中文与用户对话。" +
                    "当用户要求搜索书籍时，调用 search_books 工具并简要说明搜索结果。" +
                    "工具结果会以卡片形式展示给用户，回答时不要重复完整书籍列表。"
    }

}
