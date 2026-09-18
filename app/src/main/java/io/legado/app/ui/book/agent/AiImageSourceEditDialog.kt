package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.AiImageSource
import io.legado.app.databinding.DialogAiImageSourceEditBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiImageSourceEditDialog() : BaseDialogFragment(R.layout.dialog_ai_image_source_edit, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply { putLong("id", id) }
    }

    private val binding by viewBinding(DialogAiImageSourceEditBinding::bind)
    private val viewModel by viewModels<AiImageSourceEditViewModel>()
    private var source = AiImageSource()
    private val responseFormats = listOf(
        AiImageSource.RESPONSE_FORMAT_AUTO,
        AiImageSource.RESPONSE_FORMAT_URL,
        AiImageSource.RESPONSE_FORMAT_BASE64
    )

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.inflateMenu(R.menu.ai_image_source_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.tvResponseFormat.setOnClickListener {
            context?.selector(
                getString(R.string.ai_image_response_format),
                responseFormats.map(::formatLabel)
            ) { _, index -> binding.tvResponseFormat.setText(responseFormats[index]) }
        }
        viewModel.initData(arguments) {
            source = it
            bindSource()
        }
        bindSource()
    }

    private fun bindSource() = binding.run {
        tvName.setText(source.name)
        tvBaseUrl.setText(source.baseUrl)
        tvApiKey.setText(source.apiKey)
        tvModel.setText(source.model)
        tvImageSize.setText(source.imageSize)
        tvResponseFormat.setText(source.responseFormat)
        tvCustomBody.setText(source.customBody)
        tvHeaders.setText(source.headers)
    }

    private fun formatLabel(value: String): String = when (value) {
        AiImageSource.RESPONSE_FORMAT_URL -> getString(R.string.ai_image_response_url)
        AiImageSource.RESPONSE_FORMAT_BASE64 -> getString(R.string.ai_image_response_base64)
        else -> getString(R.string.ai_image_response_auto)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        if (item?.itemId == R.id.menu_save) save()
        return true
    }

    private fun save() {
        val name = binding.tvName.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.tvBaseUrl.text?.toString()?.trim().orEmpty()
        val model = binding.tvModel.text?.toString()?.trim().orEmpty()
        val size = binding.tvImageSize.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) return toastOnUi(R.string.ai_source_name)
        if (baseUrl.isBlank()) return toastOnUi(R.string.input_base_url)
        if (model.isBlank()) return toastOnUi(R.string.ai_source_model)
        if (!SIZE_PATTERN.matches(size)) return toastOnUi(R.string.ai_image_size_invalid)
        val customBody = binding.tvCustomBody.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (customBody != null && !isJsonObject(customBody)) {
            return toastOnUi(R.string.ai_image_custom_body_invalid)
        }
        val responseFormat = binding.tvResponseFormat.text?.toString()?.trim()
            ?.takeIf { it in responseFormats } ?: AiImageSource.RESPONSE_FORMAT_AUTO
        source = source.copy(
            name = name,
            baseUrl = baseUrl,
            apiKey = binding.tvApiKey.text?.toString()?.trim().orEmpty(),
            model = model,
            imageSize = size,
            responseFormat = responseFormat,
            customBody = customBody,
            headers = binding.tvHeaders.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            lastUpdateTime = System.currentTimeMillis()
        )
        viewModel.save(source) {
            toastOnUi(R.string.action_save)
            dismissAllowingStateLoss()
        }
    }

    companion object {
        private val SIZE_PATTERN = Regex("^[1-9]\\d{1,4}x[1-9]\\d{1,4}$")

        private fun isJsonObject(value: String): Boolean = runCatching {
            JsonParser.parseString(value).isJsonObject
        }.getOrDefault(false)
    }
}
