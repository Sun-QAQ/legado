package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.AiSource
import io.legado.app.databinding.DialogAiSourceEditBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * AI 供应商编辑
 */
class AiSourceEditDialog() : BaseDialogFragment(R.layout.dialog_ai_source_edit, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogAiSourceEditBinding::bind)
    private val viewModel by viewModels<AiSourceEditViewModel>()
    private var aiSource = AiSource()
    private val presets by lazy {
        listOf(
            AiSourcePreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            AiSourcePreset("OpenCodeGO", "https://opencode.ai/zen/go/v1", "glm-5.2"),
            AiSourcePreset("", "", "", getString(R.string.ai_source_custom))
        )
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.inflateMenu(R.menu.ai_source_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        viewModel.initData(arguments) {
            aiSource = it
            initView()
        }
        if (arguments?.getLong("id") == null) {
            initView()
        }
    }

    private fun initView() {
        initPresetSelector()
        binding.tvName.setText(aiSource.name)
        binding.tvBaseUrl.setText(aiSource.baseUrl)
        binding.tvApiKey.setText(aiSource.apiKey)
        binding.tvModel.setText(aiSource.model)
        binding.tvHeaders.setText(aiSource.headers)
        if (aiSource.name.isBlank() && aiSource.baseUrl.isBlank()) {
            applyPreset(0)
        } else {
            binding.tvSupplier.setText(presets[detectPreset(aiSource)].displayName)
        }
    }

    private fun initPresetSelector() {
        binding.tvSupplier.isFocusable = false
        binding.tvSupplier.isClickable = true
        binding.tvSupplier.setOnClickListener {
            context?.selector(
                getString(R.string.ai_source_supplier),
                presets.map { it.displayName }
            ) { _, index ->
                applyPreset(index)
            }
        }
    }

    private fun applyPreset(index: Int) {
        val preset = presets[index]
        binding.tvSupplier.setText(preset.displayName)
        binding.tvName.setText(preset.name)
        binding.tvBaseUrl.setText(preset.baseUrl)
        binding.tvModel.setText(preset.model)
    }

    private fun detectPreset(source: AiSource): Int {
        val index = presets.indexOfFirst { preset ->
            preset.baseUrl.isNotBlank() &&
                (source.baseUrl.trimEnd('/').startsWith(preset.baseUrl.trimEnd('/')) ||
                    source.name.equals(preset.name, ignoreCase = true))
        }
        return if (index >= 0) index else presets.lastIndex
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> save()
            R.id.menu_fetch_models -> fetchModels()
        }
        return true
    }

    private fun save() {
        val name = binding.tvName.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.tvBaseUrl.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toastOnUi(R.string.ai_source_name)
            return
        }
        if (baseUrl.isEmpty()) {
            toastOnUi(R.string.input_base_url)
            return
        }
        val headersText = binding.tvHeaders.text?.toString()?.trim()
        val headers = if (headersText.isNullOrBlank()) null else headersText
        aiSource = aiSource.copy(
            name = name,
            baseUrl = baseUrl,
            apiKey = binding.tvApiKey.text?.toString()?.trim().orEmpty(),
            model = binding.tvModel.text?.toString()?.trim().orEmpty(),
            headers = headers,
            lastUpdateTime = System.currentTimeMillis()
        )
        viewModel.save(aiSource) {
            toastOnUi(R.string.action_save)
            dismissAllowingStateLoss()
        }
    }

    private fun fetchModels() {
        val source = aiSource.copy(
            baseUrl = binding.tvBaseUrl.text?.toString()?.trim().orEmpty(),
            apiKey = binding.tvApiKey.text?.toString()?.trim().orEmpty(),
            headers = binding.tvHeaders.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        )
        if (source.baseUrl.isEmpty()) {
            toastOnUi(R.string.input_base_url)
            return
        }
        viewModel.fetchModels(
            source,
            success = { models ->
                aiSource = aiSource.copy(
                    baseUrl = source.baseUrl,
                    apiKey = source.apiKey,
                    headers = source.headers,
                    model = aiSource.model.ifBlank { models.firstOrNull().orEmpty() },
                    models = GSON.toJson(models),
                    lastUpdateTime = System.currentTimeMillis()
                )
                toastOnUi(getString(R.string.fetch_models_success, models.size))
                if (models.isNotEmpty()) {
                    context?.selector(models) { _, _, index ->
                        binding.tvModel.setText(models[index])
                    }
                }
            },
            error = { throwable ->
                toastOnUi(getString(R.string.fetch_models_fail, throwable.localizedMessage))
            }
        )
    }

    private data class AiSourcePreset(
        val name: String,
        val baseUrl: String,
        val model: String,
        val displayName: String = name
    )

}
