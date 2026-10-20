package io.legado.app.ui.book.agent

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong

internal object AgentPresetPreferences {
    fun currentId(context: Context): Long = context.getPrefLong(PreferKey.aiPresetId)

    fun select(context: Context, id: Long) {
        context.putPrefLong(PreferKey.aiPresetId, id)
    }

    fun selectedPrompt(context: Context): String {
        val id = currentId(context)
        if (id == 0L) return ""
        // 每次请求读取最新内容，已选预设的编辑无需重新选择。
        val preset = appDb.aiPresetDao.get(id)
        if (preset == null) select(context, 0L)
        return preset?.prompt.orEmpty()
    }
}
