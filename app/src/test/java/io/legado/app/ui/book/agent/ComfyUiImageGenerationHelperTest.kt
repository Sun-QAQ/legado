package io.legado.app.ui.book.agent

import com.google.gson.JsonParser
import io.legado.app.data.entities.AiImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiImageGenerationHelperTest {
    private val source = AiImageSource(
        baseUrl = "http://comfy.test/proxy/prompt",
        apiType = AiImageSource.TYPE_COMFYUI,
        customBody = """{
            "6":{"class_type":"CLIPTextEncode","inputs":{"text":"风景，{{prompt}}"}},
            "7":{"class_type":"CLIPTextEncode","inputs":{"text":"bad quality"}},
            "5":{"class_type":"EmptyLatentImage","inputs":{"width":"{{width}}","height":"{{height}}","batch_size":1}},
            "3":{"class_type":"KSampler","inputs":{"seed":"{{seed}}","positive":["6",0]}}
        }"""
    )

    @Test
    fun `安全替换提示词且尺寸和种子使用数字并保留负面提示词及节点连接`() {
        val prompt = "猫\"\n{{width}}\\雪"
        val request = ComfyUiImageGenerationHelper.buildRequest(source, prompt, "768x512", 123)
        val graph = JsonParser.parseString(request.toString()).asJsonObject.getAsJsonObject("prompt")
        assertEquals("风景，$prompt", graph["6"].asJsonObject["inputs"].asJsonObject["text"].asString)
        assertEquals("bad quality", graph["7"].asJsonObject["inputs"].asJsonObject["text"].asString)
        val dimensions = graph["5"].asJsonObject["inputs"].asJsonObject
        assertTrue(dimensions["width"].asJsonPrimitive.isNumber)
        assertEquals(768, dimensions["width"].asInt)
        assertEquals(512, dimensions["height"].asInt)
        val sampler = graph["3"].asJsonObject["inputs"].asJsonObject
        assertEquals(123L, sampler["seed"].asLong)
        assertEquals("6", sampler["positive"].asJsonArray[0].asString)
        assertTrue(source.customBody!!.contains("{{prompt}}"))
    }

    @Test
    fun `未加引号的数字占位符可以校验并展开为数值`() {
        val unquoted = source.customBody!!
            .replace("\"{{width}}\"", "{{width}}")
            .replace("\"{{height}}\"", "{{height}}")
            .replace("\"{{seed}}\"", "{{seed}}")
        listOf(unquoted, """{"prompt":$unquoted,"extra_data":{}}""").forEach { workflow ->
            ComfyUiImageGenerationHelper.validateWorkflow(workflow)
            val request = ComfyUiImageGenerationHelper.buildRequest(
                source.copy(customBody = workflow), "猫", "768x512", 123
            )
            val graph = request.getAsJsonObject("prompt")
            val dimensions = graph["5"].asJsonObject.getAsJsonObject("inputs")
            val seed = graph["3"].asJsonObject.getAsJsonObject("inputs")["seed"]
            assertEquals(768, dimensions["width"].asInt)
            assertEquals(512, dimensions["height"].asInt)
            assertEquals(123L, seed.asLong)
            assertTrue(seed.asJsonPrimitive.isNumber)
        }
    }

    @Test
    fun `兼容未加引号的数字占位符时不改写字符串和转义字符`() {
        val text = "引号\"和反斜杠\\，{{seed}}，{{width}}，{{height}}，{{prompt}}"
        val workflow = """{"1":{"class_type":"TestNode","inputs":{
            "text":${com.google.gson.JsonPrimitive(text)},"values":[{{seed}}, {"width": {{width}}}],
            "quoted":"{{height}}","fixed":42,"unknown":"{{unknown}}"
        }}}"""
        val validated = ComfyUiImageGenerationHelper.validateWorkflow(workflow)
        assertEquals(text, validated["1"].asJsonObject.getAsJsonObject("inputs")["text"].asString)
        val request = ComfyUiImageGenerationHelper.buildRequest(source.copy(customBody = workflow), "猫", "768x512", 123)
        val inputs = request.getAsJsonObject("prompt")["1"].asJsonObject.getAsJsonObject("inputs")
        assertEquals("引号\"和反斜杠\\，123，768，512，猫", inputs["text"].asString)
        assertEquals(123L, inputs["values"].asJsonArray[0].asLong)
        assertEquals(768, inputs["values"].asJsonArray[1].asJsonObject["width"].asInt)
        assertEquals(512, inputs["quoted"].asInt)
        assertEquals(42, inputs["fixed"].asInt)
        assertEquals("{{unknown}}", inputs["unknown"].asString)
    }

    @Test
    fun `数字占位符兼容不会放行其他JSON语法错误`() {
        listOf("{{unknown}}", "{{prompt}}", "{{seed}}0", "1{{seed}}", "{{ seed }}").forEach { value ->
            val workflow = """{"1":{"class_type":"TestNode","inputs":{"text":"{{prompt}}","seed":$value}}}"""
            assertThrows(Exception::class.java) { ComfyUiImageGenerationHelper.validateWorkflow(workflow) }
        }
    }

    @Test
    fun `支持完整请求体保留扩展参数并清除旧任务ID`() {
        val template = source.copy(customBody = """{"prompt":${source.customBody},"extra_data":{"test":true},"prompt_id":"old"}""")
        val request = ComfyUiImageGenerationHelper.buildRequest(template, "猫", "", 1)
        assertTrue(request.getAsJsonObject("extra_data")["test"].asBoolean)
        assertFalse(request.has("prompt_id"))
        assertEquals(1024, request["prompt"].asJsonObject["5"].asJsonObject["inputs"].asJsonObject["width"].asInt)
    }

    @Test
    fun `拒绝普通画布JSON空工作流及缺少提示词占位符的工作流`() {
        listOf("", "[]", "{}", """{"nodes":[],"links":[]}""",
            """{"6":{"class_type":"CLIPTextEncode","inputs":{"text":"固定提示词"}}}""")
            .forEach { value ->
                assertThrows(Exception::class.java) { ComfyUiImageGenerationHelper.validateWorkflow(value) }
            }
    }

    @Test
    fun `正向提示词可完全使用占位符`() {
        val template = source.copy(customBody = source.customBody!!.replace("风景，{{prompt}}", "{{prompt}}"))
        val request = ComfyUiImageGenerationHelper.buildRequest(template, "一只猫", "1024x1024")
        assertEquals("一只猫", request["prompt"].asJsonObject["6"].asJsonObject["inputs"].asJsonObject["text"].asString)
    }

    @Test
    fun `服务地址兼容根地址prompt地址及代理路径`() {
        listOf("http://comfy.test/proxy", "http://comfy.test/proxy/", "http://comfy.test/proxy/prompt/")
            .forEach { assertEquals("http://comfy.test/proxy/", ComfyUiImageGenerationHelper.baseUrl(it).toString()) }
        assertEquals("http://comfy.test:8188/", ComfyUiImageGenerationHelper.baseUrl("http://comfy.test:8188/prompt").toString())
    }

    @Test
    fun `提交响应需要任务ID且拒绝节点错误`() {
        assertEquals("job-1", ComfyUiImageGenerationHelper.parsePromptId("""{"prompt_id":"job-1","node_errors":{}}"""))
        listOf("{}", """{"error":{"message":"bad workflow"}}""", """{"prompt_id":"j","node_errors":{"6":{"errors":[]}}}""")
            .forEach { assertThrows(IllegalStateException::class.java) { ComfyUiImageGenerationHelper.parsePromptId(it) } }
    }

    @Test
    fun `历史未出现或任务尚未完成时继续等待`() {
        val base = ComfyUiImageGenerationHelper.baseUrl(source.baseUrl)
        assertNull(ComfyUiImageGenerationHelper.parseHistory("{}", "j", base))
        assertNull(ComfyUiImageGenerationHelper.parseHistory("""{"j":{"status":{"completed":false},"outputs":{}}}""", "j", base))
    }

    @Test
    fun `优先最终图片且正确编码图片路径`() {
        val history = """{"j":{"status":{"completed":true,"status_str":"success"},"outputs":{
            "preview":{"images":[{"filename":"preview.png","type":"temp"}]},
            "save":{"images":[{"filename":"猫 & 1.png","subfolder":"a/b c","type":"output"}]}
        }}}"""
        val url = ComfyUiImageGenerationHelper.parseHistory(history, "j", ComfyUiImageGenerationHelper.baseUrl(source.baseUrl))!!.toHttpUrl()
        assertEquals("/proxy/view", url.encodedPath)
        assertEquals("猫 & 1.png", url.queryParameter("filename"))
        assertEquals("a/b c", url.queryParameter("subfolder"))
        assertEquals("output", url.queryParameter("type"))
    }

    @Test
    fun `执行失败或无图片时返回明确错误`() {
        val base = ComfyUiImageGenerationHelper.baseUrl(source.baseUrl)
        val failure = """{"j":{"status":{"status_str":"error","completed":false,"messages":[["execution_error",{"exception_message":"模型不存在"}]]}}}"""
        val error = assertThrows(IllegalStateException::class.java) { ComfyUiImageGenerationHelper.parseHistory(failure, "j", base) }
        assertTrue(error.message!!.contains("模型不存在"))
        assertThrows(IllegalStateException::class.java) {
            ComfyUiImageGenerationHelper.parseHistory("""{"j":{"status":{"completed":true},"outputs":{}}}""", "j", base)
        }
    }

    @Test
    fun `完整生成请求携带认证并下载最终图片`() = runBlocking {
        val requests = mutableListOf<Request>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val client = client { request ->
            requests.add(request)
            when (request.url.encodedPath) {
                "/proxy/prompt" -> {
                    assertEquals("POST", request.method)
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue(body.contains("风景，猫"))
                    json(request, """{"prompt_id":"j"}""")
                }
                "/proxy/history/j" -> json(request, completedHistory)
                "/proxy/view" -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(bytes.toResponseBody("image/png".toMediaType())).build()
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val result = AiImageGenerationHelper.generate(source.copy(apiKey = "test-key"), "猫", "512x512", client)
        assertArrayEquals(bytes, result)
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.header("Authorization") == "Bearer test-key" })
    }

    @Test
    fun `排队后轮询到完成并保留自定义认证头`() = runBlocking {
        var polls = 0
        val client = client { request ->
            assertEquals("Custom token", request.header("authorization"))
            if (request.method == "POST") json(request, """{"prompt_id":"j"}""")
            else json(request, if (++polls == 1) "{}" else completedHistory)
        }
        val url = ComfyUiImageGenerationHelper.generateImageUrl(client, source, "猫", "512x512",
            mapOf("authorization" to "Custom token"), pollIntervalMillis = 1)
        assertEquals(2, polls)
        assertTrue(url.contains("/proxy/view?"))
    }

    @Test
    fun `等待超时不重复提交任务`() = runBlocking {
        var submits = 0
        val client = client { request ->
            if (request.method == "POST") {
                submits++
                json(request, """{"prompt_id":"j"}""")
            } else json(request, "{}")
        }
        try {
            ComfyUiImageGenerationHelper.generateImageUrl(client, source, "猫", "512x512", emptyMap(),
                timeoutMillis = 100, pollIntervalMillis = 1)
            error("Expected timeout")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("超时"))
        }
        assertEquals(1, submits)
    }

    @Test
    fun `取消等待传播取消异常`() = runBlocking {
        val polled = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client = client { request ->
            if (request.method == "POST") json(request, """{"prompt_id":"j"}""")
            else { polled.complete(Unit); json(request, "{}") }
        }
        val job = launch {
            try {
                ComfyUiImageGenerationHelper.generateImageUrl(client, source, "猫", "512x512", emptyMap())
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
        polled.await()
        job.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `提交HTTP错误时包含服务端原因`() = runBlocking {
        val client = client { request ->
            json(request, "checkpoint missing").newBuilder().code(400).message("Bad Request").build()
        }
        try {
            ComfyUiImageGenerationHelper.generateImageUrl(client, source, "猫", "512x512", emptyMap())
            error("Expected HTTP error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("400"))
            assertTrue(e.message!!.contains("checkpoint missing"))
        }
    }

    @Test
    fun `自定义认证头优先于APIKey且OpenAI下载不向图片站点发送认证`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests.add(request)
            if (request.method == "POST") {
                assertEquals("/v1/images/generations", request.url.encodedPath)
                json(request, """{"data":[{"url":"http://images.test/cat.png"}]}""")
            } else json(request, "small image bytes")
        }
        val result = AiImageGenerationHelper.generate(AiImageSource(
            baseUrl = "http://openai.test/v1", model = "image-model", apiKey = "ignored",
            headers = """{"authorization":"Custom token"}"""
        ), "猫", "512x512", client)
        assertEquals("small image bytes", String(result))
        assertEquals(listOf("Custom token"), requests.first().headers.values("Authorization"))
        assertNull(requests.last().header("Authorization"))
    }

    private fun client(respond: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { respond(it.request()) }.build()

    private fun json(request: Request, text: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(text.toResponseBody("application/json".toMediaType())).build()

    private val completedHistory = """{"j":{"status":{"completed":true,"status_str":"success"},
        "outputs":{"9":{"images":[{"filename":"cat.png","subfolder":"","type":"output"}]}}}}"""
}
