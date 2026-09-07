package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.AiPersona
import io.legado.app.databinding.DialogAiPersonaEditBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * AI 人格编辑
 */
class AiPersonaEditDialog() : BaseDialogFragment(R.layout.dialog_ai_persona_edit, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogAiPersonaEditBinding::bind)
    private val viewModel by viewModels<AiPersonaEditViewModel>()
    private var aiPersona = AiPersona()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.inflateMenu(R.menu.ai_persona_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        val isEdit = arguments?.getLong("id") != null
        viewModel.initData(arguments) {
            aiPersona = it
            initView()
        }
        if (!isEdit) {
            initView()
        }
    }

    private fun initView() {
        binding.tvName.setText(aiPersona.name)
        binding.tvPrompt.setText(aiPersona.prompt)
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
            toastOnUi(R.string.ai_persona_name)
            return
        }
        if (prompt.isEmpty()) {
            toastOnUi(R.string.ai_persona_prompt)
            return
        }
        aiPersona = aiPersona.copy(
            name = name,
            prompt = prompt,
            lastUpdateTime = System.currentTimeMillis()
        )
        viewModel.save(aiPersona) {
            toastOnUi(R.string.action_save)
            dismissAllowingStateLoss()
        }
    }

}
