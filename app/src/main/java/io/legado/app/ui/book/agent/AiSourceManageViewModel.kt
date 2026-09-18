package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSource
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong

/**
 * AI 供应商管理
 */
class AiSourceManageViewModel(application: Application) : BaseViewModel(application) {

    private val selection = AgentSupplierSelection(
        load = { context.getPrefLong(PreferKey.aiSupplierId) },
        save = { context.putPrefLong(PreferKey.aiSupplierId, it) }
    )

    val currentId: Long get() = selection.currentId

    fun resolveCurrent(sources: List<AiSource>): Long =
        selection.resolve(sources.filter { it.enabled }.map { it.id })

    fun select(source: AiSource, model: String) {
        if (!source.enabled || model.isBlank()) return
        selection.select(source.id)
        execute {
            appDb.aiSourceDao.update(
                source.copy(
                    model = model,
                    lastUpdateTime = System.currentTimeMillis()
                )
            )
        }
    }

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
