package io.legado.app.api.controller

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.google.gson.JsonParser
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.ui.book.agent.AgentViewModel
import io.legado.app.ui.book.agent.AgentAttachments
import io.legado.app.ui.book.agent.AgentAttachmentUpload
import io.legado.app.ui.book.agent.AiSourceHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** Web 仅传输对话状态；供应商凭据与请求始终留在 App。生命周期随 WebService。 */
class AiChatController {
    private val store = ViewModelStore()
    private val mutex = Mutex()
    private var model: AgentViewModel? = null
    private var closed = false
    private val availableModels = mutableMapOf<Long, List<String>>()
    private val selectedModels = mutableMapOf<Long, String>()

    private suspend fun <T : Any> withAgent(block: suspend (AgentViewModel) -> T): ReturnData =
        try {
            withContext(Dispatchers.Main.immediate) {
                mutex.withLock {
                    check(!closed) { "Web 服务已停止" }
                    val agent = model ?: AgentViewModel(
                        appCtx.applicationContext as Application,
                        conversationPreferenceKey = "webAiConversationId",
                        restoreLatestConversation = false
                    ).also {
                        store.put("web-ai-chat", it)
                        model = it
                        it.startConversationHistory()?.join()
                        refreshSelection(it)
                    }
                    ReturnData().setData(block(agent))
                }
            }
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "AI 对话操作失败")
        }

    private suspend fun refreshSelection(agent: AgentViewModel) {
        val (sources, personas) = withContext(Dispatchers.IO) {
            appDb.aiSourceDao.allEnabled to appDb.aiPersonaDao.all
        }
        agent.restoreSupplier(sources)
        agent.restorePersona(personas)
    }

    suspend fun config(): ReturnData = withAgent { agent ->
        refreshSelection(agent)
        val (sources, personas) = withContext(Dispatchers.IO) {
            appDb.aiSourceDao.allEnabled to appDb.aiPersonaDao.all
        }
        val suppliers = withContext(Dispatchers.IO) {
            sources.map { source -> async {
                var modelError: String? = null
                val models = try {
                    withTimeout(15000) { AiSourceHelper.fetchModels(source) }.also {
                        check(it.isNotEmpty()) { "供应商未返回模型列表" }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    modelError = "模型列表获取失败，显示已保存的模型：${e.localizedMessage ?: "请求失败"}"
                    source.getModelList()
                }
                AiChatPayload.supplier(source, models, modelError)
            } }.awaitAll()
        }
        suppliers.forEach { payload ->
            @Suppress("UNCHECKED_CAST")
            val models = payload["models"] as List<String>
            availableModels[(payload["id"] as String).toLong()] = models
        }
        mapOf(
            "suppliers" to suppliers,
            "personas" to personas.map { mapOf("id" to it.id.toString(), "name" to it.name) },
            "supplierId" to agent.currentSupplierId.value.toString(),
            "personaId" to agent.currentPersonaId.value.toString()
        )
    }

    suspend fun state(): ReturnData = withAgent { snapshot(it) }

    suspend fun history(): ReturnData = withAgent {
        withContext(Dispatchers.IO) {
            appDb.aiConversationDao.observeAll().first().map {
                mapOf("id" to it.id, "title" to it.title, "updatedAt" to it.updatedAt)
            }
        }
    }

    suspend fun action(body: String?): ReturnData = withAgent { agent ->
        require((body?.length ?: 0) <= 15 * 1024 * 1024) { "附件请求过大" }
        val json = JsonParser.parseString(body ?: "{}").asJsonObject
        fun text(key: String) = json.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        when (text("action")) {
            "send" -> {
                check(!agent.waiting.value) { "请等待当前回复完成或停止生成" }
                val uploads = json.getAsJsonArray("attachments")?.map { item ->
                    AgentAttachmentUpload(item.asJsonObject.get("name").asString, item.asJsonObject.get("dataUrl").asString)
                }.orEmpty()
                val prepared = withContext(Dispatchers.IO) { AgentAttachments.prepare(uploads) }
                val message = text("text").trim().ifEmpty { if (prepared.isNotEmpty()) "请分析这些附件。" else "" }
                require(message.isNotEmpty() && message.length <= 32000) { "请输入消息或添加附件，文字不超过 32000 字" }
                refreshSelection(agent)
                val source = withContext(Dispatchers.IO) { appDb.aiSourceDao.get(agent.currentSupplierId.value) }
                val selectedModel = source?.let { selectedModels[it.id] ?: it.model }
                check(source != null && source.enabled && !selectedModel.isNullOrBlank()) { "请先选择 AI 供应商和模型" }
                if (prepared.isEmpty()) agent.send(message, modelOverride = selectedModel) else agent.sendAttachments(message, prepared, selectedModel)
            }
            "cancel" -> agent.cancel()
            "new" -> agent.newConversation().join()
            "open" -> {
                val id = text("id")
                check(withContext(Dispatchers.IO) { appDb.aiConversationDao.get(id) } != null) { "对话不存在" }
                agent.openConversation(id)?.join()
            }
            "supplier" -> {
                check(!agent.waiting.value) { "生成时不能切换供应商" }
                val source = withContext(Dispatchers.IO) { appDb.aiSourceDao.get(text("id").toLong()) }
                check(source != null && source.enabled) { "供应商未启用或已删除" }
                val requestedModel = text("model")
                if (requestedModel.isNotBlank()) {
                    check(requestedModel in availableModels[source.id].orEmpty() || requestedModel == source.model) { "模型列表已变更，请刷新页面重新选择" }
                    selectedModels[source.id] = requestedModel
                }
                agent.selectSupplier(source.id, source.name)
            }
            "persona" -> {
                check(!agent.waiting.value) { "生成时不能切换角色" }
                val id = text("id").toLong()
                if (id == 0L) agent.selectDefaultPersona() else {
                    val persona = withContext(Dispatchers.IO) { appDb.aiPersonaDao.get(id) }
                    check(persona != null) { "角色已删除" }
                    agent.selectPersona(persona.id, persona.name, persona.prompt)
                }
            }
            "more" -> {
                check(!agent.waiting.value) { "请等待当前搜索完成" }
                agent.continueSearch()
            }
            else -> error("不支持的 AI 对话操作")
        }
        snapshot(agent)
    }

    private suspend fun snapshot(agent: AgentViewModel): Map<String, Any?> = mapOf(
        "conversationId" to agent.currentConversationId.value,
        "waiting" to agent.waiting.value,
        "supplierId" to agent.currentSupplierId.value.toString(),
        "model" to (selectedModels[agent.currentSupplierId.value] ?: withContext(Dispatchers.IO) {
            appDb.aiSourceDao.get(agent.currentSupplierId.value)?.model.orEmpty()
        }),
        "personaId" to agent.currentPersonaId.value.toString(),
        "messages" to agent.messages.value.map { AiChatPayload.message(it, agent.streamingText.value) }
    )

    /** 由 WebService 在主线程销毁，取消流式请求和后台任务。 */
    fun close() {
        closed = true
        model?.cancel()
        store.clear()
        model = null
    }
}
