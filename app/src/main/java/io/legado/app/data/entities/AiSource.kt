package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.Parcelize

/**
 * AI 供应商
 */
@Parcelize
@Entity(tableName = "aiSources")
data class AiSource(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    // 名称
    var name: String = "",
    // 接口地址，例如 https://api.openai.com/v1
    var baseUrl: String = "",
    // Api Key
    var apiKey: String = "",
    // 默认模型
    var model: String = "",
    // 自定义请求头，json 格式
    var headers: String? = null,
    // 是否启用
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    // 获取到的模型列表，json 格式
    var models: String? = null,
    // 最后更新时间
    var lastUpdateTime: Long = System.currentTimeMillis()
) : Parcelable {

    fun getModelList(): List<String> {
        val json = models ?: return emptyList()
        return GSON.fromJsonObject<List<String>>(json).getOrDefault(emptyList())
    }

    fun getHeaderMap(): Map<String, String> {
        val headerText = headers ?: return emptyMap()
        return GSON.fromJsonObject<Map<String, String>>(headerText).getOrDefault(emptyMap())
    }

}
