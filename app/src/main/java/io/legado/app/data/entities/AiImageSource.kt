package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.Parcelize

/** 支持 OpenAI 兼容接口及 ComfyUI 工作流的图像生成供应商。 */
@Parcelize
@Entity(tableName = "aiImageSources")
data class AiImageSource(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var baseUrl: String = "",
    var apiKey: String = "",
    var model: String = "",
    var imageSize: String = "1024x1024",
    var responseFormat: String = RESPONSE_FORMAT_AUTO,
    var customBody: String? = null,
    var headers: String? = null,
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    var lastUpdateTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'openai'")
    var apiType: String = TYPE_OPENAI
) : Parcelable {

    fun getHeaderMap(): Map<String, String> {
        val value = headers ?: return emptyMap()
        return GSON.fromJsonObject<Map<String, String>>(value).getOrDefault(emptyMap())
    }

    companion object {
        const val TYPE_OPENAI = "openai"
        const val TYPE_COMFYUI = "comfyui"
        const val RESPONSE_FORMAT_AUTO = "auto"
        const val RESPONSE_FORMAT_URL = "url"
        const val RESPONSE_FORMAT_BASE64 = "b64_json"
    }
}
