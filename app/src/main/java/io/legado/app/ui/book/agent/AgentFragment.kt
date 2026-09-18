package io.legado.app.ui.book.agent

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentAgentBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Agent 对话
 */
class AgentFragment() : BaseFragment(R.layout.fragment_agent), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentAgentBinding::bind)
    private val viewModel by viewModels<AgentViewModel>()
    private val adapter by lazy { AgentAdapter(requireContext(), object : AgentAdapter.CallBack {
        override fun openBook(book: SearchBook) {
            openBookInfo(book)
        }
        override fun onLoadMore() {
            viewModel.continueSearch()
        }
        override fun importBookSource(source: SourceRepositoryItem) {
            showDialogFragment(ImportBookSourceDialog(source.jsonUrl))
        }
        override fun openGeneratedImage(path: String) {
            showDialogFragment(PhotoDialog(path))
        }
    }) }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        viewModel.startConversationHistory()
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(requireContext().accentColor)
        binding.llInput.applyAgentInputInsets()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.btnSend.setOnClickListener {
            sendInput()
        }
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
                viewModel.messages.collect {
                    adapter.setItems(it)
                    if (it.isNotEmpty()) {
                        binding.recyclerView.post {
                            binding.recyclerView.scrollToPosition(it.size - 1)
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.streamingText.collect {
                    it?.let { text -> adapter.updateLastText(text) }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.waiting.collect { waiting ->
                    if (waiting) {
                        binding.btnSend.setImageResource(R.drawable.ic_stop_black_24dp)
                        binding.btnSend.contentDescription = getString(R.string.agent_interrupt)
                    } else {
                        binding.btnSend.setImageResource(R.drawable.ic_send)
                        binding.btnSend.contentDescription = getString(R.string.agent_send)
                    }
                }
            }
        }
        observeSuppliers()
        observePersonas()
    }

    private fun observeSuppliers() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.aiSourceDao.observeAll().catch {
                AppLog.put("获取AI供应商列表失败", it)
            }.flowOn(Dispatchers.IO).collect { list ->
                val enabled = list.filter { it.enabled }
                if (enabled.isNotEmpty()) {
                    val currentId = viewModel.currentSupplierId.value
                    if (currentId == 0L || enabled.none { it.id == currentId }) {
                        viewModel.selectSupplier(enabled.first().id, enabled.first().name)
                    }
                }
            }
        }
    }

    private fun observePersonas() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.aiPersonaDao.observeAll().catch {
                AppLog.put("获取AI人格列表失败", it)
            }.flowOn(Dispatchers.IO).collect(viewModel::restorePersona)
        }
    }

    private fun sendInput() {
        if (viewModel.waiting.value) {
            viewModel.cancel()
            return
        }
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.etInput.setText("")
        viewModel.send(text)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.ai_agent, menu)
        menu.applyTint(requireContext())
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_new_chat -> viewModel.newConversation()
            R.id.menu_chat_history -> showDialogFragment<AgentConversationHistoryDialog>()
            R.id.menu_select_supplier -> selectSupplier()
            R.id.menu_manage_supplier -> startActivity<AiSourceManageActivity>()
            R.id.menu_manage_image_source -> startActivity<AiImageSourceManageActivity>()
            R.id.menu_select_persona -> selectPersona()
            R.id.menu_manage_persona -> startActivity<AiPersonaManageActivity>()
            R.id.menu_clear_chat -> {
                alert(R.string.agent_clear_chat) {
                    setMessage(R.string.agent_clear_chat_confirm)
                    noButton()
                    yesButton {
                        viewModel.clearChat()
                        toastOnUi(R.string.agent_clear_chat)
                    }
                }
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    private fun selectSupplier() {
        viewLifecycleOwner.lifecycleScope.launch {
            val suppliers = appDb.aiSourceDao.allEnabled
            if (suppliers.isEmpty()) {
                toastOnUi(R.string.ai_not_configured)
                return@launch
            }
            val names = suppliers.map {
                if (it.id == viewModel.currentSupplierId.value) {
                    "[${getString(R.string.ai_source_current)}]${it.name}"
                } else {
                    it.name
                }
            }
            context?.selector(getString(R.string.agent_select_supplier), names) { _, index ->
                val source = suppliers[index]
                viewModel.selectSupplier(source.id, source.name)
            }
        }
    }

    private fun selectPersona() {
        viewLifecycleOwner.lifecycleScope.launch {
            val personas = appDb.aiPersonaDao.all
            val defaultName = getString(R.string.agent_persona_default)
            val currentId = viewModel.currentPersonaId.value
            val names = buildList {
                add(if (currentId == 0L) "[${getString(R.string.ai_source_current)}]$defaultName" else defaultName)
                personas.forEach {
                    add(if (currentId == it.id) "[${getString(R.string.ai_source_current)}]${it.name}" else it.name)
                }
            }
            context?.selector(getString(R.string.agent_select_persona), names) { _, index ->
                if (index == 0) {
                    viewModel.selectDefaultPersona()
                    toastOnUi(getString(R.string.agent_persona_switched, defaultName))
                } else {
                    val persona = personas[index - 1]
                    viewModel.selectPersona(persona.id, persona.name, persona.prompt)
                    toastOnUi(getString(R.string.agent_persona_switched, persona.name))
                }
            }
        }
    }

    private fun openBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

}

internal fun View.applyAgentInputInsets() {
    val bottomBarOffset = 88.dpToPx()
    val keyboardGap = 10.dpToPx()
    setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = resolveAgentInputBottomMargin(
                imeHeight = windowInsets.imeHeight,
                navigationBarHeight = windowInsets.navigationBarHeight,
                bottomBarOffset = bottomBarOffset,
                keyboardGap = keyboardGap
            )
        }
        windowInsets
    }
}

internal fun resolveAgentInputBottomMargin(
    imeHeight: Int,
    navigationBarHeight: Int,
    bottomBarOffset: Int,
    keyboardGap: Int
): Int {
    return if (imeHeight > 0) {
        imeHeight + keyboardGap
    } else {
        navigationBarHeight + bottomBarOffset
    }
}
