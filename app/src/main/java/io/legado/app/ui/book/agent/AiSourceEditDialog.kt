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
    }

    private fun initView() {
        binding.tvName.setText(aiSource.name)
        binding.tvBaseUrl.setText(aiSource.baseUrl)
        binding.tvApiKey.setText(aiSource.apiKey)
        binding.tvModel.setText(aiSource.model)
        binding.tvHeaders.setText(aiSource.headers)
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

}
