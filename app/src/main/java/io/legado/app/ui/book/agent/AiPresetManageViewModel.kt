package io.legado.app.ui.book.agent

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPreset

class AiPresetManageViewModel(application: Application) : BaseViewModel(application) {
    fun resolveCurrent(presets: List<AiPreset>): Long {
        val id = AgentPresetPreferences.currentId(context)
        if (id != 0L && presets.none { it.id == id }) {
            AgentPresetPreferences.select(context, 0L)
            return 0L
        }
        return id
    }

    fun select(preset: AiPreset) {
        AgentPresetPreferences.select(context, preset.id)
    }

    fun delete(preset: AiPreset) {
        execute {
            appDb.aiPresetDao.delete(preset.id)
        }.onSuccess {
            if (AgentPresetPreferences.currentId(context) == preset.id) {
                AgentPresetPreferences.select(context, 0L)
            }
        }
    }
}
