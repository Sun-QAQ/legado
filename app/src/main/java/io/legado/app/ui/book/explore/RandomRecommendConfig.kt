package io.legado.app.ui.book.explore

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

@Keep
data class RandomRecommendCategory(
    val sourceUrl: String = "",
    val title: String = "",
    val url: String = ""
)

object RandomRecommendConfig {
    private const val KEY = "randomRecommendCategories"

    fun read(context: Context): List<RandomRecommendCategory> {
        return GSON.fromJsonArray<RandomRecommendCategory>(context.getPrefString(KEY))
            .getOrDefault(emptyList())
            .filter { it.sourceUrl.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.sourceUrl to it.url }
    }

    fun save(context: Context, categories: List<RandomRecommendCategory>) {
        context.putPrefString(KEY, GSON.toJson(categories))
    }
}
