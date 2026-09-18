package io.legado.app.ui.book.agent

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentAgentBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 阅读页内的 AI 问答窗口。复用 Agent 对话呈现与服务商配置，但问题只取当前阅读进度之前的正文。
 */
class ReadingAssistantDialog : BaseDialogFragment(R.layout.fragment_agent, true) {

    private val binding by viewBinding(FragmentAgentBinding::bind)
    private val viewModel by viewModels<AgentViewModel>()
    private val adapter by lazy {
        AgentAdapter(requireContext(), object : AgentAdapter.CallBack {
            override fun openBook(book: SearchBook) = Unit

            override fun onLoadMore() = Unit

            override fun importBookSource(source: SourceRepositoryItem) {
                showDialogFragment(ImportBookSourceDialog(source.jsonUrl))
            }

            override fun openGeneratedImage(path: String) {
                showDialogFragment(PhotoDialog(path))
            }
        })
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.titleBar.toolbar.title = buildString {
            append(getString(R.string.agent))
            ReadBook.curTextChapter?.title?.takeIf { it.isNotBlank() }?.let {
                append(" · ").append(it)
            }
        }
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(requireContext().accentColor)
        binding.llInput.applyAgentInputInsets()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.btnSend.setOnClickListener { sendInput() }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else {
                false
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.messages.collect { messages ->
                    adapter.setItems(messages)
                    if (messages.isNotEmpty()) {
                        binding.recyclerView.post {
                            binding.recyclerView.scrollToPosition(messages.lastIndex)
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.streamingText.collect { text ->
                    text?.let(adapter::updateLastText)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.waiting.collect { waiting ->
                    binding.btnSend.setImageResource(
                        if (waiting) R.drawable.ic_stop_black_24dp else R.drawable.ic_send
                    )
                    binding.btnSend.contentDescription = getString(
                        if (waiting) R.string.agent_interrupt else R.string.agent_send
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        viewModel.cancel()
        super.onDestroyView()
    }

    private fun sendInput() {
        if (viewModel.waiting.value) {
            viewModel.cancel()
            return
        }
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        binding.etInput.setText("")
        viewModel.askAboutCurrentReading(text)
    }
}
