package io.legado.app.ui.book.agent

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.max

/**
 * Agent 对话
 */
class AgentViewModel(application: Application) : BaseViewModel(application), AgentToolContext {

    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages

    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText

    private val _supplierName = MutableStateFlow("")
    val supplierName: StateFlow<String> = _supplierName

    private val _currentSupplierId = MutableStateFlow(0L)
    val currentSupplierId: StateFlow<Long> = _currentSupplierId

    private val history = arrayListOf<ChatTurn>()
    private var selectedSupplierId: Long = 0L
    private var lastBooks: List<SearchBook> = emptyList()
    private var lastSearchKey = ""
    private var lastSearchGroup = ""
    private var searchedSourceCount = 0
    private var accumulatedSearchBooks: List<SearchBook> = emptyList()
    private var canLoadMoreSearch = false
    private var hasBooks = false
    private var lastDisplayLimit = SEARCH_PAGE_SIZE
    private var currentSupplier: io.legado.app.data.entities.AiSource? = null
    private val activeSteps = arrayListOf<AgentStep>()
    private val stepStartTimes = HashMap<String, Long>()
    private var stepSequence = 0L
    private var liveReply: AgentMessage? = null
    private var activeJob: Job? = null
    private var currentCall: okhttp3.Call? = null
    private var cancelRequested = false

    fun selectSupplier(id: Long, name: String) {
        selectedSupplierId = id
        _currentSupplierId.value = id
        _supplierName.value = name
    }

    fun clearChat() {
        activeSteps.clear()
        stepStartTimes.clear()
        history.clear()
        liveReply = null
        _streamingText.value = null
        cancelRequested = false
        currentCall?.cancel()
        currentCall = null
        _messages.value = emptyList()
        lastSearchKey = ""
        lastSearchGroup = ""
        searchedSourceCount = 0
        accumulatedSearchBooks = emptyList()
        canLoadMoreSearch = false
        hasBooks = false
        lastDisplayLimit = SEARCH_PAGE_SIZE
    }

    fun send(text: String) {
        val key = text.trim()
        if (key.isEmpty() || _waiting.value) return
        cancelRequested = false
        addUserMessage(key)
        activeJob = viewModelScope.launch {
            val supplier = if (selectedSupplierId > 0) {
                appDb.aiSourceDao.get(selectedSupplierId)
            } else {
                appDb.aiSourceDao.allEnabled.firstOrNull()
            }
            if (supplier == null || supplier.model.isBlank()) {
                addAgentMessage(getString(R.string.ai_not_configured))
            } else {
                agentLoop(supplier, key)
            }
        }
    }

    /**
     * 中断当前正在进行的请求或搜索
     */
    fun cancel() {
        cancelRequested = true
        currentCall?.cancel()
        activeJob?.cancel()
        activeJob = null
    }

    fun searchBook(key: String) {
        if (key.isBlank() || _waiting.value) return
        cancelRequested = false
        addUserMessage(key)
        activeJob = viewModelScope.launch {
            searchDirect(key)
        }
    }

    fun continueSearch() {
        if (_waiting.value || !canLoadMoreSearch || lastSearchKey.isBlank()) return
        cancelRequested = false
        activeJob = viewModelScope.launch {
            _waiting.value = true
            val stepId = startStep(getString(R.string.agent_step_continue_search))
            try {
                val start = searchedSourceCount
                val end = start + SEARCH_SOURCE_BATCH_SIZE
                val result = withContext(Dispatchers.IO) {
                    searchBooks(
                        lastSearchKey,
                        getSearchSources(lastSearchGroup),
                        start,
                        end,
                        lastDisplayLimit
                    )
                }
                searchedSourceCount = result.searchedSources
                canLoadMoreSearch = result.canLoadMore
                val mergedBooks = mergeSearchBooks(
                    accumulatedSearchBooks,
                    result.allBooks,
                    lastSearchKey
                )
                accumulatedSearchBooks = mergedBooks
                val topBooks = mergedBooks.take(lastDisplayLimit)
                finishStep(
                    stepId,
                    summary = getString(
                        R.string.agent_step_search_done,
                        result.searchedSources,
                        mergedBooks.size
                    )
                )
                _messages.value = _messages.value.map { it.copy(canLoadMore = false) }
                if (topBooks.isEmpty()) {
                    canLoadMoreSearch = false
                    finishAgentReply(getString(R.string.agent_no_more_results))
                } else {
                    finishAgentReply("", topBooks, canLoadMoreSearch)
                }
            } catch (e: Exception) {
                if (cancelRequested) {
                    cancelRequested = false
                    failStep(stepId, NoStackTraceException(getString(R.string.agent_interrupted)))
                    finishAgentReply(getString(R.string.agent_interrupted))
                } else {
                    failStep(stepId, e)
                    context.toastOnUi(e.localizedMessage ?: e.message ?: "搜索失败")
                    finishAgentReply(e.localizedMessage ?: "搜索失败")
                }
            } finally {
                _waiting.value = false
            }
        }
    }

    private fun addUserMessage(text: String) {
        history.add(ChatTurn(ROLE_USER, text))
        _messages.value = _messages.value.map { it.copy(canLoadMore = false) } +
            AgentMessage(true, text)
    }

    private fun addAgentMessage(
        text: String,
        books: List<SearchBook> = emptyList(),
        canLoadMore: Boolean = false
    ) {
        history.add(ChatTurn(ROLE_ASSISTANT, text))
        _messages.value = _messages.value + AgentMessage(false, text, books, canLoadMore = canLoadMore)
    }

    private fun startStep(title: String, detail: String? = null): String {
        val id = "step-${System.currentTimeMillis()}-${stepSequence++}"
        val step = AgentStep(id, title, AgentStepState.RUNNING, detail)
        stepStartTimes[id] = System.currentTimeMillis()
        if (liveReply != null) {
            liveReply = liveReply!!.copy(steps = liveReply!!.steps + step)
            emitLive()
        } else {
            activeSteps.add(step)
            emitSteps()
        }
        return id
    }

    private fun finishStep(stepId: String, summary: String? = null) {
        val start = stepStartTimes.remove(stepId) ?: 0L
        val duration = start.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
        if (liveReply != null) {
            liveReply = liveReply!!.copy(
                steps = liveReply!!.steps.map {
                    if (it.id == stepId) {
                        it.copy(
                            state = AgentStepState.DONE,
                            summary = summary ?: it.summary,
                            durationMs = duration
                        )
                    } else {
                        it
                    }
                }
            )
            emitLive()
        } else {
            val index = activeSteps.indexOfFirst { it.id == stepId }
            if (index >= 0) {
                val step = activeSteps[index]
                activeSteps[index] = step.copy(
                    state = AgentStepState.DONE,
                    summary = summary ?: step.summary,
                    durationMs = duration
                )
                emitSteps()
            }
        }
    }

