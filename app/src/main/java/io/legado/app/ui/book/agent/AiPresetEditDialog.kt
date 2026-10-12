package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.AiPreset
import io.legado.app.databinding.DialogAiPresetEditBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * AI 预设编辑
 */
class AiPresetEditDialog() : BaseDialogFragment(R.layout.dialog_ai_preset_edit, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogAiPresetEditBinding::bind)
    private val viewModel by viewModels<AiPresetEditViewModel>()
    private var aiPreset = AiPreset()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.let { aiPreset = aiPreset.copy(id = it.getLong("presetId")) }
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.inflateMenu(R.menu.ai_preset_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        val isEdit = arguments?.getLong("id") != null
        viewModel.initData(arguments) {
            aiPreset = it
            if (savedInstanceState == null) initView()
        }
        if (!isEdit && savedInstanceState == null) {
            initView()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("presetId", aiPreset.id)
        super.onSaveInstanceState(outState)
    }

    private fun initView() {
        binding.tvName.setText(aiPreset.name)
        binding.tvPrompt.setText(aiPreset.prompt)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> save()
        }
        return true
    }

    private fun save() {
        val name = binding.tvName.text?.toString()?.trim().orEmpty()
        val prompt = binding.tvPrompt.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toastOnUi(R.string.ai_preset_name)
            return
        }
        if (prompt.isEmpty()) {
            toastOnUi(R.string.ai_preset_prompt)
            return
        }
        aiPreset = aiPreset.copy(
            name = name,
            prompt = prompt,
            lastUpdateTime = System.currentTimeMillis()
        )
        viewModel.save(aiPreset) {
            toastOnUi(R.string.action_save)
            dismissAllowingStateLoss()
        }
    }

}
