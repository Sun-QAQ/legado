package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * AI 人格（系统提示词）
 */
@Parcelize
@Entity(tableName = "aiPersonas")
data class AiPersona(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    // 名称
    var name: String = "",
    // 系统提示词
    var prompt: String = "",
    // 最后更新时间
    var lastUpdateTime: Long = System.currentTimeMillis()
) : Parcelable
