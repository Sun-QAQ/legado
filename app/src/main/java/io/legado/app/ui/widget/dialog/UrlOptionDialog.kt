package io.legado.app.ui.widget.dialog

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.databinding.DialogUrlOptionEditBinding
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.applyDialogRoundedBackground
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout

class UrlOptionDialog(context: Context, private val success: (String) -> Unit) :
    AppCompatDialog(context) {

    val binding = DialogUrlOptionEditBinding.inflate(layoutInflater)

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.let {
            it.setLayout(
                minOf(it.attributes.width, 640.dpToPx()),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            it.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        }
        window?.setBackgroundDrawableResource(R.color.transparent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.vwBg.applyDialogRoundedBackground()
        binding.root.setOnClickListener { dismiss() }
        binding.vwBg.setOnClickListener(null)
        binding.editMethod.setFilterValues("POST", "GET")
        binding.editCharset.setFilterValues(AppConst.charsets)
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener {
            success.invoke(GSON.toJson(getUrlOption()))
            dismiss()
        }
    }

    private fun getUrlOption(): AnalyzeUrl.UrlOption {
        val urlOption = AnalyzeUrl.UrlOption()
        urlOption.useWebView(binding.cbUseWebView.isChecked)
        urlOption.setMethod(binding.editMethod.text.toString())
        urlOption.setCharset(binding.editCharset.text.toString())
        urlOption.setHeaders(binding.editHeaders.text.toString())
        urlOption.setBody(binding.editBody.text.toString())
        urlOption.setRetry(binding.editRetry.text.toString())
        urlOption.setType(binding.editType.text.toString())
        urlOption.setWebJs(binding.editWebJs.text.toString())
        urlOption.setJs(binding.editJs.text.toString())
        return urlOption
    }

}