    private fun failStep(stepId: String, e: Exception) {
        val start = stepStartTimes.remove(stepId) ?: 0L
        val duration = start.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
        if (liveReply != null) {
            liveReply = liveReply!!.copy(
                steps = liveReply!!.steps.map {
                    if (it.id == stepId) {
                        it.copy(
                            state = AgentStepState.FAILED,
                            summary = e.localizedMessage ?: e.message ?: "未知错误",
                            durationMs = duration
                        )
                    } else {
                        it
                    }
                }
            )
            emitLive()
        } else {
            val index = activeSteps.indexOfFirst { it.id == stepId }
            if (index >= 0) {
                val step = activeSteps[index]
                activeSteps[index] = step.copy(
                    state = AgentStepState.FAILED,
                    summary = e.localizedMessage ?: e.message ?: "未知错误",
                    durationMs = duration
                )
                emitSteps()
            }
        }
    }

    private fun updateStepDetail(stepId: String, detail: String) {
        if (liveReply != null) {
            liveReply = liveReply!!.copy(
                steps = liveReply!!.steps.map {
                    if (it.id == stepId) it.copy(detail = detail) else it
                }
            )
            emitLive()
        } else {
            val index = activeSteps.indexOfFirst { it.id == stepId }
            if (index >= 0) {
                activeSteps[index] = activeSteps[index].copy(detail = detail)
                emitSteps()
            }
        }
    }

    /**
     * 流式回复：liveReply 始终保持为对话列表中最后一条消息，随内容与步骤实时更新
     */
    private fun emitLive() {
        val base = _messages.value.filterNot { it.streaming }
        _messages.value = if (liveReply != null) base + liveReply!! else base
    }

    private fun beginLiveReply() {
        liveReply = AgentMessage(false, "", streaming = true)
        emitLive()
    }

    private fun appendLiveText(delta: String) {
        val reply = liveReply ?: return
        liveReply = reply.copy(text = reply.text + delta)
        _streamingText.value = liveReply!!.text
    }

    private fun finalizeLive(
        text: String,
        books: List<SearchBook> = emptyList(),
        canLoadMore: Boolean = false
    ) {
        val reply = liveReply ?: return
        history.add(ChatTurn(ROLE_ASSISTANT, text))
        _messages.value = _messages.value.filterNot { it.streaming } +
            reply.copy(
                text = text,
                books = books,
                canLoadMore = canLoadMore,
                streaming = false
            )
        _streamingText.value = null
        liveReply = null
    }

    private fun emitSteps() {
        _messages.value = _messages.value.filterNot { it.placeholder } +
            AgentMessage(steps = activeSteps.toList(), placeholder = true)
    }

    private fun finishAgentReply(
        text: String,
        books: List<SearchBook> = emptyList(),
        canLoadMore: Boolean = false,
        steps: List<AgentStep> = activeSteps.toList()
    ) {
        _messages.value = _messages.value.filterNot { it.placeholder } +
            AgentMessage(false, text, books, steps, canLoadMore = canLoadMore)
        activeSteps.clear()
        stepStartTimes.clear()
    }

    private suspend fun searchDirect(key: String, searchKey: String = key) {
        _waiting.value = true
        val stepId = startStep(
            getString(R.string.agent_step_searching),
            getString(R.string.agent_step_detail_search, searchKey)
        )
        try {
            lastSearchGroup = ""
            val result = withContext(Dispatchers.IO) {
                searchBooks(
                    searchKey,
                    appDb.bookSourceDao.allEnabled,
                    0,
                    FIRST_SEARCH_SOURCE_LIMIT,
                    SEARCH_PAGE_SIZE
                )
            }
            lastSearchKey = searchKey
            searchedSourceCount = result.searchedSources
            accumulatedSearchBooks = result.allBooks
            canLoadMoreSearch = result.canLoadMore
            finishStep(
                stepId,
                summary = getString(
                    R.string.agent_step_search_done,
                    result.searchedSources,
                    result.allBooks.size
                )
            )
            if (result.books.isEmpty()) {
                finishAgentReply(getString(R.string.agent_no_result))
            } else {
                finishAgentReply("", result.books, canLoadMoreSearch)
            }
        } catch (e: Exception) {
            if (cancelRequested) {
                cancelRequested = false
                failStep(stepId, NoStackTraceException(getString(R.string.agent_interrupted)))
                finishAgentReply(getString(R.string.agent_interrupted))
            } else {
                failStep(stepId, e)
                context.toastOnUi(e.localizedMessage ?: e.message ?: "搜索失败")
                finishAgentReply(e.localizedMessage ?: "搜索失败")
            }
        } finally {
            _waiting.value = false
        }
    }

