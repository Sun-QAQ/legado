package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.AiSearchSource
import io.legado.app.databinding.DialogAiSearchSourceEditBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiSearchSourceEditDialog() : BaseDialogFragment(
    R.layout.dialog_ai_search_source_edit,
    true
), Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply { putLong("id", id) }
    }

    private val binding by viewBinding(DialogAiSearchSourceEditBinding::bind)
    private val viewModel by viewModels<AiSearchSourceEditViewModel>()
    private var source = AiSearchSource()
    private val types = listOf(
        AiSearchSource.TYPE_TAVILY,
        AiSearchSource.TYPE_BRAVE,
        AiSearchSource.TYPE_SEARXNG,
        AiSearchSource.TYPE_CUSTOM
    )

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.inflateMenu(R.menu.ai_source_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.tvType.setOnClickListener {
            context?.selector(
                getString(R.string.ai_search_type),
                types.map(::typeLabel)
            ) { _, index -> selectType(types[index]) }
        }
        binding.tvMethod.setOnClickListener {
            val methods = listOf(AiSearchSource.METHOD_GET, AiSearchSource.METHOD_POST)
            context?.selector(getString(R.string.ai_search_method), methods) { _, index ->
                binding.tvMethod.setText(methods[index])
            }
        }
        binding.swtSafeSearch.setOnCheckedChangeListener { _, _ -> }
        viewModel.initData(arguments) {
            source = it
            bindSource()
        }
        bindSource()
    }

    private fun bindSource() = binding.run {
        tvName.setText(source.name)
        tvType.setText(typeLabel(source.type))
        tvBaseUrl.setText(source.baseUrl.ifBlank { AiSearchSource.defaultBaseUrl(source.type) })
        tvApiKey.setText(source.apiKey)
        tvMethod.setText(source.method)
        tvQueryParameter.setText(source.queryParameter)
        tvResultPath.setText(source.resultPath)
        tvTitlePath.setText(source.titlePath)
        tvUrlPath.setText(source.urlPath)
        tvSnippetPath.setText(source.snippetPath)
        tvPublishedAtPath.setText(source.publishedAtPath)
        tvDefaultCount.setText(source.defaultCount.toString())
        tvLanguage.setText(source.language)
        tvCountry.setText(source.country)
        swtSafeSearch.isChecked = source.safeSearch
        tvCustomBody.setText(source.customBody)
        tvHeaders.setText(source.headers)
        updateCustomFields(source.type)
    }

    private fun selectType(type: String) {
        val oldType = selectedType()
        binding.tvType.setText(typeLabel(type))
        val currentUrl = binding.tvBaseUrl.text?.toString()?.trim().orEmpty()
        if (currentUrl.isBlank() || currentUrl == AiSearchSource.defaultBaseUrl(oldType)) {
            binding.tvBaseUrl.setText(AiSearchSource.defaultBaseUrl(type))
        }
        val preset = presetFor(type)
        binding.tvMethod.setText(preset.method)
        binding.tvQueryParameter.setText(preset.queryParameter)
        binding.tvResultPath.setText(preset.resultPath)
        binding.tvTitlePath.setText(preset.titlePath)
        binding.tvUrlPath.setText(preset.urlPath)
        binding.tvSnippetPath.setText(preset.snippetPath)
        binding.tvPublishedAtPath.setText(preset.publishedAtPath)
        if (binding.tvName.text.isNullOrBlank()) binding.tvName.setText(typeLabel(type))
        updateCustomFields(type)
    }

    private fun updateCustomFields(type: String) {
        binding.customMappingGroup.isVisible = type == AiSearchSource.TYPE_CUSTOM
    }

    private fun selectedType(): String {
        val label = binding.tvType.text?.toString().orEmpty()
        return types.firstOrNull { typeLabel(it) == label } ?: AiSearchSource.TYPE_TAVILY
    }

    private fun typeLabel(type: String): String = when (type) {
        AiSearchSource.TYPE_TAVILY -> "Tavily"
        AiSearchSource.TYPE_BRAVE -> "Brave"
        AiSearchSource.TYPE_SEARXNG -> "SearXNG"
        else -> getString(R.string.ai_search_type_custom)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        if (item?.itemId == R.id.menu_save) save()
        return true
    }

    private fun save() {
        val name = binding.tvName.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.tvBaseUrl.text?.toString()?.trim().orEmpty()
        val type = selectedType()
        if (name.isBlank()) return toastOnUi(R.string.ai_source_name)
        if (baseUrl.isBlank()) return toastOnUi(R.string.input_base_url)
        val count = binding.tvDefaultCount.text?.toString()?.toIntOrNull()
        if (count == null || count !in 1..20) {
            return toastOnUi(R.string.ai_search_count_invalid)
        }
        val customBody = binding.tvCustomBody.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (customBody != null && !isJsonObject(customBody)) {
            return toastOnUi(R.string.ai_search_json_invalid)
        }
        val headers = binding.tvHeaders.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (headers != null && !isJsonObject(headers)) {
            return toastOnUi(R.string.ai_search_headers_invalid)
        }
        val preset = presetFor(type)
        source = source.copy(
            name = name,
            type = type,
            baseUrl = baseUrl,
            apiKey = binding.tvApiKey.text?.toString()?.trim().orEmpty(),
            method = binding.tvMethod.text?.toString()?.trim()?.uppercase()
                ?.takeIf { it == AiSearchSource.METHOD_GET || it == AiSearchSource.METHOD_POST }
                ?: preset.method,
            queryParameter = textOrPreset(binding.tvQueryParameter.text?.toString(), preset.queryParameter),
            resultPath = textOrPreset(binding.tvResultPath.text?.toString(), preset.resultPath),
            titlePath = textOrPreset(binding.tvTitlePath.text?.toString(), preset.titlePath),
            urlPath = textOrPreset(binding.tvUrlPath.text?.toString(), preset.urlPath),
            snippetPath = textOrPreset(binding.tvSnippetPath.text?.toString(), preset.snippetPath),
            publishedAtPath = binding.tvPublishedAtPath.text?.toString()?.trim().orEmpty(),
            headers = headers,
            customBody = customBody,
            defaultCount = count,
            language = binding.tvLanguage.text?.toString()?.trim().orEmpty(),
            country = binding.tvCountry.text?.toString()?.trim().orEmpty(),
            safeSearch = binding.swtSafeSearch.isChecked,
            lastUpdateTime = System.currentTimeMillis()
        )
        viewModel.save(source) {
            toastOnUi(R.string.action_save)
            dismissAllowingStateLoss()
        }
    }

    private fun presetFor(type: String): AiSearchSource = when (type) {
        AiSearchSource.TYPE_BRAVE -> AiSearchSource(
            type = type,
            method = AiSearchSource.METHOD_GET,
            queryParameter = "q",
            resultPath = "web.results",
            snippetPath = "description",
            publishedAtPath = "page_age"
        )
        AiSearchSource.TYPE_SEARXNG -> AiSearchSource(
            type = type,
            method = AiSearchSource.METHOD_GET,
            queryParameter = "q",
            snippetPath = "content",
            publishedAtPath = "publishedDate"
        )
        AiSearchSource.TYPE_CUSTOM -> AiSearchSource(type = type)
        else -> AiSearchSource(type = type)
    }

    private fun textOrPreset(value: String?, preset: String): String =
        value?.trim()?.takeIf { it.isNotBlank() } ?: preset

    companion object {
        private fun isJsonObject(value: String): Boolean = runCatching {
            JsonParser.parseString(value).isJsonObject
        }.getOrDefault(false)
    }
}
