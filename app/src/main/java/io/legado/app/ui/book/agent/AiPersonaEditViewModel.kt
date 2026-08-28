package io.legado.app.ui.book.agent

import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPersona

/**
 * AI 人格编辑
 */
class AiPersonaEditViewModel(app: Application) : BaseViewModel(app) {

    var id: Long? = null

    fun initData(arguments: Bundle?, success: (aiPersona: AiPersona) -> Unit) {
        execute {
            if (id == null) {
                val argumentId = arguments?.getLong("id")
                if (argumentId != null && argumentId != 0L) {
                    id = argumentId
                    return@execute appDb.aiPersonaDao.get(argumentId)
                }
            }
            null
        }.onSuccess {
            it?.let(success)
        }
    }

    fun save(aiPersona: AiPersona, success: (() -> Unit)? = null) {
        id = aiPersona.id
        execute {
            appDb.aiPersonaDao.insert(aiPersona)
        }.onSuccess {
            success?.invoke()
        }
    }

}
