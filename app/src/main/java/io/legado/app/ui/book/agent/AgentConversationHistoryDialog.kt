package io.legado.app.ui.book.agent

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiConversation
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyDialogRoundedBackground
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class AgentConversationHistoryDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    AgentConversationAdapter.CallBack {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<AgentViewModel>({ requireParentFragment() })
    private val adapter by lazy { AgentConversationAdapter(requireContext(), this) }
    private var renameDialog: AlertDialog? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.applyDialogRoundedBackground()
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.setTitle(R.string.agent_chat_history)
        binding.toolBar.menu.applyTint(requireContext())
        binding.tvMsg.gravity = Gravity.CENTER
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.tvFooterLeft.apply {
            setText(R.string.delete_all)
            isVisible = true
            setOnClickListener { confirmDeleteAll() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appDb.aiConversationDao.observeAll()
                        .catch {
                            AppLog.put("获取AI历史对话失败", it)
                            binding.tvMsg.setText(R.string.agent_chat_history_load_failed)
                            binding.tvMsg.isVisible = true
                        }
                        .flowOn(Dispatchers.IO)
                        .collect { conversations ->
                            adapter.setItems(conversations)
                            binding.tvMsg.isVisible = conversations.isEmpty()
                            binding.tvMsg.setText(R.string.agent_chat_history_empty)
                            binding.tvFooterLeft.isVisible = conversations.isNotEmpty()
                        }
                }
                launch {
                    viewModel.currentConversationId.collect {
                        adapter.currentConversationId = it
                    }
                }
            }
        }
    }

    override fun open(conversation: AiConversation) {
        viewModel.openConversation(conversation.id)
        dismiss()
    }

    override fun delete(conversation: AiConversation) {
        alert(R.string.delete) {
            setMessage(getString(R.string.agent_delete_chat_confirm, conversation.title))
            noButton()
            yesButton { viewModel.deleteConversation(conversation.id) }
        }
    }

    override fun rename(conversation: AiConversation) {
        renameDialog?.dismiss()
        val input = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.agent_chat_name)
            editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editView.setSingleLine(true)
            editView.filters = arrayOf(InputFilter.LengthFilter(AgentConversationCodec.MAX_CUSTOM_TITLE_LENGTH))
            editView.setText(conversation.title)
            editView.setSelection(editView.text.length)
        }
        val dialog = alert(R.string.agent_rename_chat) {
            customView { input.root }
            cancelButton()
            positiveButton(R.string.action_save)
        }
        renameDialog = dialog
        dialog.setOnDismissListener { if (renameDialog === dialog) renameDialog = null }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = AgentConversationCodec.customTitle(input.editView.text.toString())
            if (name == null) {
                input.editView.error = getString(R.string.agent_chat_name_invalid)
                return@setOnClickListener
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
            viewModel.renameConversation(conversation.id, name) { success ->
                if (!dialog.isShowing) return@renameConversation
                if (success) dialog.dismiss() else {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
                    input.editView.error = getString(R.string.agent_chat_rename_failed)
                }
            }
        }
    }

    override fun onDestroyView() {
        renameDialog?.dismiss()
        renameDialog = null
        binding.recyclerView.adapter = null
        super.onDestroyView()
    }

    private fun confirmDeleteAll() {
        alert(R.string.delete_all) {
            setMessage(R.string.agent_delete_all_chats_confirm)
            noButton()
            yesButton { viewModel.deleteAllConversations() }
        }
    }
}
