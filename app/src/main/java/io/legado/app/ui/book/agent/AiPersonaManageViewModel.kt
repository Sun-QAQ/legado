package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPersona

/**
 * AI 人格管理
 */
class AiPersonaManageViewModel(application: Application) : BaseViewModel(application) {

    fun delete(aiPersona: AiPersona) {
        execute {
            appDb.aiPersonaDao.delete(aiPersona.id)
        }
    }

}
