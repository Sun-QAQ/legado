package io.legado.app.api.controller

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.google.gson.JsonParser
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.ui.book.agent.AgentViewModel
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
        mapOf(
            "suppliers" to sources.map(AiChatPayload::supplier),
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
        val json = JsonParser.parseString(body ?: "{}").asJsonObject
        fun text(key: String) = json.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        when (text("action")) {
            "send" -> {
                check(!agent.waiting.value) { "请等待当前回复完成或停止生成" }
                val message = text("text").trim()
                require(message.isNotEmpty() && message.length <= 32000) { "请输入不超过 32000 字的消息" }
                refreshSelection(agent)
                val source = withContext(Dispatchers.IO) { appDb.aiSourceDao.get(agent.currentSupplierId.value) }
                check(source != null && source.enabled && source.model.isNotBlank()) { "请先在 App 中配置并启用 AI 供应商和默认模型" }
                agent.send(message)
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

    private fun snapshot(agent: AgentViewModel): Map<String, Any?> = mapOf(
        "conversationId" to agent.currentConversationId.value,
        "waiting" to agent.waiting.value,
        "supplierId" to agent.currentSupplierId.value.toString(),
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
