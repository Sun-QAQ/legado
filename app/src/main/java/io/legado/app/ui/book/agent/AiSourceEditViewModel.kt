package io.legado.app.ui.book.agent

import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.utils.GSON

/**
 * AI 供应商编辑
 */
class AiSourceEditViewModel(app: Application) : BaseViewModel(app) {

    var id: Long? = null

    fun initData(arguments: Bundle?, success: (aiSource: AiSource) -> Unit) {
        execute {
            if (id == null) {
                val argumentId = arguments?.getLong("id")
                if (argumentId != null && argumentId != 0L) {
                    id = argumentId
                    return@execute appDb.aiSourceDao.get(argumentId)
                }
            }
            null
        }.onSuccess {
            it?.let(success)
        }
    }

    fun save(aiSource: AiSource, success: (() -> Unit)? = null) {
        id = aiSource.id
        execute {
            appDb.aiSourceDao.insert(aiSource)
        }.onSuccess {
            success?.invoke()
        }
    }

    /**
     * 获取 OpenAI 兼容的模型列表
     */
    fun fetchModels(
        aiSource: AiSource,
        success: (List<String>) -> Unit,
        error: (Throwable) -> Unit
    ) {
        execute {
            AiSourceHelper.fetchModels(aiSource)
        }.onSuccess {
            val newSource = aiSource.copy(
                models = GSON.toJson(it),
                lastUpdateTime = System.currentTimeMillis()
            )
            appDb.aiSourceDao.insert(newSource)
            success.invoke(it)
        }.onError {
            error.invoke(it)
        }
    }

}
