package io.legado.app.ui.rss.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.fragment.app.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssReadBinding
import io.legado.app.databinding.DialogHttpAuthBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.Download
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.isTrue
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setDarkeningAllowed
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import splitties.views.bottomPadding
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.regex.PatternSyntaxException

/**
 * rss阅读界面
 */
class ReadRssFragment : VMBaseFragment<ReadRssViewModel>(R.layout.activity_rss_read),
    RssFavoritesDialog.Callback {

    private val binding by viewBinding(ActivityRssReadBinding::bind)
    override val viewModel by viewModels<ReadRssViewModel>()

    private var starMenuItem: MenuItem? = null
    private var ttsMenuItem: MenuItem? = null
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var httpAuthDialog: AlertDialog? = null
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private val rssJsExtensions by lazy { RssJsExtensions(this) }

    fun getSource(): RssSource? {
        return viewModel.rssSource
    }

    val sourceKey: String? get() = arguments?.getString("tabSource")
    val pageTitle: String get() = if (view != null) binding.titleBar.title?.toString().orEmpty()
        else arguments?.getString("title").orEmpty()
    val pageUrl: String get() = if (view != null) binding.webView.url.orEmpty()
        else arguments?.getString("origin").orEmpty()
    private val pageIntent get() = Intent().putExtras(requireArguments())
    private var webState: Bundle? = null
    private var pageInitialized = false
    private var restoredScroll: Pair<Int, Int>? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(requireContext().backgroundColor)
        viewModel.upStarMenuData.observe(viewLifecycleOwner) {
            upStarMenu()
            binding.titleBar.menu.findItem(R.id.menu_login)?.isVisible =
                !viewModel.rssSource?.loginUrl.isNullOrBlank()
            if (pageInitialized) restorePage()
        }
        viewModel.upTtsMenuData.observe(viewLifecycleOwner) { upTtsMenu(it) }
        binding.titleBar.title = arguments?.getString("title")
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.setNavigationOnClickListener { back() }
        initView()
        initWebView()
        webState = savedInstanceState?.getBundle("webState")
        restoredScroll = savedInstanceState?.let { it.getInt("scrollX") to it.getInt("scrollY") }
        pageInitialized = false
        if (sourceKey == null) {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { back() }
        }
    }

    fun back() {
        if (binding.customWebView.size > 0) {
            customWebViewCallback?.onCustomViewHidden()
            return
        } else if (binding.webView.canGoBack()
            && binding.webView.copyBackForwardList().size > 1
        ) {
            binding.webView.goBack()
            return
        }
        minimize()
    }

    private fun minimize() {
        val host = activity as? TabHost
        if (sourceKey != null && host != null) host.minimizeRssTab() else activity?.finish()
    }

    interface TabHost {
        fun minimizeRssTab()
        fun closeRssTab(sourceKey: String)
        fun showRssTabs()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isHidden || sourceKey != null) return
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.rss_read, menu)
        starMenuItem = menu.findItem(R.id.menu_rss_star)
        ttsMenuItem = menu.findItem(R.id.menu_aloud)
        val isTab = sourceKey != null
        menu.findItem(R.id.menu_rss_minimize).isVisible = isTab
        menu.findItem(R.id.menu_rss_tabs).isVisible = isTab
        menu.findItem(R.id.menu_rss_close_tab).isVisible = isTab
        upStarMenu()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_rss_minimize -> minimize()
            R.id.menu_rss_tabs -> (activity as? TabHost)?.showRssTabs()
            R.id.menu_rss_close_tab -> sourceKey?.let { (activity as? TabHost)?.closeRssTab(it) }
            R.id.menu_rss_refresh -> viewModel.refresh {
                binding.webView.reload()
            }

            R.id.menu_rss_star -> {
                viewModel.addFavorite()
                viewModel.rssArticle?.let {
                    showDialogFragment(RssFavoritesDialog(it))
                }
            }

            R.id.menu_share_it -> {
                binding.webView.url?.let {
                    requireContext().share(it)
                } ?: viewModel.rssArticle?.let {
                    requireContext().share(it.link)
                } ?: requireContext().toastOnUi(R.string.null_url)
            }

            R.id.menu_aloud -> readAloud()
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", viewModel.rssSource?.sourceUrl)
            }

            R.id.menu_browser_open -> binding.webView.url?.let {
                requireContext().openUrl(it)
            } ?: requireContext().toastOnUi("url null")
        }
        super.onCompatOptionsItemSelected(item)
    }

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.rssArticle?.let {
            if (title != null) {
                it.title = title
            }
            if (group != null) {
                it.group = group
            }
        }
        viewModel.updateFavorite()
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    @JavascriptInterface
    fun isNightTheme(): Boolean {
        return AppConfig.isNightTheme
    }

    private fun initView() {
        binding.titleBar.toolbar.updateLayoutParams {
            height = 48.dpToPx()
        }
        binding.root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(typeMask)
            view.bottomPadding = insets.bottom
            windowInsets
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun initWebView() {
        binding.progressBar.fontColor = accentColor
        binding.webView.webChromeClient = CustomWebChromeClient()
        binding.webView.webViewClient = CustomWebViewClient()
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
        binding.webView.settings.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            setDarkeningAllowed(AppConfig.isNightTheme)
        }
        binding.webView.addJavascriptInterface(this, "thisActivity")
        binding.webView.setOnLongClickListener {
            val hitTestResult = binding.webView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                hitTestResult.extra?.let { webPic ->
                    requireContext().selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder(null)
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        binding.webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            binding.llView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(requireContext(), url, fileName)
            }
        }

    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            viewModel.saveImage(webPic, Uri.parse(path))
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initLiveData() {
        viewModel.contentLiveData.observe(viewLifecycleOwner) { content ->
            content ?: return@observe
            viewModel.rssArticle?.let {
                upJavaScriptEnable()
                val url = NetworkUtils.getAbsoluteURL(it.origin, it.link)
                val html = viewModel.clHtml(content)
                binding.webView.settings.userAgentString =
                    viewModel.headerMap[AppConst.UA_NAME] ?: AppConfig.userAgent
                if (viewModel.rssSource?.loadWithBaseUrl == true) {
                    binding.webView
                        .loadDataWithBaseURL(url, html, "text/html", "utf-8", url)//不想用baseUrl进else
                } else {
                    binding.webView
                        .loadDataWithBaseURL(null, html, "text/html;charset=utf-8", "utf-8", url)
                }
            }
        }
        viewModel.urlLiveData.observe(viewLifecycleOwner) {
            it ?: return@observe
            upJavaScriptEnable()
            CookieManager.applyToWebView(it.url)
            binding.webView.settings.userAgentString = it.getUserAgent()
            binding.webView.loadUrl(it.url, it.headerMap)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun upJavaScriptEnable() {
        if (viewModel.rssSource?.enableJs == true) {
            binding.webView.settings.javaScriptEnabled = true
        }
    }

    private fun upStarMenu() {
        starMenuItem?.isVisible = viewModel.rssArticle != null
        if (viewModel.rssStar != null) {
            starMenuItem?.setIcon(R.drawable.ic_star)
            starMenuItem?.setTitle(R.string.in_favorites)
        } else {
            starMenuItem?.setIcon(R.drawable.ic_star_border)
            starMenuItem?.setTitle(R.string.out_favorites)
        }
        starMenuItem?.icon?.setTintMutate(primaryTextColor)
    }

    private fun upTtsMenu(isPlaying: Boolean) {
        lifecycleScope.launch {
            if (isPlaying) {
                ttsMenuItem?.setIcon(R.drawable.ic_stop_black_24dp)
                ttsMenuItem?.setTitle(R.string.aloud_stop)
            } else {
                ttsMenuItem?.setIcon(R.drawable.ic_volume_up)
                ttsMenuItem?.setTitle(R.string.read_aloud)
            }
            ttsMenuItem?.icon?.setTintMutate(primaryTextColor)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun readAloud() {
        if (viewModel.tts?.isSpeaking == true) {
            viewModel.tts?.stop()
            upTtsMenu(false)
        } else {
            binding.webView.settings.javaScriptEnabled = true
            binding.webView.evaluateJavascript("document.documentElement.outerHTML") {
                val html = StringEscapeUtils.unescapeJson(it)
                    .replace("^\"|\"$".toRegex(), "")
                viewModel.readAloud(
                    Jsoup.parse(html)
                        .textArray()
                        .joinToString("\n")
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pageInitialized) {
            pageInitialized = true
            // Retained ViewModels may contain the previous load event after a view recreation.
            viewModel.contentLiveData.value = null
            viewModel.urlLiveData.value = null
            initLiveData()
            viewModel.initData(pageIntent, loadPage = webState == null)
        }
        binding.webView.onResume()
    }

    private fun restorePage() {
        val state = webState ?: return
        webState = null
        upJavaScriptEnable()
        binding.webView.settings.userAgentString =
            viewModel.headerMap[AppConst.UA_NAME] ?: binding.webView.settings.userAgentString
        if (binding.webView.restoreState(state) == null) viewModel.initData(pageIntent)
    }

    override fun onPause() {
        httpAuthDialog?.dismiss()
        binding.webView.onPause()
        // onPause alone does not pause HTML media. Pause only this page, never global timers.
        binding.webView.evaluateJavascript(
            "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null
        )
        viewModel.tts?.stop()
        if (binding.customWebView.size > 0) CustomWebChromeClient().onHideCustomView()
        binding.root.findFocus()?.hideSoftInput()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null && pageInitialized) {
            outState.putBundle("webState", Bundle().also { binding.webView.saveState(it) })
            outState.putInt("scrollX", binding.webView.scrollX)
            outState.putInt("scrollY", binding.webView.scrollY)
        } else {
            webState?.let { outState.putBundle("webState", it) }
            restoredScroll?.let {
                outState.putInt("scrollX", it.first)
                outState.putInt("scrollY", it.second)
            }
        }
    }

    override fun onDestroyView() {
        httpAuthDialog?.dismiss()
        binding.webView.removeJavascriptInterface("thisActivity")
        binding.webView.stopLoading()
        (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        starMenuItem = null
        ttsMenuItem = null
        pageInitialized = false
        super.onDestroyView()
    }

    inner class CustomWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            requireActivity().keepScreenOn(true)
            requireActivity().toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            requireActivity().keepScreenOn(false)
            requireActivity().toggleSystemBar(true)
            customWebViewCallback = null
        }
    }

    inner class CustomWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            httpAuthDialog?.dismiss()
            super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String?
        ) {
            if (!isResumed || isHidden || activity?.isFinishing != false) {
                handler.cancel()
                return
            }
            httpAuthDialog?.dismiss()
            val authBinding = DialogHttpAuthBinding.inflate(layoutInflater)
            var submitted = false
            httpAuthDialog = requireContext().alert(R.string.login) {
                setMessage(
                    getString(R.string.http_auth_message, host) +
                        if (realm.isNullOrBlank()) "" else "\n$realm"
                )
                setCustomView(authBinding.root)
                positiveButton(R.string.login) {
                    submitted = true
                    handler.proceed(
                        authBinding.httpAuthUsername.text.toString(),
                        authBinding.httpAuthPassword.text.toString()
                    )
                }
                negativeButton(R.string.cancel)
                onDismiss { dialog ->
                    // 包括取消、返回、页面跳转及 Activity 销毁，保证认证请求结束。
                    if (!submitted) handler.cancel()
                    authBinding.httpAuthUsername.text?.clear()
                    authBinding.httpAuthPassword.text?.clear()
                    if (httpAuthDialog === dialog) httpAuthDialog = null
                }
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            return shouldOverrideUrlLoading(request.url)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return shouldOverrideUrlLoading(Uri.parse(url))
        }

        /**
         * 如果有黑名单,黑名单匹配返回空白,
         * 没有黑名单再判断白名单,在白名单中的才通过,
         * 都没有不做处理
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            val source = viewModel.rssSource ?: return super.shouldInterceptRequest(view, request)
            val blacklist = source.contentBlacklist?.splitNotBlank(",")
            if (!blacklist.isNullOrEmpty()) {
                blacklist.forEach {
                    try {
                        if (url.startsWith(it) || url.matches(it.toRegex())) {
                            return createEmptyResource()
                        }
                    } catch (e: PatternSyntaxException) {
                        AppLog.put("黑名单规则正则语法错误 源名称:${source.sourceName} 正则:$it", e)
                    }
                }
            } else {
                val whitelist = source.contentWhitelist?.splitNotBlank(",")
                if (!whitelist.isNullOrEmpty()) {
                    whitelist.forEach {
                        try {
                            if (url.startsWith(it) || url.matches(it.toRegex())) {
                                return super.shouldInterceptRequest(view, request)
                            }
                        } catch (e: PatternSyntaxException) {
                            val msg = "白名单规则正则语法错误 源名称:${source.sourceName} 正则:$it"
                            AppLog.put(msg, e)
                        }
                    }
                    return createEmptyResource()
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            restoredScroll?.let { (x, y) ->
                view.post { if (isAdded && this@ReadRssFragment.view != null) view.scrollTo(x, y) }
                restoredScroll = null
            }
            android.webkit.CookieManager.getInstance().flush()
            view.title?.let { title ->
                if (title != url
                    && title != view.url
                    && title.isNotBlank()
                    && url != "about:blank"
                    && !url.contains(title)
                ) {
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = arguments?.getString("title")
                }
            }
            viewModel.rssSource?.injectJs?.let {
                if (it.isNotBlank()) {
                    view.evaluateJavascript(it, null)
                }
            }
        }

        private fun createEmptyResource(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream("".toByteArray())
            )
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            val source = viewModel.rssSource
            val js = source?.shouldOverrideUrlLoading
            if (!js.isNullOrBlank()) {
                val t = SystemClock.uptimeMillis()
                val result = kotlin.runCatching {
                    runScriptWithContext(lifecycleScope.coroutineContext) {
                        source.evalJS(js) {
                            put("java", rssJsExtensions)
                            put("url", url.toString())
                        }.toString()
                    }
                }.onFailure {
                    AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
                }.getOrNull()
                if (SystemClock.uptimeMillis() - t > 30) {
                    AppLog.put("${source.getTag()}: url跳转拦截js执行耗时过长")
                }
                if (result.isTrue()) {
                    return true
                }
            }
            when (url.scheme) {
                "http", "https", "jsbridge" -> {
                    return false
                }

                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    return true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        requireContext().openUrl(url)
                    }
                    return true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

    }

}
