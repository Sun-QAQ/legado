package io.legado.app.ui.main.explore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.ceil

/** 书源生成的发现页面，只向页面提供当前书源的分类跳转。 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ExploreWebView(
    context: Context,
    val sourceUrl: String,
    val html: String,
    private val onContentReady: () -> Unit = {},
    private val openExplore: (url: String, title: String) -> Unit
) : WebView(context) {
    private var disposed = false
    private var contentReady = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "java")
        webViewClient = object : WebViewClient() {
            // 导航不能带走持有原生桥的页面，分类通过 java.open 打开。
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true

            override fun onPageFinished(view: WebView, url: String) {
                if (disposed) return
                evaluateJavascript("""
                    (function() {
                        function resize() {
                            if (document.body) java.resize(Math.ceil(document.body.scrollHeight));
                        }
                        if (window.ResizeObserver) new ResizeObserver(resize).observe(document.body);
                        else new MutationObserver(resize).observe(document.body, {childList:true,subtree:true,attributes:true});
                        window.addEventListener('resize', resize);
                        resize();
                    })();
                """.trimIndent(), null)
            }
        }
        val prefix = """
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; frame-src 'none'; base-uri 'none'; form-action 'none'">
        """.trimIndent() + if (Regex("name\\s*=\\s*['\"]?viewport", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            ""
        } else {
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        }
        val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val document = if (head == null) prefix + html else {
            html.substring(0, head.range.last + 1) + prefix + html.substring(head.range.last + 1)
        }
        loadDataWithBaseURL(sourceUrl, document, "text/html", "UTF-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun open(type: String, url: String, title: String) {
            if (type != "explore" || url.isBlank()) return
            post { if (!disposed) openExplore(url, title) }
        }

        @JavascriptInterface
        fun resize(cssHeight: Int) {
            if (cssHeight <= 0) return
            post {
                if (disposed) return@post
                val height = ceil(cssHeight.coerceAtMost(100_000) * resources.displayMetrics.density).toInt()
                val params = layoutParams ?: return@post
                if (params.height != height) {
                    params.height = height
                    layoutParams = params
                }
                if (!contentReady) {
                    contentReady = true
                    onContentReady()
                }
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        removeJavascriptInterface("java")
        stopLoading()
        (parent as? ViewGroup)?.removeView(this)
        removeAllViews()
        destroy()
    }
}
