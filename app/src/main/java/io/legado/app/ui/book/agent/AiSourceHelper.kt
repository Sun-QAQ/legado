package io.legado.app.ui.book.agent

import io.legado.app.data.entities.AiSource
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.concurrent.TimeUnit

/**
 * AI 供应商通用请求
 */
object AiSourceHelper {

    suspend fun fetchModels(aiSource: AiSource): List<String> {
        val client = okHttpClient.newBuilder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val headers = HashMap(aiSource.getHeaderMap())
        if (aiSource.apiKey.isNotBlank() && !headers.containsKey("Authorization")) {
            headers["Authorization"] = if (aiSource.apiKey.startsWith("Bearer ")) {
                aiSource.apiKey
            } else {
                "Bearer ${aiSource.apiKey}"
            }
        }
        val response = client.newCallStrResponse {
            addHeaders(headers)
            url(aiSource.baseUrl.trimEnd('/') + "/models")
        }
        val body = response.body ?: throw Exception("响应内容为空")
        val json = GSON.fromJsonObject<Map<String, Any>>(body).getOrThrow()
        @Suppress("UNCHECKED_CAST")
        val data = json["data"] as? List<Map<String, Any>> ?: emptyList()
        return data.mapNotNull { item ->
            (item["id"] as? String)?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
    }

}
