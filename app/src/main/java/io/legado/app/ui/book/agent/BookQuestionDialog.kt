package io.legado.app.ui.book.agent

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.DialogBookQuestionBinding
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** 从阅读菜单打开的独立问书会话，关闭后回到原阅读位置。 */
class BookQuestionDialog : BaseDialogFragment(R.layout.dialog_book_question) {
    private val binding by viewBinding(DialogBookQuestionBinding::bind)
    private val viewModel by viewModels<AgentViewModel>()
    private var autoScroll: AgentAutoScroll? = null
    private var keyboardVisible = false
    private val adapter by lazy {
        AgentAdapter(requireContext(), object : AgentAdapter.CallBack {
            override fun openBook(book: SearchBook) {
                startActivity<BookInfoActivity> {
                    putExtra("name", book.name)
                    putExtra("author", book.author)
                    putExtra("bookUrl", book.bookUrl)
                }
            }
            override fun onLoadMore() = viewModel.continueSearch()
            override fun importBookSource(source: SourceRepositoryItem) {
                showDialogFragment(ImportBookSourceDialog(source.jsonUrl))
            }
            override fun openGeneratedImage(path: String) {
                showDialogFragment(PhotoDialog(path))
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_Legado_BookQuestion)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val light = ColorUtils.isColorLight(ThemePalette(requireContext()).background)
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    @OptIn(FlowPreview::class)
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val context = BookQuestionContext(
            args.getString("name").orEmpty(), args.getString("author").orEmpty(),
            args.getString("bookUrl").orEmpty(), args.getString("chapter").orEmpty(),
            args.getString("excerpt").orEmpty()
        )
        viewModel.bookQuestionContext = context.prompt()
        binding.tvBook.text = getString(R.string.book_question_book, context.name)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnModel.setOnClickListener { startActivity<AiSourceManageActivity>() }
        val palette = ThemePalette(requireContext())
        binding.inputBar.background = rounded(palette.surface, 24)
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(palette.accent)
        listOf(
            R.string.book_question_suggestion_summary,
            R.string.book_question_suggestion_people,
            R.string.book_question_suggestion_details
        ).forEach { res ->
            binding.suggestions.addView(questionButton(getString(res), outlined = true).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dpToPx() }
                setOnClickListener { send(getString(res)) }
            })
        }
        listOf(
            R.string.book_question_highlights to R.string.book_question_highlights_prompt,
            R.string.book_question_background to R.string.book_question_background_prompt,
            R.string.book_question_concepts to R.string.book_question_concepts_prompt
        ).forEach { (label, prompt) ->
            binding.quickActions.addView(questionButton(getString(label), outlined = false).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = 4.dpToPx()
                    marginEnd = 4.dpToPx()
                }
                gravity = Gravity.CENTER
                setOnClickListener { send(getString(prompt)) }
            })
        }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            itemAnimator = null
            adapter = this@BookQuestionDialog.adapter
        }
        autoScroll = AgentAutoScroll(binding.recyclerView)
        binding.btnSend.setOnClickListener {
            if (viewModel.waiting.value) viewModel.cancel() else sendInput()
        }
        binding.etInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else false
        }
        binding.etInput.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) sendInput()
                true
            } else false
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateWelcome()
            insets
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appDb.aiSourceDao.observeAll()
                        .catch { AppLog.put("获取问书 AI 模型失败", it) }
                        .flowOn(Dispatchers.IO)
                        .collect { sources -> viewModel.restoreSupplier(sources.filter { it.enabled }) }
                }
                launch {
                    viewModel.messages.collect {
                        adapter.setItems(it)
                        updateWelcome()
                        autoScroll?.requestScroll()
                    }
                }
                launch {
                    viewModel.streamingText.sample(50).collect { text ->
                        text?.let { adapter.updateLastText(it) }
                    }
                }
                launch {
                    viewModel.waiting.collect { waiting ->
                        binding.btnSend.setImageResource(if (waiting) R.drawable.ic_stop_black_24dp else R.drawable.ic_arrow_right)
                        binding.btnSend.rotation = if (waiting) 0f else -90f
                        binding.btnSend.contentDescription = getString(if (waiting) R.string.agent_interrupt else R.string.agent_send)
                        for (i in 0 until binding.quickActions.childCount) {
                            binding.quickActions.getChildAt(i).isEnabled = !waiting
                        }
                    }
                }
            }
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.dpToPx().toFloat()
        stroke?.let { setStroke(1.dpToPx(), it) }
    }

    private fun questionButton(label: String, outlined: Boolean) = TextView(requireContext()).apply {
        val palette = ThemePalette(context)
        text = label
        textSize = if (outlined) 16f else 14f
        setTextColor(palette.text)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = 48.dpToPx()
        setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
        background = RippleDrawable(
            ColorStateList.valueOf(palette.accentContainerPressed),
            rounded(if (outlined) palette.background else palette.surface, 18, if (outlined) palette.divider else null),
            rounded(Color.WHITE, 18)
        )
        isFocusable = true
        isClickable = true
    }

    private fun updateWelcome() {
        val empty = viewModel.messages.value.isEmpty()
        binding.welcome.isVisible = empty && !keyboardVisible
        binding.recyclerView.isVisible = !empty
        binding.quickActions.isVisible = empty && !keyboardVisible
    }

    private fun sendInput() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty() || viewModel.waiting.value) return
        if (send(text)) binding.etInput.text?.clear()
    }

    private fun send(text: String): Boolean {
        if (!viewModel.canModifyMessages()) return false
        autoScroll?.followLatest()
        viewModel.send(text)
        return true
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) viewModel.cancel()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        autoScroll?.dispose()
        autoScroll = null
        binding.recyclerView.adapter = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(name: String, author: String, bookUrl: String, chapter: String, excerpt: String) =
            BookQuestionDialog().apply {
                arguments = Bundle().apply {
                    putString("name", name)
                    putString("author", author)
                    putString("bookUrl", bookUrl)
                    putString("chapter", chapter)
                    putString("excerpt", excerpt.takeLast(BookQuestionContext.MAX_EXCERPT_LENGTH))
                }
            }
    }
}
