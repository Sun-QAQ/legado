package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.utils.GSON

/**
 * AI 供应商管理
 */
class AiSourceManageViewModel(application: Application) : BaseViewModel(application) {

    fun delete(aiSource: AiSource) {
        execute {
            appDb.aiSourceDao.delete(aiSource)
        }
    }

    fun enable(enabled: Boolean, aiSource: AiSource) {
        execute {
            appDb.aiSourceDao.update(aiSource.copy(enabled = enabled))
        }
    }

    fun fetchModels(aiSource: AiSource) {
        execute {
            AiSourceHelper.fetchModels(aiSource)
        }.onSuccess { models ->
            appDb.aiSourceDao.update(aiSource.copy(
                models = GSON.toJson(models),
                lastUpdateTime = System.currentTimeMillis()
            ))
        }
    }

}
