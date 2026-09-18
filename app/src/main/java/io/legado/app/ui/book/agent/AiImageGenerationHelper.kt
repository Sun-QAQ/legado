package io.legado.app.ui.book.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.AiImageSource
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.util.concurrent.TimeUnit

internal data class AiGeneratedImageReference(
    val url: String? = null,
    val base64: String? = null
)

internal object AiImageGenerationHelper {

    fun buildRequest(source: AiImageSource, prompt: String, size: String): JsonObject =
        JsonObject().apply {
            addProperty("model", source.model)
            addProperty("prompt", prompt)
            addProperty("n", 1)
            addProperty("size", size.ifBlank { source.imageSize })
            if (source.responseFormat != AiImageSource.RESPONSE_FORMAT_AUTO) {
                addProperty("response_format", source.responseFormat)
            }
        }

    fun parseResponse(body: String): AiGeneratedImageReference {
        val root = JsonParser.parseString(body).asJsonObject
        val item = root.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("响应中没有图片数据")
        val url = item.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim()
        val base64 = item.get("b64_json")?.takeIf { !it.isJsonNull }?.asString?.trim()
        if (url.isNullOrBlank() && base64.isNullOrBlank()) {
            throw IllegalArgumentException("响应中缺少 url 或 b64_json")
        }
        return AiGeneratedImageReference(url, base64)
    }

    suspend fun generate(source: AiImageSource, prompt: String, size: String): ByteArray {
        val client = okHttpClient.newBuilder()
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        val headers = HashMap(source.getHeaderMap())
        if (source.apiKey.isNotBlank() && !headers.containsKey("Authorization")) {
            headers["Authorization"] = if (source.apiKey.startsWith("Bearer ")) {
                source.apiKey
            } else {
                "Bearer ${source.apiKey}"
            }
        }
        val endpoint = source.baseUrl.trimEnd('/').let {
            if (it.endsWith("/images/generations")) it else "$it/images/generations"
        }
        val response = client.newCallStrResponse {
            addHeaders(headers)
            url(endpoint)
            post(buildRequest(source, prompt, size).toString().toRequestBody(JSON_MEDIA_TYPE))
        }
        val body = response.body.orEmpty()
        if (!response.isSuccessful()) {
            val message = runCatching {
                JsonParser.parseString(body).asJsonObject
                    .getAsJsonObject("error")?.get("message")?.asString
            }.getOrNull()
            throw IllegalStateException(message ?: "HTTP ${response.code()}: ${response.message()}")
        }
        val reference = parseResponse(body)
        reference.base64?.let { encoded ->
            require(encoded.length <= MAX_BASE64_CHARS) { "图片数据过大" }
            val bytes = encoded.decodeBase64()?.toByteArray()
                ?: throw IllegalArgumentException("Base64 图片数据无效")
            require(bytes.size <= MAX_IMAGE_BYTES) { "图片数据过大" }
            return bytes
        }
        val imageResponse = client.newCallResponse { url(requireNotNull(reference.url)) }
        imageResponse.use {
            if (!it.isSuccessful) throw IllegalStateException("下载图片失败：HTTP ${it.code}")
            val responseBody = it.body
            val length = responseBody.contentLength()
            require(length < 0 || length <= MAX_IMAGE_BYTES) { "图片文件过大" }
            val bytes = responseBody.source().readByteArray(MAX_IMAGE_BYTES.toLong() + 1)
            require(bytes.size <= MAX_IMAGE_BYTES) { "图片文件过大" }
            return bytes
        }
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    private const val MAX_BASE64_CHARS = 36 * 1024 * 1024
}
