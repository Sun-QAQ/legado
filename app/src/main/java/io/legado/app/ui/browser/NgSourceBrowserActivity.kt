package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.IntentData
import io.legado.app.model.webBook.NgJsRuntime
import io.legado.app.utils.GSON
import io.legado.app.utils.startActivity
import io.legado.app.utils.gone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.util.UUID

/** 仅供已导入 NG 书源打开的 HTML 使用，禁止导航后继续持有脚本桥。 */
class NgSourceBrowserActivity : BaseActivity<ActivityWebViewBinding>() {
    override val binding by lazy { ActivityWebViewBinding.inflate(layoutInflater) }
    private lateinit var payload: Payload

    @SuppressLint("SetJavaScriptEnabled")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val data = IntentData.get<Payload>(intent.getStringExtra("payload"))
        if (data == null) { finish(); return }
        payload = data
        binding.titleBar.title = data.source.bookSourceName
        binding.progressBar.gone()
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "ngBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            }
            val bootstrap = """
                (function(){
                    var pending = {}, seq = 0;
                    window.__ngResolve = function(id, value, error) {
                        var p = pending[id]; if (!p) return; delete pending[id];
                        if (error) p.reject(new Error(error)); else p.resolve(value);
                    };
                    function request(value, image) {
                        return new Promise(function(resolve,reject) {
                            var id = String(++seq); pending[id] = {resolve:resolve,reject:reject};
                            if (image) ngBridge.image(id,value); else ngBridge.run(id,value);
                        });
                    }
                    window.run = function(code){return request(String(code), false);};
                    window.imageToPngDataUrlAwait = function(url){return request(String(url), true);};
                })();
            """.trimIndent()
            val prefix = """
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; frame-src 'none'; base-uri 'none'; form-action 'none'">
                <script>$bootstrap</script><script>${data.script.replace("</script", "<\\/script", true)}</script>
            """.trimIndent()
            val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(data.html)
            val html = if (head == null) prefix + data.html else
                data.html.substring(0, head.range.last + 1) + prefix + data.html.substring(head.range.last + 1)
            loadDataWithBaseURL(data.url, html, "text/html", "UTF-8", null)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun run(id: String, code: String) = respond(id) {
            NgJsRuntime.evaluate(payload.source, "JSON.stringify(eval(${GSON.toJson(code)}))")
        }

        @JavascriptInterface
        fun image(id: String, url: String) = respond(id) {
            require(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("data:image/"))
            val target = Glide.with(appCtx).asBitmap().load(url).submit(1024, 1024)
            try {
                val bytes = ByteArrayOutputStream().use {
                    target.get().compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
                GSON.toJson("data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            } finally {
                Glide.with(appCtx).clear(target)
            }
        }
    }

    private fun respond(id: String, block: suspend () -> String) {
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            ensureActive()
            val value = result.getOrNull() ?: "null"
            val error = GSON.toJson(result.exceptionOrNull()?.localizedMessage)
            binding.webView.evaluateJavascript("window.__ngResolve(${GSON.toJson(id)},$value,$error)", null)
        }
    }

    override fun onDestroy() {
        binding.webView.removeJavascriptInterface("ngBridge")
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    private data class Payload(val source: BookSource, val url: String, val html: String, val script: String)

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun open(source: BookSource, url: String, html: String, script: String, options: String) {
            val key = IntentData.put(UUID.randomUUID().toString(), Payload(source, url, html, script))
            appCtx.startActivity<NgSourceBrowserActivity> { putExtra("payload", key) }
        }
    }
}
