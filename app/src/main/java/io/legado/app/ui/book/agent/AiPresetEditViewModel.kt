package io.legado.app.ui.book.agent

import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPreset

/**
 * AI 预设编辑
 */
class AiPresetEditViewModel(app: Application) : BaseViewModel(app) {

    fun initData(arguments: Bundle?, success: (aiPreset: AiPreset) -> Unit) {
        execute {
            val argumentId = arguments?.getLong("id")
            if (argumentId != null && argumentId != 0L) {
                return@execute appDb.aiPresetDao.get(argumentId)
            }
            null
        }.onSuccess {
            it?.let(success)
        }
    }

    fun save(aiPreset: AiPreset, success: (() -> Unit)? = null) {
        execute {
            appDb.aiPresetDao.insert(aiPreset)
        }.onSuccess {
            success?.invoke()
        }
    }

}