    /**
     * Agent 循环：模型决定是否调用搜索工具，最多执行 3 轮工具调用。回复内容以流式输出
     */
    private suspend fun agentLoop(supplier: io.legado.app.data.entities.AiSource, key: String) {
        _waiting.value = true
        try {
            currentSupplier = supplier
            var finalText = ""
            lastBooks = emptyList()
            hasBooks = false
            var finished = false
            beginLiveReply()
            for (round in 0 until 3) {
                if (finished) break
                val requestStepId = startStep(
                    if (round == 0) {
                        getString(R.string.agent_step_requesting)
                    } else {
                        getString(R.string.agent_step_generating)
                    },
                    "model=${supplier.model}"
                )
                val request = buildChatRequest(supplier)
                val message = withContext(Dispatchers.IO) {
                    chatCompletionStream(supplier, request) { delta ->
                        appendLiveText(delta)
                    }
                }
                val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (content.isNotBlank()) {
                    finalText = content
                }
                val toolCalls = message.get("tool_calls")?.takeIf { it.isJsonArray }
                if (toolCalls == null || toolCalls.asJsonArray.size() == 0) {
                    finishStep(
                        requestStepId,
                        summary = getString(R.string.agent_step_reply_done)
                    )
                    finalizeLive(
                        finalText,
                        if (hasBooks) lastBooks else emptyList(),
                        hasBooks && canLoadMoreSearch
                    )
                    finished = true
                    break
                }
                finishStep(
                    requestStepId,
                    summary = getString(
                        R.string.agent_step_request_tool,
                        toolCalls.asJsonArray.size()
                    )
                )
                history.add(
                    ChatTurn(
                        ROLE_ASSISTANT,
                        content,
                        toolCalls = message.get("tool_calls")
                    )
                )
                for (call: JsonElement in toolCalls.asJsonArray) {
                    val callObject = call.asJsonObject
                    val toolCallId =
                        callObject.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val function = callObject.get("function")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: throw invalidResponseException(null)
                    val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val arguments =
                        function.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val result = executeTool(name, arguments)
                    history.add(
                        ChatTurn(
                            ROLE_TOOL,
                            result,
                            toolCallId
                        )
                    )
                }
            }
            if (!finished) {
                finalizeLive(
                    finalText.ifBlank { getString(R.string.agent_waiting) },
                    if (hasBooks) lastBooks else emptyList(),
                    hasBooks && canLoadMoreSearch
                )
            }
        } catch (e: Exception) {
            if (cancelRequested) {
                cancelRequested = false
                AppLog.put("Agent 对话已中断")
                if (liveReply != null) {
                    val reply = liveReply!!
                    liveReply = reply.copy(
                        steps = reply.steps.map {
                            if (it.state == AgentStepState.RUNNING) {
                                it.copy(
                                    state = AgentStepState.FAILED,
                                    summary = getString(R.string.agent_interrupted),
                                    durationMs = stepStartTimes.remove(it.id)
                                        ?.takeIf { d -> d > 0 }
                                        ?.let { d -> System.currentTimeMillis() - d }
                                )
                            } else {
                                it
                            }
                        }
                    )
                }
                val partial = liveReply?.text.orEmpty()
                finalizeLive(
                    partial.ifBlank { getString(R.string.agent_interrupted) },
                    if (hasBooks) lastBooks else emptyList(),
                    hasBooks && canLoadMoreSearch
                )
                return
            }
            AppLog.put("Agent 对话出错", e)
            val message = e.localizedMessage ?: e.message ?: "请求失败"
            if (liveReply != null) {
                val reply = liveReply!!
                liveReply = reply.copy(
                    steps = reply.steps.map {
                        if (it.state == AgentStepState.RUNNING) {
                            it.copy(
                                state = AgentStepState.FAILED,
                                summary = message,
                                durationMs = stepStartTimes.remove(it.id)
                                    ?.takeIf { d -> d > 0 }
                                    ?.let { d -> System.currentTimeMillis() - d }
                            )
                        } else {
                            it
                        }
                    }
                )
                finalizeLive("${supplier.name}: $message")
            } else {
                finishAgentReply("${supplier.name}: $message")
            }
        } finally {
            liveReply = null
            stepStartTimes.clear()
            currentSupplier = null
            _waiting.value = false
        }
    }

