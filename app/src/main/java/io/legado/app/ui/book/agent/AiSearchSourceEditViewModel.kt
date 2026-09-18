package io.legado.app.ui.book.agent

import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiSearchSource

class AiSearchSourceEditViewModel(application: Application) : BaseViewModel(application) {

    fun initData(arguments: Bundle?, success: (AiSearchSource) -> Unit) {
        execute {
            val id = arguments?.getLong("id")?.takeIf { it != 0L } ?: return@execute null
            appDb.aiSearchSourceDao.get(id)
        }.onSuccess { it?.let(success) }
    }

    fun save(source: AiSearchSource, success: () -> Unit) {
        execute { appDb.aiSearchSourceDao.insert(source) }.onSuccess { success() }
    }
}
