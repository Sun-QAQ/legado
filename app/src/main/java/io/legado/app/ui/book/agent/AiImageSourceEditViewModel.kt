package io.legado.app.ui.book.agent

import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageSource

class AiImageSourceEditViewModel(application: Application) : BaseViewModel(application) {
    private var id: Long? = null

    fun initData(arguments: Bundle?, success: (AiImageSource) -> Unit) {
        execute {
            val sourceId = arguments?.getLong("id")?.takeIf { it != 0L } ?: return@execute null
            id = sourceId
            appDb.aiImageSourceDao.get(sourceId)
        }.onSuccess { it?.let(success) }
    }

    fun save(source: AiImageSource, success: () -> Unit) {
        id = source.id
        execute { appDb.aiImageSourceDao.insert(source) }.onSuccess { success() }
    }
}
