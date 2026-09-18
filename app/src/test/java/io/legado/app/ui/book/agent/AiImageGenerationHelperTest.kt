package io.legado.app.ui.book.agent

import io.legado.app.data.entities.AiImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AiImageGenerationHelperTest {

    @Test
    fun `自动响应格式不写入请求体`() {
        val source = AiImageSource(model = "image-model")
        val request = AiImageGenerationHelper.buildRequest(source, "一只猫", "1024x1024")

        assertEquals("image-model", request["model"].asString)
        assertEquals("一只猫", request["prompt"].asString)
        assertFalse(request.has("response_format"))
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
