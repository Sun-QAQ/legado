package io.legado.app.ui.book.agent

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentAgentBinding
import io.legado.app.databinding.ItemAgentAttachmentBinding
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
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

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
    private var autoScroll: AgentAutoScroll? = null
    private var pendingCameraPath: String? = null
    private val selectPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addDraftAttachment(it) }
    }
    private val selectFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.addDraftAttachment(it) }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraPath?.let(::File)
        pendingCameraPath = null
        if (success && file != null) {
            val uri = FileProvider.getUriForFile(requireContext(), AppConst.authority, file)
            viewModel.addDraftAttachment(uri, temporaryFile = file)
        } else {
            file?.delete()
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toastOnUi(R.string.agent_attachment_camera_denied)
    }
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

    @OptIn(FlowPreview::class)
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        pendingCameraPath = savedInstanceState?.getString("agentCameraPath")
        setSupportToolbar(binding.titleBar.toolbar)
        val personaTitleView = binding.titleBar.toolbar.findViewById<TextView>(R.id.tv_agent_persona)
        viewModel.startConversationHistory()
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(requireContext().accentColor)
        binding.llInput.applyAgentInputInsets()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            // 键盘改变列表高度时，以底部消息为锚点，保持最新对话可见。
            stackFromEnd = true
        }
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        // 流式回复持续改变高度，默认 change 动画会反复交叉淡入并造成跳动。
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = adapter
        autoScroll = AgentAutoScroll(binding.recyclerView)
        binding.btnSend.setOnClickListener {
            sendInput()
        }
        binding.btnUpload.setOnClickListener { showUploadOptions() }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else {
                false
            }
        }
        binding.etInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                // 按下时仅发送一次，并消费抬起和长按事件，避免换行或重复发送。
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    sendInput()
                }
                true
            } else {
                false
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.messages.collect {
                    adapter.setItems(it)
                    autoScroll?.requestScroll()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // 合并密集的 token 更新；最终全文由 messages 立即补齐，不会丢字。
                viewModel.streamingText.sample(50).collect {
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
                    updateAttachmentControls()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.draftAttachments.collect { attachments ->
                        binding.attachmentScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
                        binding.llAttachments.removeAllViews()
                        attachments.forEachIndexed { index, attachment ->
                            val item = ItemAgentAttachmentBinding.inflate(layoutInflater, binding.llAttachments, false)
                            item.tvName.text = attachment.name
                            item.btnRemove.contentDescription = getString(R.string.agent_attachment_remove) + ": " + attachment.name
                            item.btnRemove.setOnClickListener { viewModel.removeDraftAttachment(index) }
                            if (attachment.mimeType.startsWith("image/")) {
                                Glide.with(this@AgentFragment).load(attachment.bytes)
                                    .error(R.drawable.image_loading_error).into(item.ivPreview)
                            }
                            binding.llAttachments.addView(item.root)
                        }
                        updateAttachmentControls()
                    }
                }
                launch {
                    viewModel.attachmentBusy.collect { updateAttachmentControls() }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.currentPersonaName.collect { name ->
                    personaTitleView?.text = name
                }
            }
        }
        observeSuppliers()
        observePersonas()
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.restorePersona(appDb.aiPersonaDao.all)
        }
    }

    private fun observeSuppliers() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.aiSourceDao.observeAll().catch {
                AppLog.put("获取AI供应商列表失败", it)
            }.flowOn(Dispatchers.IO).collect { list ->
                val enabled = list.filter { it.enabled }
                viewModel.restoreSupplier(enabled)
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
        if (viewModel.attachmentBusy.value) return
        if (viewModel.draftAttachments.value.isNotEmpty()) {
            autoScroll?.followLatest()
            viewLifecycleOwner.lifecycleScope.launch {
                if (viewModel.sendDraftAttachments(text) && binding.etInput.text?.toString()?.trim() == text) {
                    binding.etInput.setText("")
                }
            }
            return
        }
        if (text.isEmpty()) return
        autoScroll?.followLatest()
        binding.etInput.setText("")
        viewModel.send(text)
    }

    private fun updateAttachmentControls() {
        val busy = viewModel.attachmentBusy.value
        binding.btnUpload.isEnabled = !busy && !viewModel.waiting.value
        binding.btnUpload.alpha = if (binding.btnUpload.isEnabled) 1f else 0.4f
        binding.btnSend.isEnabled = !busy || viewModel.waiting.value
        for (index in 0 until binding.llAttachments.childCount) {
            binding.llAttachments.getChildAt(index).findViewById<View>(R.id.btn_remove).isEnabled = !busy
        }
    }

    private fun showUploadOptions() {
        if (viewModel.attachmentBusy.value || viewModel.waiting.value) return
        if (viewModel.draftAttachments.value.size >= AgentAttachments.MAX_COUNT) {
            toastOnUi(R.string.agent_attachment_limit)
            return
        }
        alert(R.string.agent_upload) {
            items(listOf(
                getString(R.string.agent_upload_camera),
                getString(R.string.agent_upload_album),
                getString(R.string.agent_upload_file)
            )) { _, index ->
                try {
                    when (index) {
                        0 -> {
                            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera()
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        }
                        1 -> selectPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        2 -> selectFile.launch(arrayOf("*/*"))
                    }
                } catch (e: Exception) {
                    AppLog.put("打开 AI 附件选择器失败", e)
                    toastOnUi(R.string.agent_attachment_picker_failed)
                }
            }
        }
    }

    private fun launchCamera() {
        try {
            val file = File.createTempFile("ai-photo-", ".jpg", requireContext().cacheDir)
            pendingCameraPath = file.absolutePath
            takePhoto.launch(FileProvider.getUriForFile(requireContext(), AppConst.authority, file))
        } catch (e: Exception) {
            pendingCameraPath?.let { File(it).delete() }
            pendingCameraPath = null
            AppLog.put("打开 AI 拍照失败", e)
            toastOnUi(R.string.agent_attachment_picker_failed)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("agentCameraPath", pendingCameraPath)
    }

    override fun onDestroyView() {
        autoScroll?.dispose()
        autoScroll = null
        binding.recyclerView.adapter = null
        super.onDestroyView()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.ai_agent, menu)
        menu.applyTint(requireContext())
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        if (viewModel.attachmentBusy.value && item.itemId in listOf(
                R.id.menu_new_chat, R.id.menu_chat_history, R.id.menu_clear_chat
            )) return
        when (item.itemId) {
            R.id.menu_new_chat -> {
                autoScroll?.followLatest()
                viewModel.newConversation()
            }
            R.id.menu_chat_history -> {
                autoScroll?.followLatest()
                showDialogFragment<AgentConversationHistoryDialog>()
            }
            R.id.menu_manage_supplier -> startActivity<AiSourceManageActivity>()
            R.id.menu_manage_image_source -> startActivity<AiImageSourceManageActivity>()
            R.id.menu_manage_search_source -> startActivity<AiSearchSourceManageActivity>()
            R.id.menu_manage_persona -> startActivity<AiPersonaManageActivity>()
            R.id.menu_manage_presets -> startActivity<AiPresetManageActivity>()
            R.id.menu_manage_tools -> startActivity<AiToolManageActivity>()
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
                navigationBarHeight = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.navigationBars()
                ).bottom,
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
    // 电脑控制等场景中，窗口可能因输入而重排，但 IME Insets 会暂时为 0 或较小值。
    // 始终保留主界面底部导航栏的空间，再根据实际 IME 高度向上避让。
    return maxOf(
        navigationBarHeight + bottomBarOffset,
        imeHeight + keyboardGap
    )
}
