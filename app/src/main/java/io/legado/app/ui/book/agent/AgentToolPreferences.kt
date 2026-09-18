package io.legado.app.ui.book.agent

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefStringSet

internal object AgentToolPreferences {

    fun disabled(context: Context): Set<String> =
        context.getPrefStringSet(PreferKey.aiDisabledTools).orEmpty().toSet()

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val disabled = disabled(context).toMutableSet()
        if (enabled) disabled.remove(name) else disabled.add(name)
        context.putPrefStringSet(PreferKey.aiDisabledTools, disabled)
    }
}
