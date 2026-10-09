package io.legado.app.model.webBook

import androidx.annotation.Keep
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensions
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.browser.NgSourceBrowserActivity

@Keep
class NgJsBridge(
    private val source: BookSource,
    private val bypassRateLimit: Boolean = false
) : JsExtensions {
    override fun getSource() = source

    override fun ajax(url: Any): String? = ajaxRequest(url, null)

    /** NG 的第二个参数是毫秒，限制整个请求以及单次读取的等待时间。 */
    fun ajax(url: Any, timeout: Long): String? {
        require(timeout > 0) { "NG JS ajax 超时时间必须大于 0 毫秒" }
        return ajaxRequest(url, timeout)
    }

    private fun ajaxRequest(url: Any, timeout: Long?): String? {
        val urlStr = if (url is List<*>) url.firstOrNull().toString() else url.toString()
        val requestSource = if (bypassRateLimit) source.copy(concurrentRate = null) else source
        return kotlin.runCatching {
            require(urlStr.isNotBlank()) { "NG JS 请求地址不能为空" }
            val body = AnalyzeUrl(urlStr, source = requestSource,
                readTimeout = timeout, callTimeout = timeout).getStrResponse().body
            qmFirstChapterFallback(urlStr, body, requestSource, timeout)
        }.getOrElse { it.stackTraceToString() }
    }

    /**
     * 七猫旧版 chapter/content 对部分已下架书籍返回 17010104，但新版 book/detail
     * 仍会返回首章明文。保留原请求结果，只在首章命中时转换成旧接口需要的 data.content。
     */
    private fun qmFirstChapterFallback(
        url: String,
        body: String?,
        requestSource: BookSource,
        timeout: Long?
    ): String? {
        if (body.isNullOrBlank() || !body.contains("17010104") ||
            !url.startsWith("https://api-ks.wtzw.com/api/v1/chapter/content")) {
            return body
        }
        val chapterId = Regex("(?:^|&)chapterId=([^&]*)")
            .find(url.substringBefore(','))?.groupValues?.getOrNull(1) ?: return body
        val comma = AnalyzeUrl.paramPattern.matcher(url)
        if (!comma.find()) return body
        val request = url.substring(0, comma.start())
        val options = url.substring(comma.end())
        val fallbackUrl = request.replace(
            "https://api-ks.wtzw.com/api/v1/chapter/content",
            "https://api-bc.wtzw.com/api/v4/book/detail"
        )
        val fallback = AnalyzeUrl(
            "$fallbackUrl,$options",
            source = requestSource,
            readTimeout = timeout,
            callTimeout = timeout
        ).getStrResponse().body ?: return body
        val book = runCatching {
            JsonParser.parseString(fallback).asJsonObject
                .getAsJsonObject("data")?.getAsJsonObject("book")
        }.getOrNull() ?: return body
        if (book.get("first_chapter_id")?.asString != chapterId) return body
        val content = book.get("first_chapter_content")?.asString?.takeIf { it.isNotBlank() }
            ?: return body
        return JsonObject().apply {
            add("data", JsonObject().apply { addProperty("content", content) })
        }.toString()
    }

    fun showBrowser(url: String, html: String, javaScript: String, options: String) {
        NgSourceBrowserActivity.open(source, url, html, javaScript, options)
    }
}
