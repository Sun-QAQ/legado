package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPersona
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong

/**
 * AI 人格管理
 */
class AiPersonaManageViewModel(application: Application) : BaseViewModel(application) {

    private val selection = AgentPersonaSelection(
        load = { context.getPrefLong(PreferKey.aiPersonaId) },
        save = { context.putPrefLong(PreferKey.aiPersonaId, it) }
    )

    val currentId: Long get() = selection.currentId

    fun resolveCurrent(personas: List<AiPersona>): Long =
        selection.resolve(personas.mapTo(HashSet()) { it.id })

    fun select(aiPersona: AiPersona) {
        selection.select(aiPersona.id)
    }

    fun delete(aiPersona: AiPersona) {
        execute {
            appDb.aiPersonaDao.delete(aiPersona.id)
        }
    }

}
