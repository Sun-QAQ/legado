package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAgentMessageEditBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.applyDialogRoundedBackground
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AgentMessageEditDialog() : BaseDialogFragment(R.layout.dialog_agent_message_edit) {
    constructor(id: String) : this() {
        arguments = Bundle().apply { putString("messageId", id) }
    }

    private val binding by viewBinding(DialogAgentMessageEditBinding::bind)
    private val viewModel by viewModels<AgentViewModel>({ requireParentFragment() })

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setLayout(0.92f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.applyDialogRoundedBackground()
        val id = requireArguments().getString("messageId") ?: return dismiss()
        val message = viewModel.messages.value.firstOrNull { it.id == id }
        if (message == null) {
            toastOnUi(R.string.agent_message_missing)
            dismiss()
            return
        }
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.inflateMenu(R.menu.save)
        binding.toolBar.menu.applyTint(requireContext())
        binding.etMessage.setText(savedInstanceState?.getString("draft") ?: message.text)
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_save) {
                val text = binding.etMessage.text.toString()
                val current = viewModel.messages.value.firstOrNull { message -> message.id == id }
                if (current == null) {
                    toastOnUi(R.string.agent_message_missing)
                    dismiss()
                } else if (text.isBlank() && !AgentConversationEditor.canBeEmpty(current)) {
                    binding.etMessage.error = getString(R.string.agent_message_empty)
                } else if (viewModel.editMessage(id, text)) {
                    dismiss()
                }
            }
            true
        }
        binding.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", binding.etMessage.text.toString())
        super.onSaveInstanceState(outState)
    }
}
