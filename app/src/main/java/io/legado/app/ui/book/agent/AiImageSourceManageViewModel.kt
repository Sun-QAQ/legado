package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageSource
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong

class AiImageSourceManageViewModel(application: Application) : BaseViewModel(application) {

    private val selection = AgentImageSourceSelection(
        load = { context.getPrefLong(PreferKey.aiImageSourceId) },
        save = { context.putPrefLong(PreferKey.aiImageSourceId, it) }
    )

    val currentId: Long get() = selection.currentId

    fun resolveDefault(sources: List<AiImageSource>): Long =
        selection.resolve(sources.filter { it.enabled }.map { it.id })

    fun setDefault(source: AiImageSource) {
        if (source.enabled) selection.select(source.id)
    }

    fun enable(enabled: Boolean, source: AiImageSource) {
        execute { appDb.aiImageSourceDao.update(source.copy(enabled = enabled)) }
    }

    fun delete(source: AiImageSource) {
        execute { appDb.aiImageSourceDao.delete(source) }
    }
}
