package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.legado.app.data.entities.AiImageSource
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random

/** ComfyUI 原生 API：提交工作流、轮询历史、获取图片下载地址。 */
internal object ComfyUiImageGenerationHelper {

    fun validateWorkflow(value: String): JsonObject {
        val root = JsonParser.parseString(value)
        require(root.isJsonObject) { "请填写 ComfyUI API 格式工作流 JSON" }
        val workflow = root.asJsonObject.let {
            if (it.get("prompt")?.isJsonObject == true) it.getAsJsonObject("prompt") else it
        }
        require(workflow.size() > 0 && workflow.entrySet().all { (_, node) ->
            node.isJsonObject && node.asJsonObject.let {
                it.get("class_type")?.isJsonPrimitive == true &&
                    it.get("class_type").asString.isNotBlank() && it.get("inputs")?.isJsonObject == true
            }
        }) { "请使用 ComfyUI 导出的 API 格式工作流，而不是普通画布 JSON" }
        require(workflow.entrySet().any { (_, node) ->
            containsPrompt(node.asJsonObject.getAsJsonObject("inputs"))
        }) { "请将工作流中正向提示词的文本替换为 {{prompt}}" }
        return root.asJsonObject
    }

    private fun containsPrompt(element: JsonElement): Boolean = when {
        element.isJsonObject -> element.asJsonObject.entrySet().any { containsPrompt(it.value) }
        element.isJsonArray -> element.asJsonArray.any(::containsPrompt)
        element.isJsonPrimitive && element.asJsonPrimitive.isString ->
            element.asString.contains("{{prompt}}")
        else -> false
    }

    fun buildRequest(
        source: AiImageSource,
        prompt: String,
        size: String,
        seed: Long = Random.nextLong(1L shl 53)
    ): JsonObject {
        val root = validateWorkflow(source.customBody.orEmpty())
        val dimensions = size.ifBlank { source.imageSize }.split('x')
        require(dimensions.size == 2 && dimensions.all { (it.toIntOrNull() ?: 0) > 0 }) {
            "图片尺寸无效，请使用 1024x1024 等格式"
        }
        // 在 JSON 树中替换，提示词中的引号、换行和占位符都只作为文本处理。
        val values = mapOf(
            "{{prompt}}" to JsonPrimitive(prompt),
            "{{width}}" to JsonPrimitive(dimensions[0].toInt()),
            "{{height}}" to JsonPrimitive(dimensions[1].toInt()),
            "{{seed}}" to JsonPrimitive(seed)
        )
        val request = if (root.get("prompt")?.isJsonObject == true) root else JsonObject().apply {
            add("prompt", root)
        }
        val workflow = request.getAsJsonObject("prompt")
        workflow.entrySet().forEach { (_, node) ->
            val inputs = node.asJsonObject.getAsJsonObject("inputs")
            node.asJsonObject.add("inputs", replaceValues(inputs, values))
        }
        // 模板可能来自一次历史请求；让服务端为本次请求分配独立 ID。
        request.remove("prompt_id")
        return request
    }

    private fun replaceValues(element: JsonElement, values: Map<String, JsonPrimitive>): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet().forEach { (key, value) -> add(key, replaceValues(value, values)) }
        }
        element.isJsonArray -> JsonArray().apply {
            element.asJsonArray.forEach { add(replaceValues(it, values)) }
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            val text = element.asString
            values[text] ?: JsonPrimitive(PLACEHOLDER.replace(text) { values.getValue(it.value).asString })
        }
        else -> element.deepCopy()
    }

    fun baseUrl(value: String): HttpUrl {
        val parsed = value.trim().toHttpUrl()
        val path = parsed.encodedPath.trimEnd('/').removeSuffix("/prompt").trimEnd('/')
        return parsed.newBuilder().encodedPath("$path/").query(null).fragment(null).build()
    }

    fun parsePromptId(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        if (root.has("error") || root.getAsJsonObject("node_errors")?.size()?.let { it > 0 } == true) {
            throw IllegalStateException("ComfyUI 工作流校验失败：${root.get("error") ?: root.get("node_errors")}")
        }
        return root.get("prompt_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("ComfyUI 未返回 prompt_id")
    }

    /** null 表示任务仍在排队或执行，失败及无图片的已完成任务立即报错。 */
    fun parseHistory(body: String, promptId: String, baseUrl: HttpUrl): String? {
        val history = JsonParser.parseString(body).asJsonObject.getAsJsonObject(promptId) ?: return null
        val status = history.getAsJsonObject("status")
        val messages = status?.getAsJsonArray("messages")
        val failure = messages?.firstOrNull {
            it.isJsonArray && it.asJsonArray.size() >= 2 &&
                it.asJsonArray[0].asString in setOf("execution_error", "execution_interrupted")
        }?.asJsonArray
        if (status?.get("status_str")?.asString == "error" || failure != null) {
            val detail = failure?.get(1)?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("exception_message")?.asString ?: "执行失败或已中断"
            throw IllegalStateException("ComfyUI：$detail")
        }
        if (status?.get("completed")?.asBoolean == false) return null
        val images = history.getAsJsonObject("outputs")?.entrySet()?.flatMap { (_, output) ->
            output.asJsonObject.getAsJsonArray("images")?.toList().orEmpty()
        }.orEmpty().map { it.asJsonObject }
        // 优先选择 SaveImage 保存的最终输出，而不是预览节点的临时图片。
        val image = images.firstOrNull { it.get("type")?.asString == "output" }
            ?: images.firstOrNull()
            ?: throw IllegalStateException("ComfyUI 任务已完成但没有图片，请在工作流中添加 SaveImage 节点")
        val filename = image.get("filename")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ComfyUI 图片缺少文件名")
        return baseUrl.newBuilder().addPathSegment("view")
            .addQueryParameter("filename", filename)
            .addQueryParameter("subfolder", image.get("subfolder")?.asString.orEmpty())
            .addQueryParameter("type", image.get("type")?.asString ?: "output")
            .build().toString()
    }

    suspend fun generateImageUrl(
        client: OkHttpClient,
        source: AiImageSource,
        prompt: String,
        size: String,
        headers: Map<String, String>,
        timeoutMillis: Long = 600_000,
        pollIntervalMillis: Long = 1_000
    ): String {
        val base = baseUrl(source.baseUrl)
        val request = buildRequest(source, prompt, size)
        return withTimeoutOrNull(timeoutMillis) {
            val submitted = requestJson(client, base.newBuilder().addPathSegment("prompt").build(), headers, request)
            val promptId = parsePromptId(submitted)
            val historyUrl = base.newBuilder().addPathSegment("history").addPathSegment(promptId).build()
            var imageUrl: String? = null
            while (imageUrl == null) {
                imageUrl = parseHistory(requestJson(client, historyUrl, headers), promptId, base)
                if (imageUrl == null) delay(pollIntervalMillis)
            }
            imageUrl
        } ?: throw IllegalStateException("ComfyUI 生成超时（10 分钟），请检查服务端队列和运行状态")
    }

    private suspend fun requestJson(
        client: OkHttpClient,
        endpoint: HttpUrl,
        headers: Map<String, String>,
        body: JsonObject? = null
    ): String = client.newCallResponse {
        url(endpoint)
        addHeaders(headers)
        if (body != null) post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
    }.use { response ->
        val text = response.body.string()
        check(response.isSuccessful) { "ComfyUI HTTP ${response.code}：${text.take(2000)}" }
        text
    }

    private val PLACEHOLDER = Regex("\\{\\{(?:prompt|width|height|seed)}}")
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