    override suspend fun searchBooks(key: String, group: String, limit: Int): String {
        val pageSize = limit.coerceIn(1, MAX_SEARCH_PAGE_SIZE)
        lastDisplayLimit = pageSize
        val groupDetail = group.takeIf { it.isNotBlank() }?.let { ", group=$it" }.orEmpty()
        val stepId = startStep(
            getString(R.string.agent_step_searching),
            getString(R.string.agent_step_detail_search, key) + groupDetail
        )
        val result = withContext(Dispatchers.IO) {
            val sources = getSearchSources(group)
            if (sources.isEmpty()) {
                null
            } else {
                searchBooks(key, sources, 0, FIRST_SEARCH_SOURCE_LIMIT, pageSize)
            }
        }
        if (result == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_no_source_group, group)))
            return getString(R.string.agent_no_source_group, group)
        }
        lastBooks = result.books
        hasBooks = hasBooks || result.books.isNotEmpty()
        lastSearchKey = key
        lastSearchGroup = group
        searchedSourceCount = result.searchedSources
        accumulatedSearchBooks = result.allBooks
        canLoadMoreSearch = result.canLoadMore
        finishStep(
            stepId,
            summary = getString(
                R.string.agent_step_search_done,
                result.searchedSources,
                result.allBooks.size
            )
        )
        return GSON.toJson(result.books.map { it.toToolResult() })
    }

    override suspend fun addBookToShelf(bookUrl: String): String {
        if (bookUrl.isBlank()) return getString(R.string.agent_book_url_empty)
        val stepId = startStep(
            getString(R.string.agent_step_add_shelf),
            getString(R.string.agent_step_detail_search, bookUrl)
        )
        return try {
            val result = withContext(Dispatchers.IO) {
                val searchBook = appDb.searchBookDao.getSearchBook(bookUrl)
                if (searchBook == null) {
                    null
                } else {
                    appDb.bookDao.getBook(searchBook.name, searchBook.author)?.let { existing ->
                        existing.removeType(BookType.notShelf)
                        existing.save()
                        existing.name
                    } ?: run {
                        val book = searchBook.toBook()
                        book.removeType(BookType.notShelf)
                        if (book.order == 0) {
                            book.order = appDb.bookDao.minOrder - 1
                        }
                        book.save()
                        book.name
                    }
                }
            }
            if (result == null) {
                failStep(stepId, NoStackTraceException(getString(R.string.agent_book_not_found)))
                getString(R.string.agent_book_not_found)
            } else {
                finishStep(
                    stepId,
                    summary = getString(R.string.agent_step_add_shelf_done, result)
                )
                getString(R.string.agent_step_add_shelf_success, result)
            }
        } catch (e: Exception) {
            failStep(stepId, e)
            getString(R.string.agent_add_shelf_failed, e.localizedMessage ?: e.message.orEmpty())
        }
    }

    override suspend fun createBookSource(url: String): String {
        val supplier = currentSupplier ?: return getString(R.string.agent_no_supplier)
        return createBookSource(supplier, url)
    }

    override suspend fun readingReport(period: String): String {
        val stepId = startStep(
            getString(R.string.agent_step_reading_report, periodLabel(period))
        )
        return try {
            val result = withContext(Dispatchers.IO) {
                ReadingReport.build(period)
            }
            finishStep(stepId, summary = getString(R.string.agent_step_reading_report_done))
            result
        } catch (e: Exception) {
            failStep(stepId, e)
            throw e
        }
    }

    override suspend fun getLibraryStats(): String {
        val stepId = startStep(getString(R.string.agent_step_library_stats))
        return try {
            val result = withContext(Dispatchers.IO) {
                LibraryStats.build()
            }
            val summary = runCatching {
                val json = JsonParser.parseString(result).asJsonObject
                getString(
                    R.string.agent_step_library_stats_done,
                    json.get("bookSourceCount")?.asInt ?: 0,
                    json.get("bookCount")?.asInt ?: 0
                )
            }.getOrElse { getString(R.string.agent_step_library_stats_done_short) }
            finishStep(stepId, summary = summary)
            result
        } catch (e: Exception) {
            failStep(stepId, e)
            throw e
        }
    }

    private suspend fun executeTool(name: String, arguments: String): String {
        val tool = AgentTools.find(name)
        if (tool == null) {
            return "未知工具: $name"
        }
        val parsed = runCatching { JsonParser.parseString(arguments).asJsonObject }
            .getOrDefault(JsonObject())
        val stepId = startStep(
            getString(R.string.agent_step_tool_calling, name),
            summarizeToolArguments(name, parsed)
        )
        return try {
            val result = tool.execute(this, parsed)
            val summary = summarizeToolResult(name, result)
            if (isToolFailure(name, result)) {
                failStep(stepId, Exception(summary ?: result))
            } else {
                finishStep(stepId, summary = summary)
            }
            result
        } catch (e: Exception) {
            if (cancelRequested || e is CancellationException) {
                throw e
            }
            failStep(stepId, e)
            val message = e.localizedMessage ?: e.message ?: "未知错误"
            "工具执行失败: $message"
        }
    }

    private fun isToolFailure(name: String, result: String): Boolean {
        return name == "create_book_source" && result.startsWith("书源创建失败")
    }

    private fun summarizeToolArguments(name: String, args: JsonObject): String? {
        return when (name) {
            "search_books" -> buildString {
                args.get("query")?.takeIf { !it.isJsonNull }?.asString?.let {
                    append("query=$it")
                }
                args.get("group")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        if (isNotEmpty()) append(", ")
                        append("group=$it")
                    }
            }.takeIf { it.isNotBlank() }
            "create_book_source" -> args.get("url")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { "url=$it" }
            "reading_report" -> args.get("period")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { "period=$it" }
            "create_ai_book" -> buildString {
                args.get("type")?.takeIf { !it.isJsonNull }?.asString?.let {
                    append("type=$it")
                }
                args.get("theme")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        if (isNotEmpty()) append(", ")
                        append("theme=$it")
                    }
            }.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun summarizeToolResult(name: String, result: String): String? {
        return when (name) {
            "search_books" -> {
                val parsed = runCatching { JsonParser.parseString(result) }.getOrNull()
                if (parsed is JsonArray) {
                    getString(R.string.agent_step_search_tool_done, parsed.size())
                } else {
                    result.lineSequence().firstOrNull()?.take(60)
                        ?: getString(R.string.agent_step_unknown_result)
                }
            }
            "create_book_source" -> result.lineSequence().firstOrNull()?.take(60)
                ?: getString(R.string.agent_step_unknown_result)
            "reading_report" -> getString(R.string.agent_step_reading_report_done)
            "library_stats" -> getString(R.string.agent_step_library_stats_done_short)
            "create_ai_book" -> result.lineSequence().firstOrNull()?.take(80)
                ?: getString(R.string.agent_step_unknown_result)
            else -> result.lineSequence().firstOrNull()?.take(60)
                ?: getString(R.string.agent_step_unknown_result)
        }
    }

    override suspend fun createAiBook(
        type: String,
        theme: String,
        chapterCount: Int,
        wordsPerChapter: Int
    ): String {
        val supplier = currentSupplier ?: return getString(R.string.agent_no_supplier)
        val count = chapterCount.coerceIn(1, MAX_AI_BOOK_CHAPTERS)
        val words = wordsPerChapter.coerceIn(500, 5000)
        return try {
            // 阶段1：生成书籍信息并创建书籍
            val metaStep = startStep(getString(R.string.agent_step_ai_book_meta))
            val book = generateBookMeta(supplier, type, theme)
            finishStep(metaStep, summary = "${book.name} · ${book.author}")
            // 阶段2：生成大纲并插入章节
            val outlineStep = startStep(getString(R.string.agent_step_ai_book_outline))
            val plans = generateOutline(supplier, book, count)
            finishStep(
                outlineStep,
                summary = getString(R.string.agent_step_ai_book_outline_done, plans.size)
            )
            val chapters = plans.mapIndexed { index, plan ->
                BookChapter(
                    url = "${book.bookUrl}#$index",
                    title = plan.title,
                    bookUrl = book.bookUrl,
                    index = index
                )
            }
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            // 阶段3：逐章生成正文
            val recentContents = ArrayDeque<String>()
            var totalWords = 0
            var failedCount = 0
            for (i in plans.indices) {
                currentCoroutineContext().ensureActive()
                val chapterStep = startStep(
                    getString(R.string.agent_step_ai_book_chapter, i + 1, plans.size),
                    "《${plans[i].title}》"
                )
                val content = try {
                    generateChapterContent(
                        supplier, book, plans, i, recentContents, words
                    ) { length ->
                        updateStepDetail(chapterStep, "《${plans[i].title}》 · 已生成 $length 字")
                    }
                } catch (e: Exception) {
                    if (cancelRequested || e is CancellationException) {
                        throw e
                    }
                    AppLog.put("AI创作第${i + 1}章失败", e)
                    ""
                }
                if (content.isNotBlank()) {
                    val chapter = chapters[i]
                    BookHelp.saveText(book, chapter, content)
                    appDb.bookChapterDao.upWordCount(
                        book.bookUrl, chapter.url, content.length.toString()
                    )
                    recentContents.addLast(content)
                    if (recentContents.size > 5) {
                        recentContents.removeFirst()
                    }
                    totalWords += content.length
                    finishStep(
                        chapterStep,
                        summary = getString(
                            R.string.agent_step_ai_book_chapter_done,
                            i + 1,
                            plans[i].title,
                            content.length
                        )
                    )
                } else {
                    failedCount++
                    failStep(chapterStep, NoStackTraceException(getString(R.string.agent_ai_book_failed, "章节正文为空")))
                }
            }
            book.totalChapterNum = chapters.size
            book.latestChapterTitle = chapters.lastOrNull()?.title
            appDb.bookDao.update(book)
            if (failedCount >= chapters.size) {
                getString(R.string.agent_ai_book_failed, getString(R.string.agent_ai_book_failed, "正文生成失败"))
            } else {
                getString(
                    R.string.agent_ai_book_done,
                    book.name,
                    book.author,
                    chapters.size,
                    totalWords
                )
            }
        } catch (e: Exception) {
            if (cancelRequested || e is CancellationException) {
                throw e
            }
            AppLog.put("AI创作小说失败", e)
            getString(
                R.string.agent_ai_book_failed,
                e.localizedMessage ?: e.message ?: "未知错误"
            )
        }
    }

    private data class ChapterPlan(
        val title: String,
        val outline: String
    )

    private suspend fun generateBookMeta(
        supplier: io.legado.app.data.entities.AiSource,
        type: String,
        theme: String
    ): Book {
        var lastError = ""
        for (attempt in 0 until 3) {
            val messages = JsonArray()
            messages.add(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", BOOK_META_PROMPT)
                }
            )
            val userContent = buildString {
                append("小说类型：$type\n主题与设定：$theme")
                if (attempt > 0) {
                    append("\n\n上次生成的书名与作者已存在，请更换书名后重新输出完整JSON。")
                }
            }
            messages.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userContent)
                }
            )
            val reply = chatCompletion(
                supplier,
                JsonObject().apply {
                    addProperty("model", supplier.model)
                    add("messages", messages)
                }
            )
            val content = reply.get("content")
                ?.takeIf { !it.isJsonNull }
                ?.asString.orEmpty()
            val meta = runCatching {
                GSON.fromJsonObject<JsonObject>(extractJson(content)).getOrNull()
            }.getOrNull()
            if (meta == null) {
                lastError = "书籍信息JSON格式错误"
                continue
            }
            val name = meta.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val author = meta.get("author")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val intro = meta.get("intro")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val kind = meta.get("kind")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            if (name.isBlank() || author.isBlank()) {
                lastError = "书名或作者为空"
                continue
            }
            if (appDb.bookDao.has(name, author)) {
                lastError = "书名已存在"
                continue
            }
            val bookUrl = "loc_created://${UUID.randomUUID()}"
            val book = Book(
                bookUrl = bookUrl,
                tocUrl = bookUrl,
                origin = BookType.localTag,
                originName = getString(R.string.agent_ai_author),
                name = name,
                author = author,
                intro = intro,
                kind = kind,
                type = BookType.text or BookType.created,
                group = 0L,
                order = appDb.bookDao.minOrder - 1
            )
            appDb.bookDao.insert(book)
            return book
        }
        throw NoStackTraceException(getString(R.string.agent_ai_book_meta_failed, lastError))
    }

    private suspend fun generateOutline(
        supplier: io.legado.app.data.entities.AiSource,
        book: Book,
        count: Int
    ): List<ChapterPlan> {
        var lastError = ""
        for (attempt in 0 until 3) {
            val messages = JsonArray()
            messages.add(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", BOOK_OUTLINE_PROMPT)
                }
            )
            messages.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    addProperty(
                        "content",
                        "书名：${book.name}\n作者：${book.author}\n简介：${book.intro}\n\n请生成共 $count 章的章节大纲。"
                    )
                }
            )
            val reply = chatCompletion(
                supplier,
                JsonObject().apply {
                    addProperty("model", supplier.model)
                    add("messages", messages)
                }
            )
            val content = reply.get("content")
                ?.takeIf { !it.isJsonNull }
                ?.asString.orEmpty()
            val plans = runCatching {
                GSON.fromJsonArray<JsonObject>(extractJson(content)).getOrNull()
                    ?.mapNotNull { obj ->
                        val title = obj.get("title")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            ?.trim()
                        if (title.isNullOrBlank()) {
                            null
                        } else {
                            val outline = obj.get("outline")
                                ?.takeIf { !it.isJsonNull }
                                ?.asString
                                ?.trim()
                                .orEmpty()
                            ChapterPlan(title, outline)
                        }
                    }
            }.getOrNull().orEmpty()
            if (plans.isEmpty()) {
                lastError = "大纲JSON格式错误"
                continue
            }
            if (plans.size < count) {
                // 章节数不足时按已有章节使用，章节数足够时裁剪
            }
            return plans.take(count)
        }
        throw NoStackTraceException(getString(R.string.agent_ai_book_outline_failed, lastError))
    }

    private suspend fun generateChapterContent(
        supplier: io.legado.app.data.entities.AiSource,
        book: Book,
        plans: List<ChapterPlan>,
        index: Int,
        recentContents: List<String>,
        words: Int,
        onProgress: (Int) -> Unit
    ): String {
        val context = buildString {
            append("书籍名：《${book.name}》\n")
            append("书籍简介：${book.intro.orEmpty()}\n\n")
            append("本章标题：${plans[index].title}\n")
            append("本章大纲：${plans[index].outline.ifBlank { "（无）" }}\n\n")
            if (recentContents.isNotEmpty()) {
                append("最近章节正文（供衔接前情，仅作参考，不要大段重复）：\n")
                val start = max(0, index - recentContents.size)
                recentContents.forEachIndexed { i, content ->
                    append("--- ${plans[start + i].title} ---\n")
                    append(content.take(1200)).append("\n\n")
                }
            }
        }
        val messages = JsonArray()
        messages.add(
            JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", BOOK_CHAPTER_PROMPT)
            }
        )
        messages.add(
            JsonObject().apply {
                addProperty("role", "user")
                addProperty(
                    "content",
                    context + "\n请写出本章正文，目标字数约 $words 字。"
                )
            }
        )
        val request = JsonObject().apply {
            addProperty("model", supplier.model)
            add("messages", messages)
        }
        val contentBuilder = StringBuilder()
        var lastReported = 0
        withContext(Dispatchers.IO) {
            chatCompletionStream(supplier, request) { delta ->
                contentBuilder.append(delta)
                if (contentBuilder.length - lastReported >= 200) {
                    lastReported = contentBuilder.length
                    onProgress(contentBuilder.length)
                }
            }
        }
        return contentBuilder.toString()
    }

    private fun buildChatRequest(supplier: io.legado.app.data.entities.AiSource): JsonObject {
        val root = JsonObject()
        val messages = JsonArray()
        messages.add(
            JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", SYSTEM_PROMPT + AgentTools.overview())
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
                    turn.toolCalls?.takeIf { it.isJsonArray }?.let {
                        add("tool_calls", it)
                    }
                }
            )
        }
        root.add("messages", messages)
        root.add("tools", AgentTools.toJsonArray())
        return root
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
        val bodyText = response.body
        val json = bodyText?.takeIf { it.isNotBlank() }?.let {
            runCatching { JsonParser.parseString(it) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        }
        if (json == null) {
            throw invalidResponseException(bodyText, response.code())
        }
        json.get("error")?.takeIf { !it.isJsonNull }?.let { error ->
            val message = error.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("message")
                ?.takeIf { !it.isJsonNull }
                ?.asString
            throw Exception(message ?: getString(R.string.agent_api_error, error.toString()))
        }
        if (!response.isSuccessful()) {
            AppLog.put("Agent 接口返回 HTTP ${response.code()}\n${bodyText.orEmpty()}")
            throw Exception("HTTP ${response.code()}\n${bodyText.take(1000)}")
        }
        val choices = json.get("choices")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
        if (choices == null || choices.size() == 0) {
            throw invalidResponseException(bodyText, response.code())
        }
        val choice = choices[0].takeIf { it.isJsonObject }?.asJsonObject
            ?: throw invalidResponseException(bodyText, response.code())
        choice.get("message")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            return it
        }
        val text = choice.get("text")?.takeIf { !it.isJsonNull }?.asString
        if (!text.isNullOrBlank()) {
            return JsonObject().apply {
                addProperty("content", text)
            }
        }
        throw invalidResponseException(bodyText, response.code())
    }

    /**
     * 流式调用 /chat/completions：读取 SSE 分块，将 content 增量通过 onDelta 回调实时输出，
     * 同时按 index 累积合并分片的 tool_calls，返回聚合后的完整消息
     */
    private suspend fun chatCompletionStream(
        supplier: io.legado.app.data.entities.AiSource,
        body: JsonObject,
        onDelta: (String) -> Unit
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
        val request = body.deepCopy().apply { addProperty("stream", true) }.toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())
        val httpRequest = Request.Builder()
            .apply {
                addHeaders(headers)
                url(supplier.baseUrl.trimEnd('/') + "/chat/completions")
                post(request)
            }
            .build()
        val call = client.newCall(httpRequest)
        currentCall = call
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val bodyText = response.body?.string()
                AppLog.put("Agent 流式接口返回 HTTP ${response.code}\n${bodyText.orEmpty()}")
                throw Exception("HTTP ${response.code}\n${bodyText?.take(1000).orEmpty()}")
            }
            val contentBuilder = StringBuilder()
            val toolCallMap = LinkedHashMap<Int, JsonObject>()
            try {
                response.body?.let { respBody ->
                    val source = respBody.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val data = trimmed.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        val chunk = runCatching { JsonParser.parseString(data) }
                            .getOrNull()
                            ?.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?: continue
val choices = chunk.get("choices")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?: continue
                    if (choices.size() == 0) continue
                    val choice = choices[0]
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: continue
                    val delta = choice.get("delta")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: continue
                    val content = delta.get("content")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                    if (!content.isNullOrBlank()) {
                        contentBuilder.append(content)
                        onDelta(content)
                    }
                    val toolCalls = delta.get("tool_calls")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.size()) {
                                val tc = toolCalls[i]
                                    ?.takeIf { it.isJsonObject }
                                    ?.asJsonObject
                                    ?: continue
                                val index = tc.get("index")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asInt
                                    ?: 0
                                val existing = toolCallMap[index]
                                if (existing == null) {
                                    val newCall = JsonObject()
                                    tc.get("id")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString
                                        ?.let { newCall.addProperty("id", it) }
                                    val func = JsonObject()
                                    tc.get("function")
                                        ?.takeIf { it.isJsonObject }
                                        ?.asJsonObject
                                        ?.let { funcObj ->
                                            funcObj.get("name")
                                                ?.takeIf { !it.isJsonNull }
                                                ?.asString
                                                ?.let { func.addProperty("name", it) }
                                            funcObj.get("arguments")
                                                ?.takeIf { !it.isJsonNull }
                                                ?.asString
                                                ?.let { func.addProperty("arguments", it) }
                                        }
                                    newCall.add("function", func)
                                    toolCallMap[index] = newCall
                                } else {
                                    tc.get("id")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { existing.addProperty("id", it) }
                                    tc.get("function")
                                        ?.takeIf { it.isJsonObject }
                                        ?.asJsonObject
                                        ?.let { funcObj ->
                                            val func = existing.getAsJsonObject("function")
                                            funcObj.get("name")
                                                ?.takeIf { !it.isJsonNull }
                                                ?.asString
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { func.addProperty("name", it) }
                                            funcObj.get("arguments")
                                                ?.takeIf { !it.isJsonNull }
                                                ?.asString
                                                ?.let { argDelta ->
                                                    val prev = func.get("arguments")
                                                        ?.takeIf { !it.isJsonNull }
                                                        ?.asString.orEmpty()
                                                    func.addProperty("arguments", prev + argDelta)
                                                }
                                        }
                                }
                            }
                        }
                    }
                }
            } finally {
                response.close()
            }
            return JsonObject().apply {
                addProperty("content", contentBuilder.toString())
                if (toolCallMap.isNotEmpty()) {
                    val array = JsonArray()
                    toolCallMap.keys.sorted().forEach { array.add(toolCallMap[it]) }
                    add("tool_calls", array)
                }
            }
        } finally {
            if (currentCall === call) {
                currentCall = null
            }
        }
    }

    private fun invalidResponseException(bodyText: String?, code: Int? = null): Exception {
        AppLog.put("Agent 接口返回内容格式不正确 HTTP $code\n${bodyText.orEmpty()}")
        val prefix = code?.let { "HTTP $it\n" }.orEmpty()
        val detail = bodyText?.take(1000)?.let { "\n$it" }.orEmpty()
        return Exception(prefix + getString(R.string.agent_invalid_response) + detail)
    }

    /**
     * 搜索书籍，返回合并后的结果
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun searchBooks(
        key: String,
        sources: List<BookSource>,
        sourceStart: Int,
        sourceEnd: Int,
        limit: Int
    ): SearchResult {
        val totalSources = sources.size
        if (totalSources == 0) {
            AppLog.put("Agent 搜索: 没有启用书源 key=$key")
            return SearchResult(emptyList(), emptyList(), 0, 0, false)
        }
        val end = min(sourceEnd, totalSources)
        if (sourceStart >= end) {
            AppLog.put("Agent 搜索: 没有更多书源 key=$key 已搜索=$sourceStart")
            return SearchResult(emptyList(), emptyList(), sourceStart, totalSources, false)
        }
        AppLog.put(
            "Agent 搜索开始: key=$key 书源区间=$sourceStart..$end 书源总数=$totalSources " +
                "并发=${AppConfig.threadCount}"
        )
        val result = arrayListOf<SearchBook>()
        val searchStart = System.currentTimeMillis()
        try {
            withTimeout(120_000L) {
                sources.subList(sourceStart, end).asFlow()
                    .flatMapMerge(AppConfig.threadCount) { source ->
                        flow {
                            val sourceStartTime = System.currentTimeMillis()
                            AppLog.put(
                                "Agent 搜索开始书源: ${source.bookSourceName} " +
                                    source.bookSourceUrl
                            )
                            try {
                                val books = withTimeout(30_000L) {
                                    WebBook.searchBookAwait(source, key, 1)
                                }
                                val elapsed = System.currentTimeMillis() - sourceStartTime
                                AppLog.put(
                                    "Agent 搜索完成: ${source.bookSourceName} " +
                                        "${source.bookSourceUrl} 耗时=${elapsed}ms " +
                                        "结果数=${books.size}"
                                )
                                emitAll(books.asFlow())
                            } catch (e: Exception) {
                                val elapsed = System.currentTimeMillis() - sourceStartTime
                                if (currentCoroutineContext().isActive) {
                                    AppLog.put(
                                        "Agent 搜索失败: ${source.bookSourceName} " +
                                            "${source.bookSourceUrl} 耗时=${elapsed}ms",
                                        e
                                    )
                                }
                            }
                        }
                    }
                    .collect { result.add(it) }
            }
        } catch (e: Exception) {
            AppLog.put(
                "Agent 搜索超时/取消: key=$key 书源区间=$sourceStart..$end 已获取${result.size}条 " +
                    "总耗时=${System.currentTimeMillis() - searchStart}ms",
                e
            )
            throw e
        }
        kotlin.runCatching {
            appDb.searchBookDao.insert(*result.toTypedArray())
        }
        val ranked = rankBooks(result, key)
        val finalResult = ranked.take(limit)
        val canLoadMore = end < totalSources
        AppLog.put(
            "Agent 搜索返回: key=$key 搜索书源=$end/$totalSources 原始结果=${result.size}条 " +
                "去重后=${ranked.size}条 展示=${finalResult.size}条 " +
                "总耗时=${System.currentTimeMillis() - searchStart}ms"
        )
        return SearchResult(finalResult, ranked, end, totalSources, canLoadMore)
    }

    private fun getSearchSources(group: String): List<BookSource> {
        val key = group.trim()
        if (key.isBlank()) {
            return appDb.bookSourceDao.allEnabled
        }
        val enabled = appDb.bookSourceDao.allEnabled
        enabled.firstOrNull { it.bookSourceName == key }?.let { return listOf(it) }
        appDb.bookSourceDao.getEnabledByGroup(key).takeIf { it.isNotEmpty() }?.let { return it }
        return enabled.filter {
            it.bookSourceName.contains(key, ignoreCase = true) ||
                it.bookSourceGroup?.contains(key, ignoreCase = true) == true
        }
    }

    private fun mergeSearchBooks(
        existing: List<SearchBook>,
        newBooks: List<SearchBook>,
        key: String
    ): List<SearchBook> {
        return rankBooks(existing + newBooks, key)
    }

    private fun rankBooks(books: List<SearchBook>, key: String): List<SearchBook> {
        val merged = LinkedHashMap<String, SearchBook>()
        books.forEach { book ->
            val id = "${book.name.trim()}|${book.author.trim()}".lowercase()
            val existing = merged[id]
            if (existing == null) {
                merged[id] = book
            } else {
                existing.addOrigin(book.origin)
            }
        }
        return merged.values.sortedWith(
            compareByDescending<SearchBook> { relevanceScore(it, key) }
                .thenByDescending { it.origins.size }
                .thenByDescending { it.originOrder }
        )
    }

    private fun relevanceScore(book: SearchBook, key: String): Int {
        val name = book.name.trim()
        val author = book.author.trim()
        val k = key.trim()
        var score = 0
        if (name.equals(k, ignoreCase = true)) score += 100
        if (author.equals(k, ignoreCase = true)) score += 90
        if (name.contains(k, ignoreCase = true)) score += 60
        if (author.contains(k, ignoreCase = true)) score += 50
        val intro = book.intro
        if (!intro.isNullOrBlank() && intro.contains(k, ignoreCase = true)) score += 20
        return score
    }

    private suspend fun createBookSource(
        supplier: io.legado.app.data.entities.AiSource,
        url: String
    ): String {
        val siteUrl = url.trim().trimEnd('/')
        val siteStepId = startStep(getString(R.string.agent_step_fetching_site), siteUrl)
        val (finalUrl, html) = try {
            fetchSiteHtml(siteUrl)
        } catch (e: Exception) {
            failStep(siteStepId, e)
            return "书源创建失败：${e.localizedMessage ?: e.message ?: "无法访问网站"}"
        }
        finishStep(
            siteStepId,
            summary = getString(R.string.agent_step_fetching_site_done, html.length)
        )
        var lastError = ""
        for (attempt in 0 until SOURCE_CREATE_ATTEMPTS) {
            val generateStepId = startStep(
                getString(
                    R.string.agent_step_generating_source,
                    attempt + 1,
                    SOURCE_CREATE_ATTEMPTS
                )
            )
            val messages = JsonArray()
            messages.add(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", SOURCE_CREATE_PROMPT)
                }
            )
            val userContent = buildString {
                append("请为网站编写书源。\n网站地址：$finalUrl\n首页HTML片段：\n$html\n\n请直接输出书源JSON。")
                if (attempt > 0) {
                    append("\n\n上次书源校验失败：$lastError\n请修复后重新输出完整书源JSON。")
                }
            }
            messages.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userContent)
                }
            )
            val request = JsonObject().apply {
                addProperty("model", supplier.model)
                add("messages", messages)
            }
            val reply = try {
                chatCompletion(supplier, request)
            } catch (e: Exception) {
                lastError = "请求AI失败: ${e.localizedMessage}"
                failStep(generateStepId, e)
                continue
            }
            val content = reply.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val jsonText = extractJson(content)
            val source = try {
                parseBookSource(jsonText)
            } catch (e: Exception) {
                lastError = "书源JSON解析失败: ${e.localizedMessage}"
                failStep(generateStepId, e)
                if (attempt < SOURCE_CREATE_ATTEMPTS - 1) {
                    continue
                }
                return "书源创建失败：$lastError"
            }
            finishStep(
                generateStepId,
                summary = getString(
                    R.string.agent_step_generating_source_done,
                    source.bookSourceName
                )
            )
            source.bookSourceGroup = "AI生成"
            source.enabled = true
            source.lastUpdateTime = System.currentTimeMillis()
            val validateStepId = startStep(getString(R.string.agent_step_validating_source))
            try {
                validateBookSource(source)
                appDb.bookSourceDao.insert(source)
                finishStep(
                    validateStepId,
                    summary = getString(R.string.agent_step_validating_source_done)
                )
                return "书源创建成功：${source.bookSourceName}，地址：${source.bookSourceUrl}，" +
                    "已保存到AI生成分组"
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: e.message ?: "校验失败"
                AppLog.put("AI书源校验失败: ${source.bookSourceName}", e)
                failStep(validateStepId, e)
                if (attempt < SOURCE_CREATE_ATTEMPTS - 1) {
                    continue
                }
                return "书源创建失败：$lastError"
            }
        }
        return "书源创建失败：$lastError"
    }

    private suspend fun fetchSiteHtml(url: String): Pair<String, String> {
        val candidates = if (url.startsWith("http://") || url.startsWith("https://")) {
            listOf(url)
        } else {
            listOf("https://$url", "http://$url")
        }
        for (candidate in candidates) {
            try {
                val response = okHttpClient.newCallStrResponse {
                    url(candidate)
                }
                val html = response.body.orEmpty()
                if (html.isNotBlank()) {
                    val cleanHtml = html
                        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("\\s+"), " ")
                        .take(40_000)
                    return response.url() to cleanHtml
                }
            } catch (e: Exception) {
                AppLog.put("Agent 获取网站HTML失败: $candidate", e)
            }
        }
        throw NoStackTraceException("无法访问网站 $url")
    }

    private fun extractJson(text: String): String {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)
        if (fenced != null) return fenced.groupValues[1].trim()
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val start = trimmed.indexOf('[')
            val end = trimmed.lastIndexOf(']')
            return if (start >= 0 && end > start) {
                trimmed.substring(start, end + 1)
            } else {
                trimmed
            }
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            trimmed.substring(start, end + 1)
        } else {
            trimmed
        }
    }

    private fun parseBookSource(json: String): BookSource {
        val source = if (json.trimStart().startsWith("[")) {
            GSON.fromJsonArray<BookSource>(json).getOrNull()?.firstOrNull()
        } else {
            GSON.fromJsonObject<BookSource>(json).getOrNull()
        }
            ?: throw NoStackTraceException("书源JSON格式错误")
        if (source.bookSourceUrl.isBlank()) {
            throw NoStackTraceException("bookSourceUrl为空")
        }
        if (source.bookSourceName.isBlank()) {
            throw NoStackTraceException("bookSourceName为空")
        }
        return source
    }

    private suspend fun validateBookSource(source: BookSource) {
        val keyword = source.getCheckKeyword("斗破苍穹")
        val books = withTimeout(30_000L) {
            WebBook.searchBookAwait(source, keyword)
        }
        if (books.isEmpty()) {
            throw NoStackTraceException("搜索无结果")
        }
        val book = books.first().toBook()
        if (book.tocUrl.isBlank()) {
            withTimeout(30_000L) {
                WebBook.getBookInfoAwait(source, book)
            }
        }
        val toc = withTimeout(30_000L) {
            WebBook.getChapterListAwait(source, book).getOrThrow()
        }
        if (toc.isEmpty()) {
            throw NoStackTraceException("目录为空")
        }
        val firstChapter = toc.first()
        val nextChapterUrl = toc.getOrNull(1)?.url ?: firstChapter.url
        val content = withTimeout(30_000L) {
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = firstChapter,
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }
        if (content.isBlank() || content == firstChapter.url) {
            throw NoStackTraceException("正文内容为空")
        }
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

    private fun periodLabel(period: String): String {
        return if (period == "month") {
            getString(R.string.agent_month)
        } else {
            getString(R.string.agent_week)
        }
    }

    private fun getString(resId: Int, vararg formatArgs: Any): String {
        return context.getString(resId, *formatArgs)
    }

    data class ChatTurn(
        val role: String,
        val content: String,
        val toolCallId: String? = null,
        val toolCalls: JsonElement? = null
    )

    data class SearchResult(
        val books: List<SearchBook>,
        val allBooks: List<SearchBook>,
        val searchedSources: Int,
        val totalSources: Int,
        val canLoadMore: Boolean
    )

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"
        private const val SEARCH_PAGE_SIZE = 5
        private const val MAX_SEARCH_PAGE_SIZE = 20
        private const val FIRST_SEARCH_SOURCE_LIMIT = 100
        private const val SEARCH_SOURCE_BATCH_SIZE = 100
        private const val SOURCE_CREATE_ATTEMPTS = 3
        private const val MAX_AI_BOOK_CHAPTERS = 50
        private const val BOOK_META_PROMPT =
            "你是专业小说创作助手。根据用户提供的小说类型和主题，生成小说基本信息。" +
                    "只输出一个JSON对象，不要输出解释、注释或markdown代码块。JSON格式：" +
                    "{\"name\":\"书名\",\"author\":\"作者笔名\",\"intro\":\"简介(120-200字)\",\"kind\":\"类型标签，如：科幻\"}。" +
                    "书名要新颖独特，避免常见书名。"
        private const val BOOK_OUTLINE_PROMPT =
            "你是专业小说大纲规划师。根据书籍信息生成章节大纲。" +
                    "只输出一个JSON数组，不要输出解释、注释或markdown代码块。每个元素格式：" +
                    "{\"title\":\"章节标题（有吸引力，能体现本章情节，如：初入星海）\",\"outline\":\"本章内容概要(80-150字)\"}。" +
                    "章节标题不要包含\"第X章\"前缀，只需要章节名。大纲要有完整的情节起承转合和结局。"
        private const val BOOK_CHAPTER_PROMPT =
            "你是长篇小说作者。根据给定的书籍简介、本章大纲和最近几章正文续写本章。要求：" +
                    "1. 只输出本章正文文本，不要输出章节标题，不要任何解释、注释或markdown标记；" +
                    "2. 与前面章节情节连贯、人物一致、文风统一；" +
                    "3. 正文要有具体的情节推进和细节描写，避免与前几章内容大段重复；" +
                    "4. 字数控制在用户要求的目标字数左右。"
        private const val SYSTEM_PROMPT =
            "你是阅读App中的AI助手，使用中文与用户对话。\n\n" +
                    "行为准则：\n" +
                    "1. 需要数据时先调用对应工具获取真实结果，不要凭空捏造。\n" +
                    "2. 工具结果会以卡片形式展示给用户，回答时不要重复完整结果列表。\n" +
                    "3. 搜索书籍时默认展示相关性最高的5条结果，用户明确要求展示更多（如\"展示10个\"）时，将数量填入 limit 参数，最大20条。\n" +
                    "4. 用户要求将书籍加入书架时，先调用 search_books 搜索，再对最匹配的一本调用 add_book_to_shelf。\n" +
                    "5. 用户要求创作/写一部小说时，调用 create_ai_book 工具，将类型填入 type，核心设定填入 theme。" +
                    "用户指定章节数时填入 chapterCount，指定每章字数时填入 wordsPerChapter。\n\n" +
                    "边界：\n" +
                    "1. 不支持的请求应如实说明能力范围，不要编造答案。\n" +
                    "2. 回答保持简洁，默认使用中文。\n\n" +
                    "可用工具（具体参数与调用方式以工具定义为准）：\n"
        private const val SOURCE_CREATE_PROMPT =
            "你是Legado(阅读)的书源开发专家。" +
                    "根据用户提供的网站首页HTML，编写一个完整可用的Legado书源JSON。" +
                    "要求：1. 只输出一个JSON对象，不要输出解释、注释或markdown代码块。" +
                    "2. JSON至少包含 bookSourceName、bookSourceUrl、searchUrl、ruleSearch；" +
                    "详情、目录、正文规则尽量补齐：ruleBookInfo、ruleToc、ruleContent。" +
                    "3. 规则使用Legado规则语法，例如 ruleSearch 中 bookList 使用XPath或CSS选择器，" +
                    "name、author、bookUrl、coverUrl、intro、lastChapter 使用规则表达式。" +
                    "ruleSearch、ruleBookInfo、ruleToc、ruleContent 必须使用JSON对象格式，不要使用字符串格式。" +
                    "4. searchUrl 使用 {{key}} 表示搜索关键字，{{page}} 表示页码，" +
                    "POST请求使用类似 https://example.com/search,POST,body=keyword={{key}} 的格式。" +
                    "5. bookUrlPattern 填写详情页URL特征。" +
                    "6. 输出必须是有效的JSON字符串。"
    }

}
