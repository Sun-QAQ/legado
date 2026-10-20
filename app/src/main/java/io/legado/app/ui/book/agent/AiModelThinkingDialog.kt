package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.AiModelThinking
import io.legado.app.databinding.DialogAiModelThinkingBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.applyDialogRoundedBackground
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiModelThinkingDialog() : BaseDialogFragment(R.layout.dialog_ai_model_thinking) {
    constructor(sourceId: Long, model: String) : this() {
        arguments = Bundle().apply {
            putLong("id", sourceId)
            putString("model", model)
        }
    }

    private val binding by viewBinding(DialogAiModelThinkingBinding::bind)
    private val viewModel by viewModels<AiSourceEditViewModel>()
    private val modes = listOf("default", "enabled", "disabled")
    private val efforts = listOf("", "minimal", "low", "medium", "high", "xhigh", "max")
    private val formats = listOf("auto", "openai", "thinking", "qwen")
    private var modeIndex = 0
    private var effortIndex = 0
    private var formatIndex = 0
    private var ready = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.applyDialogRoundedBackground()
        val model = arguments?.getString("model").orEmpty()
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.toolBar.subtitle = model
        binding.toolBar.inflateMenu(R.menu.save)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.menu.findItem(R.id.menu_save).isEnabled = false
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_save && ready) {
                val contextWindow = binding.etContextWindow.text.toString().toIntOrNull()
                if (contextWindow == null || contextWindow !in 8192..2000000) {
                    binding.etContextWindow.error = getString(R.string.ai_context_window_invalid)
                    return@setOnMenuItemClickListener true
                }
                binding.toolBar.menu.findItem(R.id.menu_save).isEnabled = false
                viewModel.saveModelThinking(
                    requireArguments().getLong("id"), model,
                    AiModelThinking(modes[modeIndex], efforts[effortIndex], formats[formatIndex], contextWindow),
                    success = {
                        toastOnUi(R.string.action_save)
                        dismissAllowingStateLoss()
                    },
                    error = { error ->
                        binding.toolBar.menu.findItem(R.id.menu_save).isEnabled = true
                        toastOnUi(error.localizedMessage ?: getString(R.string.ai_thinking_save_failed))
                    }
                )
            }
            true
        }
        binding.tvMode.setOnClickListener {
            selector(R.string.ai_thinking_mode, R.array.ai_thinking_modes) { index ->
                modeIndex = index
                updateFields()
            }
        }
        binding.tvEffort.setOnClickListener {
            selector(R.string.ai_thinking_effort, R.array.ai_thinking_efforts) { index ->
                effortIndex = index
                updateFields()
            }
        }
        binding.tvFormat.setOnClickListener {
            selector(R.string.ai_thinking_format, R.array.ai_thinking_formats) { index ->
                formatIndex = index
                updateFields()
            }
        }
        viewModel.initData(arguments) { source ->
            val config = source.getModelThinking(model)
            modeIndex = modes.indexOf(config.mode).coerceAtLeast(0)
            effortIndex = efforts.indexOf(config.effort).coerceAtLeast(0)
            formatIndex = formats.indexOf(config.format).coerceAtLeast(0)
            binding.etContextWindow.setText(savedInstanceState?.getString("contextWindow") ?: config.contextWindow().toString())
            savedInstanceState?.let {
                modeIndex = it.getInt("modeIndex", modeIndex).coerceIn(modes.indices)
                effortIndex = it.getInt("effortIndex", effortIndex).coerceIn(efforts.indices)
                formatIndex = it.getInt("formatIndex", formatIndex).coerceIn(formats.indices)
            }
            ready = true
            binding.toolBar.menu.findItem(R.id.menu_save).isEnabled = true
            updateFields()
        }
        updateFields()
    }

    private fun selector(title: Int, options: Int, onSelected: (Int) -> Unit) {
        if (!ready) return
        requireContext().selector(getString(title), resources.getStringArray(options).toList()) { _, index ->
            onSelected(index)
        }
    }

    private fun updateFields() {
        binding.tvMode.setText(resources.getStringArray(R.array.ai_thinking_modes)[modeIndex])
        binding.tvEffort.setText(resources.getStringArray(R.array.ai_thinking_efforts)[effortIndex])
        binding.tvFormat.setText(resources.getStringArray(R.array.ai_thinking_formats)[formatIndex])
        binding.tvEffort.isEnabled = ready && modes[modeIndex] == "enabled"
        binding.tvHint.setText(if (formats[formatIndex] == "qwen")
            R.string.ai_thinking_qwen_hint else R.string.ai_thinking_hint)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("modeIndex", modeIndex)
        outState.putInt("effortIndex", effortIndex)
        outState.putInt("formatIndex", formatIndex)
        outState.putString("contextWindow", binding.etContextWindow.text.toString())
    }
}
