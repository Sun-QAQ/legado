package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.Parcelize

/** AI Agent 使用的网络搜索供应商。 */
@Parcelize
@Entity(tableName = "aiSearchSources")
data class AiSearchSource(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var type: String = TYPE_TAVILY,
    var baseUrl: String = "",
    var apiKey: String = "",
    var method: String = METHOD_POST,
    var queryParameter: String = "query",
    var resultPath: String = "results",
    var titlePath: String = "title",
    var urlPath: String = "url",
    var snippetPath: String = "content",
    var publishedAtPath: String = "published_date",
    var headers: String? = null,
    var customBody: String? = null,
    var defaultCount: Int = 5,
    var language: String = "zh",
    var country: String = "CN",
    var safeSearch: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    var lastUpdateTime: Long = System.currentTimeMillis()
) : Parcelable {

    fun getHeaderMap(): Map<String, String> {
        val value = headers ?: return emptyMap()
        return GSON.fromJsonObject<Map<String, String>>(value).getOrDefault(emptyMap())
    }

    companion object {
        const val TYPE_TAVILY = "tavily"
        const val TYPE_BRAVE = "brave"
        const val TYPE_SEARXNG = "searxng"
        const val TYPE_CUSTOM = "custom"

        const val METHOD_GET = "GET"
        const val METHOD_POST = "POST"

        fun defaultBaseUrl(type: String): String = when (type) {
            TYPE_TAVILY -> "https://api.tavily.com/search"
            TYPE_BRAVE -> "https://api.search.brave.com/res/v1/web/search"
            else -> ""
        }
    }
}
