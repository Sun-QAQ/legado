package io.legado.app.ui.book.agent

import io.legado.app.data.entities.AiImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiImageGenerationHelperTest {

    @Test
    fun `自动响应格式不写入请求体`() {
        val source = AiImageSource(model = "image-model")
        val request = AiImageGenerationHelper.buildRequest(source, "一只猫", "1024x1024")

        assertEquals("image-model", request["model"].asString)
        assertEquals("一只猫", request["prompt"].asString)
        assertFalse(request.has("n"))
        assertFalse(request.has("response_format"))
    }

    @Test
    fun `自定义请求体合并扩展字段并可覆盖普通字段`() {
        val source = AiImageSource(
            model = "image-model",
            customBody = """{
                "size":"768x768",
                "n":2,
                "negative_prompt":"低清晰度",
                "num_inference_steps":9,
                "lora_weights":[]
            }"""
        )

        val request = AiImageGenerationHelper.buildRequest(source, "一只猫", "1024x1024")

        assertEquals("768x768", request["size"].asString)
        assertEquals(2, request["n"].asInt)
        assertEquals("低清晰度", request["negative_prompt"].asString)
        assertEquals(9, request["num_inference_steps"].asInt)
        assertTrue(request["lora_weights"].asJsonArray.isEmpty)
    }

    @Test
    fun `自定义请求体不能覆盖模型与提示词`() {
        val source = AiImageSource(
            model = "configured-model",
            customBody = """{"model":"other-model","prompt":"other prompt"}"""
        )

        val request = AiImageGenerationHelper.buildRequest(source, "current prompt", "1024x1024")

        assertEquals("configured-model", request["model"].asString)
        assertEquals("current prompt", request["prompt"].asString)
    }

    @Test
    fun `解析URL图片响应`() {
        val result = AiImageGenerationHelper.parseResponse("""{"data":[{"url":"https://img.test/a.png"}]}""")
        assertEquals("https://img.test/a.png", result.url)
        assertNull(result.base64)
    }

    @Test
    fun `解析Base64图片响应`() {
        val result = AiImageGenerationHelper.parseResponse("""{"data":[{"b64_json":"YWJj"}]}""")
        assertEquals("YWJj", result.base64)
        assertNull(result.url)
    }
}
