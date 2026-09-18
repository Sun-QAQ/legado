package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSearchSource
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong

class AiSearchSourceManageViewModel(application: Application) : BaseViewModel(application) {

    private val selection = AgentSearchSourceSelection(
        load = { context.getPrefLong(PreferKey.aiSearchSourceId) },
        save = { context.putPrefLong(PreferKey.aiSearchSourceId, it) }
    )

    fun resolveDefault(sources: List<AiSearchSource>): Long =
        selection.resolve(sources.filter { it.enabled }.map { it.id })

    fun setDefault(source: AiSearchSource) {
        if (source.enabled) selection.select(source.id)
    }

    fun enable(enabled: Boolean, source: AiSearchSource) {
        execute { appDb.aiSearchSourceDao.update(source.copy(enabled = enabled)) }
    }

    fun delete(source: AiSearchSource) {
        execute { appDb.aiSearchSourceDao.delete(source) }
    }
}
