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
import io.legado.app.constant.PreferKey
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.AiPersona
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
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

    private val supplierSelection = AgentSupplierSelection(
        load = { context.getPrefLong(PreferKey.aiSupplierId) },
        save = { context.putPrefLong(PreferKey.aiSupplierId, it) }
    )

    private val _supplierName = MutableStateFlow("")
    val supplierName: StateFlow<String> = _supplierName

    private val _currentSupplierId = MutableStateFlow(supplierSelection.currentId)
    val currentSupplierId: StateFlow<Long> = _currentSupplierId

    private val personaSelection = AgentPersonaSelection(
        load = { context.getPrefLong(PreferKey.aiPersonaId) },
        save = { context.putPrefLong(PreferKey.aiPersonaId, it) }
    )
    private val _currentPersonaId = MutableStateFlow(personaSelection.currentId)
    val currentPersonaId: StateFlow<Long> = _currentPersonaId

    private val _currentPersonaName = MutableStateFlow(getString(R.string.agent_persona_default))
    val currentPersonaName: StateFlow<String> = _currentPersonaName
    private var currentPersonaPrompt: String = SYSTEM_PROMPT

    private val history = arrayListOf<ChatTurn>()
    private var selectedSupplierId: Long = supplierSelection.currentId
    private var lastBooks: List<SearchBook> = emptyList()
    private var lastRepositorySources: List<SourceRepositoryItem> = emptyList()
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
    private var draftSource: BookSource? = null
    private var sourceCreationMode = false
    private var hasSavedSource = false
    private val readingContextProvider = ReadingContextProvider()

    @get:Suppress("unused")
    private val inSourceCreationFlow: Boolean
        get() = sourceCreationMode || draftSource != null

    fun selectSupplier(id: Long, name: String) {
        supplierSelection.select(id)
        selectedSupplierId = id
        _currentSupplierId.value = id
        _supplierName.value = name
    }

    fun selectDefaultPersona() {
        personaSelection.select(0L)
        _currentPersonaId.value = 0L
        currentPersonaPrompt = SYSTEM_PROMPT
        _currentPersonaName.value = getString(R.string.agent_persona_default)
    }

    fun selectPersona(id: Long, name: String, prompt: String) {
        personaSelection.select(id)
        _currentPersonaId.value = id
        currentPersonaPrompt = prompt
        _currentPersonaName.value = name
    }

    fun restorePersona(personas: List<AiPersona>) {
        val restoredId = personaSelection.resolve(personas.mapTo(HashSet()) { it.id })
        val persona = personas.firstOrNull { it.id == restoredId }
        if (persona == null) {
            if (_currentPersonaId.value != 0L || currentPersonaPrompt != SYSTEM_PROMPT) {
                selectDefaultPersona()
            }
            return
        }
        if (_currentPersonaId.value != persona.id ||
            _currentPersonaName.value != persona.name ||
            currentPersonaPrompt != persona.prompt
        ) {
            selectPersona(persona.id, persona.name, persona.prompt)
        }
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
        lastRepositorySources = emptyList()
        lastDisplayLimit = SEARCH_PAGE_SIZE
        draftSource = null
        sourceCreationMode = false
        hasSavedSource = false
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
     * 围绕阅读页当前章节提问。上下文只包含当前章节及此前章节，避免剧透。
     */
    fun askAboutCurrentReading(question: String) {
        val key = question.trim()
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
                answerCurrentReading(supplier, key)
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
                streaming = false,
                repositorySources = lastRepositorySources
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

    /**
     * 使用限定范围的阅读上下文回答问题，不启用工具调用，避免模型绕过章节范围读取后文。
     */
    private suspend fun answerCurrentReading(
        supplier: io.legado.app.data.entities.AiSource,
        question: String
    ) {
        _waiting.value = true
        beginLiveReply()
        try {
            val contextStepId = startStep(getString(R.string.agent_step_read_book), "当前阅读进度")
            val readingContext = withContext(Dispatchers.IO) {
                readingContextProvider.currentReadingContext()
            }
            if (readingContext == null) {
                finishStep(contextStepId, getString(R.string.agent_book_no_content))
                finalizeLive(getString(R.string.agent_book_no_content))
                return
            }
            finishStep(
                contextStepId,
                getString(R.string.agent_step_read_book_done, readingContext.chapterCount, readingContext.content.length)
            )
            val requestStepId = startStep(getString(R.string.agent_step_requesting), "model=${supplier.model}")
            val request = JsonObject().apply {
                addProperty("model", supplier.model)
                add(
                    "messages",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("role", "system")
                                addProperty("content", READING_ASSISTANT_PROMPT)
                            }
                        )
                        add(
                            JsonObject().apply {
                                addProperty(
                                    "content",
                                    "书籍：《${readingContext.bookName}》\n" +
                                        "当前进度：第${readingContext.currentChapter + 1}章\n\n" +
                                        "已读正文（仅限以下章节）：\n${readingContext.content}\n\n" +
                                        "用户问题：$question"
                                )
                                addProperty("role", "user")
                            }
                        )
                    }
                )
            }
            val reply = withContext(Dispatchers.IO) {
                chatCompletionStream(supplier, request) { delta -> appendLiveText(delta) }
            }
            val text = reply.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            finishStep(requestStepId, getString(R.string.agent_step_reply_done))
            finalizeLive(text.ifBlank { getString(R.string.agent_invalid_response) })
        } catch (e: Exception) {
            if (cancelRequested || e is CancellationException) {
                cancelRequested = false
                finalizeLive(getString(R.string.agent_interrupted))
            } else {
                AppLog.put("阅读助手回答失败", e)
                finalizeLive(e.localizedMessage ?: getString(R.string.agent_invalid_response))
            }
        } finally {
            _waiting.value = false
        }
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
     * Agent 循环：模型决定是否调用工具，普通对话最多执行 3 轮，书源创建场景最多执行 20 轮。
     * 书源场景的轮次上限在循环内动态判定（可能中途才进入草稿编辑），回复内容以流式输出
     */
    private suspend fun agentLoop(supplier: io.legado.app.data.entities.AiSource, key: String) {
        _waiting.value = true
        try {
            currentSupplier = supplier
            var finalText = ""
            lastBooks = emptyList()
            lastRepositorySources = emptyList()
            hasBooks = false
            var finished = false
            beginLiveReply()
            val maxRounds: () -> Int = {
                if (inSourceCreationFlow) SOURCE_CREATE_MAX_ROUNDS else DEFAULT_MAX_ROUNDS
            }
            var round = 0
            while (!finished && round < maxRounds()) {
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
                    //书源创建尚未完成时，若模型中途停下解释而不继续调用工具，则注入继续提示强制其继续
                    if (inSourceCreationFlow && !hasSavedSource && round < maxRounds() - 1) {
                        if (content.isNotBlank()) {
                            history.add(ChatTurn(ROLE_ASSISTANT, content))
                        }
                        liveReply = liveReply?.copy(text = "")
                        _streamingText.value = null
                        history.add(ChatTurn(ROLE_USER, getString(R.string.agent_source_continue)))
                        round++
                        continue
                    }
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
                round++
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
            val finalMessage = if (isModelError(message)) {
                message + diagnoseModelError(supplier)
            } else {
                message
            }
            if (liveReply != null) {
                val reply = liveReply!!
                liveReply = reply.copy(
                    steps = reply.steps.map {
                        if (it.state == AgentStepState.RUNNING) {
                            it.copy(
                                state = AgentStepState.FAILED,
                                summary = finalMessage,
                                durationMs = stepStartTimes.remove(it.id)
                                    ?.takeIf { d -> d > 0 }
                                    ?.let { d -> System.currentTimeMillis() - d }
                            )
                        } else {
                            it
                        }
                    }
                )
                finalizeLive("${supplier.name}: $finalMessage")
            } else {
                finishAgentReply("${supplier.name}: $finalMessage")
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

    override suspend fun searchSourceRepository(query: String, limit: Int): String {
        val safeLimit = limit.coerceIn(1, 20)
        val stepId = startStep(
            getString(R.string.agent_step_searching_source_repository),
            getString(R.string.agent_step_detail_search, query)
        )
        return try {
            val result = withContext(Dispatchers.IO) {
                SourceRepository.search(query, safeLimit)
            }
            lastRepositorySources = result
            finishStep(
                stepId,
                summary = getString(R.string.agent_step_source_repository_done, result.size)
            )
            GSON.toJson(result.map { it.toToolResult() })
        } catch (e: Exception) {
            lastRepositorySources = emptyList()
            failStep(stepId, e)
            throw e
        }
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
        val stepId = startStep(getString(R.string.agent_step_source_draft))
        return try {
            val siteUrl = url.trim().trimEnd('/')
            hasSavedSource = false
            val (finalUrl, html) = fetchSiteHtml(siteUrl)
            draftSource = BookSource(
                bookSourceUrl = finalUrl,
                bookSourceName = runCatching {
                    io.legado.app.utils.NetworkUtils.getBaseUrl(finalUrl)
                        ?.substringAfter("://", "")
                        ?.substringBefore("/", "")
                }.getOrNull() ?: finalUrl,
                bookSourceGroup = "AI生成",
                enabled = false
            )
            sourceCreationMode = true
            finishStep(
                stepId,
                summary = getString(R.string.agent_step_source_draft_done, draftSource!!.bookSourceName)
            )
            SOURCE_CREATE_GUIDE
                .replace("{siteUrl}", finalUrl)
                .replace("{html}", html)
                .replace("{draftJson}", GSON.toJson(draftSource))
        } catch (e: Exception) {
            failStep(stepId, e)
            "书源创建失败：${e.localizedMessage ?: e.message ?: "无法访问网站"}"
        }
    }

    override suspend fun fetchPage(url: String, method: String?, body: String?): String {
        val stepId = startStep(getString(R.string.agent_step_source_debug), "fetch_page $url")
        return try {
            val result = withContext(Dispatchers.IO) {
                val source = draftSource ?: BookSource().apply { bookSourceUrl = url }
                val ruleUrl = if (method.equals("POST", true) && !body.isNullOrBlank()) {
                    val option = JsonObject().apply {
                        addProperty("method", "POST")
                        addProperty("body", body)
                    }
                    "$url,$option"
                } else {
                    url
                }
                val analyzeUrl = AnalyzeUrl(
                    mUrl = ruleUrl,
                    baseUrl = source.bookSourceUrl,
                    source = source,
                    coroutineContext = coroutineContext
                )
                val res = analyzeUrl.getStrResponseAwait()
                buildString {
                    append("请求地址：${res.url}\n")
                    append("HTML片段：\n")
                    append(cleanHtmlText(res.body.orEmpty(), SOURCE_HTML_HINT_LENGTH))
                }
            }
            finishStep(stepId)
            result
        } catch (e: Exception) {
            failStep(stepId, e)
            "页面获取失败：${e.localizedMessage ?: e.message ?: "未知错误"}"
        }
    }

    override suspend fun updateBookSource(sourceJson: String): String {
        val stepId = startStep(getString(R.string.agent_step_source_update))
        return try {
            val source = parseDraftSource(sourceJson)
            draftSource = source
            finishStep(stepId, summary = source.bookSourceName)
            "已更新书源草稿：${source.bookSourceName}，地址：${source.bookSourceUrl}\n" +
                "当前草稿JSON：\n${GSON.toJson(source)}"
        } catch (e: Exception) {
            failStep(stepId, e)
            "书源JSON解析失败：${e.localizedMessage ?: e.message ?: "格式错误"}"
        }
    }

    override suspend fun debugSourceSearch(key: String): String {
        val stepId = startStep(getString(R.string.agent_step_source_debug_search), "key=$key")
        val source = draftSource
        if (source == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_draft)))
            return getString(R.string.agent_source_no_draft)
        }
        if (source.searchUrl.isNullOrBlank()) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_search_url)))
            return getString(R.string.agent_source_no_search_url)
        }
        return try {
            val books = withContext(Dispatchers.IO) {
                WebBook.searchBookAwait(source, key)
            }
            if (books.isEmpty()) {
                finishStep(stepId, summary = getString(R.string.agent_step_source_debug_done_empty))
                "搜索调试成功但未解析到书籍。可能原因：ruleSearch 的 bookList 选择器未匹配。" +
                    "\n搜索页HTML片段：\n" + withContext(Dispatchers.IO) {
                    fetchResolvedHtml(source, key)
                }
            } else {
                val result = books.take(10).map { it.toToolResult() }
                finishStep(
                    stepId,
                    summary = getString(R.string.agent_step_source_debug_done, result.size)
                )
                GSON.toJson(result)
            }
        } catch (e: Exception) {
            val hint = withContext(Dispatchers.IO) { fetchResolvedHtml(source, key) }
            failStep(stepId, e)
            "搜索调试失败：${e.localizedMessage ?: e.message ?: "解析错误"}\n$hint"
        }
    }

    override suspend fun debugSourceBookInfo(bookUrl: String): String {
        val stepId = startStep(getString(R.string.agent_step_source_debug_info), "bookUrl=$bookUrl")
        val source = draftSource
        if (source == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_draft)))
            return getString(R.string.agent_source_no_draft)
        }
        return try {
            val (result, nameBlank) = withContext(Dispatchers.IO) {
                val book = Book()
                book.origin = source.bookSourceUrl
                book.bookUrl = bookUrl
                WebBook.getBookInfoAwait(source, book)
                Pair(
                    GSON.toJson(mapOf(
                        "name" to book.name,
                        "author" to book.author,
                        "kind" to book.kind,
                        "intro" to book.intro,
                        "coverUrl" to book.coverUrl,
                        "wordCount" to book.wordCount,
                        "latestChapterTitle" to book.latestChapterTitle,
                        "tocUrl" to book.tocUrl
                    )),
                    book.name.isBlank()
                )
            }
            finishStep(
                stepId,
                summary = getString(R.string.agent_step_source_debug_done, 1)
            )
            if (nameBlank) {
                "详情页调试成功但未解析到书名，说明 ruleBookInfo 未设置或选择器不正确。\n" +
                    "请先设置 ruleBookInfo 后重试，或参考详情页HTML：\n" +
                    withContext(Dispatchers.IO) { fetchPageHtml(source, bookUrl) } + "\n$result"
            } else {
                result
            }
        } catch (e: Exception) {
            val hint = withContext(Dispatchers.IO) { fetchPageHtml(source, bookUrl) }
            failStep(stepId, e)
            "详情页调试失败：${e.localizedMessage ?: e.message ?: "解析错误"}\n$hint"
        }
    }

    override suspend fun debugSourceToc(tocUrl: String, bookUrl: String?): String {
        val stepId = startStep(getString(R.string.agent_step_source_debug_toc), "tocUrl=$tocUrl")
        val source = draftSource
        if (source == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_draft)))
            return getString(R.string.agent_source_no_draft)
        }
        if (source.ruleToc == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_rule_toc)))
            return getString(R.string.agent_source_no_rule_toc)
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                val book = Book()
                book.origin = source.bookSourceUrl
                book.bookUrl = bookUrl?.takeIf { it.isNotBlank() } ?: tocUrl
                book.tocUrl = tocUrl
                WebBook.getChapterListAwait(source, book).getOrThrow()
                    .filterNot { it.isVolume && it.url.startsWith(it.title) }
                    .take(30)
                    .map { mapOf("title" to it.title, "url" to it.url) }
            }
            finishStep(
                stepId,
                summary = getString(R.string.agent_step_source_debug_done, result.size)
            )
            GSON.toJson(mapOf("count" to result.size, "chapters" to result))
        } catch (e: Exception) {
            val hint = withContext(Dispatchers.IO) { fetchPageHtml(source, tocUrl) }
            failStep(stepId, e)
            "目录页调试失败：${e.localizedMessage ?: e.message ?: "解析错误"}\n$hint"
        }
    }

    override suspend fun debugSourceContent(
        chapterUrl: String,
        bookUrl: String?,
        tocUrl: String?
    ): String {
        val stepId = startStep(getString(R.string.agent_step_source_debug_content), "chapterUrl=$chapterUrl")
        val source = draftSource
        if (source == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_draft)))
            return getString(R.string.agent_source_no_draft)
        }
        if (source.getContentRule().content.isNullOrEmpty()) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_rule_content)))
            return getString(R.string.agent_source_no_rule_content)
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                val book = Book()
                book.origin = source.bookSourceUrl
                book.bookUrl = bookUrl?.takeIf { it.isNotBlank() } ?: tocUrl ?: chapterUrl
                book.tocUrl = tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
                val chapter = BookChapter(
                    url = chapterUrl,
                    title = "调试",
                    bookUrl = book.bookUrl
                )
                val content = WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    nextChapterUrl = null,
                    needSave = false
                )
                buildString {
                    append("正文长度：${content.length}\n")
                    append("正文预览：\n")
                    append(cleanHtmlText(content, SOURCE_CONTENT_PREVIEW_LENGTH))
                }
            }
            finishStep(stepId)
            result
        } catch (e: Exception) {
            val hint = withContext(Dispatchers.IO) { fetchPageHtml(source, chapterUrl) }
            failStep(stepId, e)
            "正文页调试失败：${e.localizedMessage ?: e.message ?: "解析错误"}\n$hint"
        }
    }

    override suspend fun saveBookSource(): String {
        val stepId = startStep(getString(R.string.agent_step_source_save))
        val source = draftSource
        if (source == null) {
            failStep(stepId, NoStackTraceException(getString(R.string.agent_source_no_draft)))
            return getString(R.string.agent_source_no_draft)
        }
        return try {
            validateBookSource(source)
            source.bookSourceGroup = "AI生成"
            source.enabled = true
            source.lastUpdateTime = System.currentTimeMillis()
            appDb.bookSourceDao.insert(source)
            sourceCreationMode = false
            hasSavedSource = true
            draftSource = null
            finishStep(
                stepId,
                summary = getString(R.string.agent_step_source_save_done, source.bookSourceName)
            )
            "书源创建成功：${source.bookSourceName}，地址：${source.bookSourceUrl}，已保存到AI生成分组"
        } catch (e: Exception) {
            failStep(stepId, e)
            "书源保存失败：${e.localizedMessage ?: e.message ?: "校验失败"}。请继续修正规则后重试，草稿仍然保留"
        }
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
        return (name == "create_book_source" && result.startsWith("书源创建失败")) ||
            (name == "save_book_source" && result.startsWith("书源保存失败")) ||
            (name == "create_ai_book" && result.startsWith("小说创作失败")) ||
            (name == "read_book_content" && (
                result.startsWith(getString(R.string.agent_book_not_found)) ||
                    result.startsWith(getString(R.string.agent_book_no_chapters, "")) ||
                    result.startsWith(getString(R.string.agent_book_no_content)) ||
                    result.startsWith(getString(R.string.agent_book_read_failed, ""))
                ))
    }

    private fun summarizeToolArguments(name: String, args: JsonObject): String? {
        return when (name) {
            "search_books", "search_source_repository" -> buildString {
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
            "create_book_source", "fetch_page" ->
                args.get("url")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "url=$it" }
            "update_book_source" -> "更新书源草稿"
            "debug_source_search" -> args.get("key")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { "key=$it" }
            "debug_source_book_info" -> args.get("bookUrl")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { "bookUrl=$it" }
            "debug_source_toc" -> args.get("tocUrl")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { "tocUrl=$it" }
            "debug_source_content" -> args.get("chapterUrl")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { "chapterUrl=$it" }
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
            "read_book_content" -> buildString {
                args.get("book")?.takeIf { !it.isJsonNull }?.asString?.let {
                    append("book=$it")
                }
                args.get("startIndex")?.takeIf { !it.isJsonNull }?.asInt?.let {
                    if (isNotEmpty()) append(", ")
                    append("chapter=$it")
                }
                args.get("count")?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it > 1 }?.let {
                    if (isNotEmpty()) append(", ")
                    append("count=$it")
                }
            }.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun summarizeToolResult(name: String, result: String): String? {
        return when (name) {
            "search_books", "search_source_repository" -> {
                val parsed = runCatching { JsonParser.parseString(result) }.getOrNull()
                if (parsed is JsonArray) {
                    if (name == "search_source_repository") {
                        getString(R.string.agent_step_source_repository_done, parsed.size())
                    } else {
                        getString(R.string.agent_step_search_tool_done, parsed.size())
                    }
                } else {
                    result.lineSequence().firstOrNull()?.take(60)
                        ?: getString(R.string.agent_step_unknown_result)
                }
            }
            "create_book_source" -> getString(R.string.agent_step_source_draft_done, "已获取首页")
            "update_book_source" -> "草稿已更新"
            "debug_source_search" -> {
                val parsed = runCatching { JsonParser.parseString(result) }.getOrNull()
                if (parsed is JsonArray) {
                    getString(R.string.agent_step_source_debug_done, parsed.size())
                } else {
                    result.lineSequence().firstOrNull()?.take(60)
                        ?: getString(R.string.agent_step_unknown_result)
                }
            }
            "debug_source_book_info", "debug_source_toc" ->
                result.lineSequence().firstOrNull()?.take(60)
                    ?: getString(R.string.agent_step_unknown_result)
            "debug_source_content" -> result.lineSequence().firstOrNull()?.take(60)
                ?: getString(R.string.agent_step_unknown_result)
            "save_book_source" -> result.lineSequence().firstOrNull()?.take(60)
                ?: getString(R.string.agent_step_unknown_result)
            "reading_report" -> getString(R.string.agent_step_reading_report_done)
            "library_stats" -> getString(R.string.agent_step_library_stats_done_short)
            "create_ai_book" -> result.lineSequence().firstOrNull()?.take(80)
                ?: getString(R.string.agent_step_unknown_result)
            "read_book_content" -> result.lineSequence().firstOrNull()?.take(60)
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

    /**
     * 按书籍与章节读取正文：定位书架上的书籍，读取指定章节区间的正文。
     * 本地已缓存内容优先，网络书未缓存时自动联网抓取并缓存
     */
    override suspend fun readBookContent(
        bookQuery: String,
        startIndex: Int,
        count: Int,
        maxChars: Int
    ): String {
        if (bookQuery.isBlank()) {
            return getString(R.string.agent_book_not_found)
        }
        val stepId = startStep(
            getString(R.string.agent_step_read_book),
            "$bookQuery · chapter=$startIndex count=$count"
        )
        return try {
            val book = withContext(Dispatchers.IO) { resolveBook(bookQuery) }
                ?: return failReadStep(stepId, getString(R.string.agent_book_not_found))
            val chapters = withContext(Dispatchers.IO) {
                readingContextProvider.getBookChapters(book)
            }
            if (chapters.isEmpty()) {
                return failReadStep(stepId, getString(R.string.agent_book_no_chapters, book.name))
            }
            val from = startIndex.coerceAtLeast(0)
            if (from >= chapters.size) {
                return failReadStep(stepId, getString(R.string.agent_book_no_chapters, book.name))
            }
            val to = (from + count.coerceAtLeast(1) - 1).coerceAtMost(chapters.size - 1)
            val selected = chapters.subList(from, to + 1).filterNot { it.isVolume }
            if (selected.isEmpty()) {
                return failReadStep(stepId, getString(R.string.agent_book_no_content))
            }
            val source = if (book.isLocal) null else appDb.bookSourceDao.getBookSource(book.origin)
            val content = withContext(Dispatchers.IO) {
                buildString {
                    selected.forEachIndexed { i, chapter ->
                        val next = chapters.getOrNull(from + i + 1)
                        val text = readingContextProvider.getChapterText(book, chapter, next, source)
                            ?: getString(R.string.agent_book_content_missing)
                        append("【第${chapter.index + 1}章 ${chapter.title}】\n")
                        append(text)
                        if (i != selected.lastIndex) append("\n\n")
                    }
                }
            }
            val finalContent = if (maxChars > 0 && content.length > maxChars) {
                content.take(maxChars) + "\n\n（内容过长，已截断至前${maxChars}字）"
            } else {
                content
            }
            finishStep(
                stepId,
                summary = getString(
                    R.string.agent_step_read_book_done,
                    selected.size,
                    finalContent.length
                )
            )
            "书名：《${book.name}》 作者：${book.author}，全书共${chapters.size}章，" +
                "已返回第${from + 1}章到第${to + 1}章（共${selected.size}章）：\n\n$finalContent"
        } catch (e: Exception) {
            if (cancelRequested || e is CancellationException) {
                throw e
            }
            failStep(stepId, e)
            getString(
                R.string.agent_book_read_failed,
                e.localizedMessage ?: e.message ?: "未知错误"
            )
        }
    }

    private fun failReadStep(stepId: String, message: String): String {
        failStep(stepId, NoStackTraceException(message))
        return message
    }

    /**
     * 定位书籍：bookUrl 精确匹配 → "书名,作者" 精确匹配 → 书名模糊搜索
     */
    private suspend fun resolveBook(query: String): Book? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return null
        appDb.bookDao.getBook(trimmed)?.let { return it }
        val (name, author) = if (trimmed.contains(",")) {
            trimmed.substringBefore(",").trim() to trimmed.substringAfter(",").trim()
        } else {
            trimmed to ""
        }
        if (name.isBlank()) return null
        if (author.isNotBlank()) {
            appDb.bookDao.getBook(name, author)?.let { return it }
        }
        val candidates = appDb.bookDao.flowSearch(name).firstOrNull().orEmpty()
        candidates.firstOrNull { it.name == name }?.let { return it }
        return candidates.firstOrNull()
    }

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
            // 拼接"第x章"前缀，保证标题唯一，避免正文文件因标题MD5重复而被覆盖
            return plans.take(count).mapIndexed { index, plan ->
                val cleanTitle = plan.title
                    .replace(Regex("^第\\s*[\\d一二三四五六七八九十百零〇]+\\s*章[：:.、\\s]*"), "")
                    .trim()
                plan.copy(title = "第${index + 1}章 $cleanTitle")
            }
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
        root.addProperty("model", supplier.model)
        val messages = JsonArray()
        messages.add(
            JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", currentPersonaPrompt + AgentTools.overview())
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
        val startedAt = System.nanoTime()
        var httpCode: Int? = null
        try {
        val response = client.newCallStrResponse {
            addHeaders(headers)
            url(supplier.baseUrl.trimEnd('/') + "/chat/completions")
            post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
        }
        httpCode = response.code()
        logChatMetadata(supplier.model, body, startedAt, httpCode)
        val bodyText = response.body
        val json = bodyText?.takeIf { it.isNotBlank() }?.let {
            runCatching { JsonParser.parseString(it) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        }
        if (json == null) {
            throw invalidResponseException(response.code())
        }
        json.get("error")?.takeIf { !it.isJsonNull }?.let {
            throw AiApiException("HTTP ${response.code()}")
        }
        if (!response.isSuccessful()) {
            throw AiApiException(parseApiError(response.code()))
        }
        val choices = json.get("choices")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
        if (choices == null || choices.size() == 0) {
            throw invalidResponseException(response.code())
        }
        val choice = choices[0].takeIf { it.isJsonObject }?.asJsonObject
            ?: throw invalidResponseException(response.code())
        choice.get("message")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            return it
        }
        val text = choice.get("text")?.takeIf { !it.isJsonNull }?.asString
        if (!text.isNullOrBlank()) {
            return JsonObject().apply {
                addProperty("content", text)
            }
        }
        throw invalidResponseException(response.code())
        } catch (e: Exception) {
            if (httpCode == null) {
                logChatMetadata(supplier.model, body, startedAt, null)
            }
            throw e
        }
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
        val streamingBody = body.deepCopy().apply { addProperty("stream", true) }
        return try {
            doChatCompletionStream(supplier, streamingBody, onDelta)
        } catch (e: AiApiException) {
            try {
                chatCompletion(supplier, body.deepCopy().apply { remove("stream") })
            } catch (e2: AiApiException) {
                if (isModelError(e2.message ?: "")) {
                    // 部分网关拒绝携带 tools 的请求并返回误导性的模型错误，尝试去掉 tools 后重试
                    chatCompletion(
                        supplier,
                        body.deepCopy().apply {
                            remove("stream")
                            remove("tools")
                        }
                    )
                } else {
                    throw e2
                }
            }
        }
    }

    /**
     * 流式调用 /chat/completions：读取 SSE 分块，将 content 增量通过 onDelta 回调实时输出，
     * 同时按 index 累积合并分片的 tool_calls，返回聚合后的完整消息
     */
    private suspend fun doChatCompletionStream(
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
        val request = body.toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())
        val httpRequest = Request.Builder()
            .apply {
                addHeaders(headers)
                url(supplier.baseUrl.trimEnd('/') + "/chat/completions")
                post(request)
            }
            .build()
        val call = client.newCall(httpRequest)
        val startedAt = System.nanoTime()
        var httpCode: Int? = null
        currentCall = call
        try {
            val response = call.execute()
            httpCode = response.code
            if (!response.isSuccessful) {
                val bodyText = response.body?.string()
                throw AiApiException(parseApiError(response.code))
            }
            val contentBuilder = StringBuilder()
            val toolCallAccumulator = StreamingToolCallAccumulator()
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
                            toolCallAccumulator.append(toolCalls)
                        }
                    }
                }
            } finally {
                response.close()
            }
            return JsonObject().apply {
                addProperty("content", contentBuilder.toString())
                if (toolCallAccumulator.isNotEmpty()) {
                    add("tool_calls", toolCallAccumulator.toJsonArray())
                }
            }
        } finally {
            if (currentCall === call) {
                currentCall = null
            }
            logChatMetadata(supplier.model, body, startedAt, httpCode)
        }
    }

    private fun invalidResponseException(code: Int? = null): Exception {
        val prefix = code?.let { "HTTP $it\n" }.orEmpty()
        return Exception(prefix + getString(R.string.agent_invalid_response))
    }

    /**
     * 错误信息只保留 HTTP 状态码，避免服务端响应正文进入日志。
     */
    private fun parseApiError(code: Int): String {
        return "HTTP $code"
    }

    private class AiApiException(message: String) : Exception(message)

    private fun logChatMetadata(
        model: String,
        body: JsonObject,
        startedAt: Long,
        httpCode: Int?
    ) {
        AppLog.put(
            AgentLogMetadata.format(
                model,
                body,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                httpCode
            )
        )
    }

    /**
     * 判断是否属于"模型不可用/不支持"类错误，此时自动拉取供应商可用模型辅助排查
     */
    private fun isModelError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("model") && (
            lower.contains("not supported") ||
                lower.contains("does not exist") ||
                lower.contains("modelerror") ||
                lower.contains("not found")
            )
    }

    private suspend fun diagnoseModelError(
        supplier: io.legado.app.data.entities.AiSource
    ): String {
        return runCatching {
            val models = AiSourceHelper.fetchModels(supplier)
            if (models.isEmpty()) {
                ""
            } else {
                "\n该供应商可用模型：${models.joinToString("、")}"
            }
        }.getOrDefault("")
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

    /**
     * 宽松解析书源草稿：只需 bookSourceUrl 非空，名称可缺省
     */
    private fun parseDraftSource(json: String): BookSource {
        val jsonText = extractJson(json)
        val source = if (jsonText.trimStart().startsWith("[")) {
            GSON.fromJsonArray<BookSource>(jsonText).getOrNull()?.firstOrNull()
        } else {
            GSON.fromJsonObject<BookSource>(jsonText).getOrNull()
        }
            ?: throw NoStackTraceException("书源JSON格式错误")
        if (source.bookSourceUrl.isBlank()) {
            throw NoStackTraceException("bookSourceUrl为空")
        }
        if (source.bookSourceName.isBlank()) {
            source.bookSourceName = runCatching {
                io.legado.app.utils.NetworkUtils.getBaseUrl(source.bookSourceUrl)
                    ?.substringAfter("://", "")
                    ?.substringBefore("/", "")
            }.getOrNull() ?: source.bookSourceUrl
        }
        return source
    }

    private fun cleanHtmlText(html: String, max: Int): String {
        return html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)
    }

    /**
     * 解析书源草稿的搜索地址（替换 {{key}} 等占位符）并抓取HTML，供调试失败时定位规则
     */
    private suspend fun fetchResolvedHtml(source: BookSource, key: String?): String {
        return try {
            val analyzeUrl = AnalyzeUrl(
                mUrl = source.searchUrl ?: source.bookSourceUrl,
                key = key,
                baseUrl = source.bookSourceUrl,
                source = source,
                ruleData = RuleData(),
                coroutineContext = coroutineContext
            )
            val res = analyzeUrl.getStrResponseAwait()
            buildString {
                append("搜索地址：${res.url}\n")
                append("HTML片段：\n")
                append(cleanHtmlText(res.body.orEmpty(), SOURCE_HTML_HINT_LENGTH))
            }
        } catch (e: Exception) {
            "无法获取搜索页：${e.localizedMessage ?: e.message ?: "未知错误"}"
        }
    }

    /**
     * 直接用书源草稿抓取指定网页HTML，供调试失败时定位规则
     */
    private suspend fun fetchPageHtml(source: BookSource, url: String): String {
        return try {
            val analyzeUrl = AnalyzeUrl(
                mUrl = url,
                baseUrl = source.bookSourceUrl,
                source = source,
                coroutineContext = coroutineContext
            )
            val res = analyzeUrl.getStrResponseAwait()
            buildString {
                append("请求地址：${res.url}\n")
                append("HTML片段：\n")
                append(cleanHtmlText(res.body.orEmpty(), SOURCE_HTML_HINT_LENGTH))
            }
        } catch (e: Exception) {
            "无法获取页面：${e.localizedMessage ?: e.message ?: "未知错误"}"
        }
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
        private const val DEFAULT_MAX_ROUNDS = 3
        private const val SOURCE_CREATE_MAX_ROUNDS = 20
        private const val SOURCE_HTML_HINT_LENGTH = 6000
        private const val SOURCE_CONTENT_PREVIEW_LENGTH = 1000
        private const val MAX_AI_BOOK_CHAPTERS = 50
        private const val READING_ASSISTANT_PROMPT =
            "你是小说阅读助手，使用中文回答。只能依据用户提供的已读正文回答，绝不引用、推测或暗示当前章节之后的情节。" +
                "若正文中没有足够依据，应明确说“已读内容中未提及”，不要编造。" +
                "回答剧情、人物或事件时，在对应句末标注章节依据，格式如【第12章 章节名】。" +
                "回答简洁清晰；用户要求总结时按要点归纳。"
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
                    "用户指定章节数时填入 chapterCount，指定每章字数时填入 wordsPerChapter。\n" +
                    "6. 用户要求编写/生成书源时，严格按以下流程逐步完成，不要一次性盲目生成：\n" +
                    "   ① 先调用 create_book_source(url) 获取网站首页HTML和书源草稿，认真分析网站结构；\n" +
                    "   ② 每写一类规则前，先用 fetch_page 查看对应页面（搜索页/详情页/目录页/正文页）的HTML结构；\n" +
                    "   ③ 用 update_book_source 依次写入 searchUrl+ruleSearch、ruleBookInfo、ruleToc、ruleContent；\n" +
                    "   ④ 每写完一类规则就用对应的 debug_source_search / debug_source_book_info / debug_source_toc / debug_source_content 调试，失败则根据返回的HTML修正后立即重试；\n" +
                    "   ⑤ 全部调试通过后调用 save_book_source 校验保存。\n" +
"   ⑥ 编写书源时请自主连续调用工具完成全部流程，不要中途停下向用户解释，" +
"必须在 save_book_source 成功后才结束；调试失败则根据返回的HTML修正规则后立即重试。\n" +
"7. 用户询问书籍内容、要求总结或续写某本书的章节时，先调用 read_book_content 按书名和章节号读取正文，" +
"基于真实内容回答，不要凭空编造。\n\n" +
"边界：\n" +
                    "1. 不支持的请求应如实说明能力范围，不要编造答案。\n" +
                    "2. 回答保持简洁，默认使用中文。\n\n" +
                    "可用工具（具体参数与调用方式以工具定义为准）：\n"
        private const val SOURCE_CREATE_GUIDE =
            "已获取网站首页并创建书源草稿，请严格按以下步骤逐步编写书源，每一步验证通过后才能进入下一步，未通过则修正后重试：\n" +
                    "\n网站地址：{siteUrl}\n首页HTML片段：\n{html}\n\n" +
                    "当前书源草稿JSON：\n{draftJson}\n\n" +
                    "流程：\n" +
                    "1. 分析首页HTML，找到搜索表单/搜索链接（搜索接口、关键字参数名）。\n" +
                    "2. 用 fetch_page 访问搜索页（把测试词\"遮天\"代入），查看搜索结果的HTML结构。\n" +
                    "3. 编写搜索规则：调用 update_book_source 写入 searchUrl（{{key}} 表示关键字，{{page}} 表示页码；" +
                    "POST 用 url,{\"method\":\"POST\",\"body\":\"key=xxx\"}）和 ruleSearch（bookList/name/author/bookUrl/coverUrl/intro/lastChapter，JSON对象格式）。\n" +
                    "4. 调用 debug_source_search(\"遮天\") 调试搜索；解析到书籍列表则继续，否则回到第2步修正 searchUrl 或 ruleSearch。\n" +
                    "5. 取搜索结果中一本的 bookUrl，用 fetch_page 访问详情页，查看详情页HTML结构。\n" +
                    "6. 编写详情规则：调用 update_book_source 写入 ruleBookInfo（name/author/intro/coverUrl/tocUrl）。\n" +
                    "7. 调用 debug_source_book_info(bookUrl) 调试详情；解析到书名则继续，否则回到第5步修正 ruleBookInfo。\n" +
                    "8. 取 tocUrl 用 fetch_page 访问目录页，查看章节列表HTML，编写 ruleToc（chapterList/chapterName/chapterUrl）。\n" +
                    "9. 调用 debug_source_toc(tocUrl) 调试目录；解析到章节列表则继续，否则回到第8步修正 ruleToc。\n" +
                    "10. 取某一章的章节url用 fetch_page 访问正文页，查看正文HTML，编写 ruleContent（content/title/author）。\n" +
                    "11. 调用 debug_source_content(chapterUrl) 调试正文；解析到正文则继续，否则回到第10步修正 ruleContent。\n" +
                    "12. 全部调试通过后调用 save_book_source 校验并保存，完成等待。\n\n" +
                    "规则语法提示：bookList/chapterList 等列表选择器用XPath或CSS；name/author/bookUrl/content 等字段用规则表达式；" +
                    "ruleSearch、ruleBookInfo、ruleToc、ruleContent 必须使用JSON对象格式。\n\n" +
                    "重要：请自主连续调用工具完成全部步骤，不要中途停下向用户解释，直到 save_book_source 成功。" +
                    "某步失败就根据返回的HTML修正对应规则后立即重试。"
    }

}

internal class AgentSupplierSelection(
    load: () -> Long,
    private val save: (Long) -> Unit
) {
    var currentId: Long = load().coerceAtLeast(0L)
        private set

    fun select(id: Long) {
        currentId = id.coerceAtLeast(0L)
        save(currentId)
    }
}

internal class AgentPersonaSelection(
    load: () -> Long,
    private val save: (Long) -> Unit
) {
    var currentId: Long = load().coerceAtLeast(0L)
        private set

    fun select(id: Long) {
        currentId = id.coerceAtLeast(0L)
        save(currentId)
    }

    fun resolve(availableIds: Set<Long>): Long {
        if (currentId != 0L && currentId !in availableIds) {
            select(0L)
        }
        return currentId
    }
}
