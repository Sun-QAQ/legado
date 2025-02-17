package io.legado.app.ui.main.aichat

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.databinding.FragmentAichatBinding
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AIchatFragment private constructor() :
    VMBaseFragment<AIChatViewModel>(R.layout.fragment_aichat),
    MainFragmentInterface {

    private val binding by viewBinding(FragmentAichatBinding::bind)
    private var progressDialog: AlertDialog? = null
    override val position: Int? get() = arguments?.getInt("position")
    override val viewModel: AIChatViewModel by viewModels()

    // 主构造函数
    init {
        // 这里可以添加需要主构造函数初始化的逻辑
    }

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {

        binding.titleBar.apply {
            setTitle(R.string.aichat)
        }

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean {
                    view?.loadUrl(request.url.toString())
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    context?.toastOnUi("加载失败: $description")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        showProgressDialog(newProgress)
                    } else {
                        dismissProgressDialog()
                    }
                }
            }

            loadUrl("https://chat.deepseek.com/")
        }
    }


    override fun onDestroyView() {
        binding.webView.destroy()
        super.onDestroyView()
    }

    private fun showProgressDialog(progress: Int) {
        if (progressDialog == null) {
            progressDialog = AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_web_progress)
                .setCancelable(false)
                .show()
        }
        progressDialog?.findViewById<ProgressBar>(R.id.progressBar)?.progress = progress
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

}
