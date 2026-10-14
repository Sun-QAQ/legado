package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 放在 AI 对话系统提示词最前面的预设。 */
@Entity(tableName = "aiPresets")
data class AiPreset(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val prompt: String = "",
    val lastUpdateTime: Long = System.currentTimeMillis()
)
