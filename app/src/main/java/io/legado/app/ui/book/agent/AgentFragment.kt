package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyTint
import io.legado.app.utils.imeHeight
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.bottomPadding
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
    }) }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
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
        binding.llInput.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            view.bottomPadding = windowInsets.imeHeight
            windowInsets
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
                viewModel.waiting.collect {
                    binding.btnSend.isEnabled = !it
                }
            }
        }
        observeSuppliers()
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

    private fun sendInput() {
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
            R.id.menu_select_supplier -> selectSupplier()
            R.id.menu_clear_chat -> {
                viewModel.clearChat()
                toastOnUi(R.string.agent_clear_chat)
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
            val names = suppliers.map { it.name }
            context?.selector(getString(R.string.agent_select_supplier), names) { _, index ->
                val source = suppliers[index]
                viewModel.selectSupplier(source.id, source.name)
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
