package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.viewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookGroupEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeDrawables
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.bookshelf.style2.FolderCoverPreview
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.applyDialogRoundedBackground
import io.legado.app.utils.externalFiles
import io.legado.app.utils.gone
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.views.onClick
import java.io.FileOutputStream

class GroupEditDialog() : BaseDialogFragment(R.layout.dialog_book_group_edit) {

    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply {
            putParcelable("group", bookGroup?.copy())
        }
    }

    private val binding by viewBinding(DialogBookGroupEditBinding::bind)
    private val viewModel by viewModels<GroupViewModel>()
    private var bookGroup: BookGroup? = null
    private val useFolderCover by lazy { AppConfig.bookGroupStyle == 1 }
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri ?: return@registerForActivityResult
        readUri(it.uri) { fileDoc, inputStream ->
            try {
                var file = requireContext().externalFiles
                val suffix = fileDoc.name.substringAfterLast(".")
                val fileName = it.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                    MD5Utils.md5Encode(tmp) + ".$suffix"
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                binding.ivCover.load(file.absolutePath)
            } catch (e: Exception) {
                appCtx.toastOnUi(e.localizedMessage)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.applyDialogRoundedBackground()
        binding.toolBar.setBackgroundColor(backgroundColor)
        binding.spSort.setPopupBackgroundDrawable(
            ThemeDrawables.get(requireContext(), R.drawable.bg_popup_menu)
        )
        (binding.spSort.adapter as? ArrayAdapter<*>)?.setDropDownViewResource(
            R.layout.item_group_sort_dropdown
        )
        @Suppress("DEPRECATION")
        bookGroup = arguments?.getParcelable("group")
        bookGroup?.let {
            binding.btnDelete.visible(it.groupId > 0 || it.groupId == Long.MIN_VALUE)
            binding.tieGroupName.setText(it.groupName)
            if (!useFolderCover) binding.ivCover.load(it.cover)
            if (it.bookSort + 1 !in 0..<binding.spSort.count) {
                it.bookSort = -1
            }
            binding.spSort.setSelection(it.bookSort + 1)
            binding.cbEnableRefresh.isChecked = it.enableRefresh
        } ?: let {
            binding.toolBar.title = getString(R.string.add_group)
            binding.btnDelete.gone()
            if (!useFolderCover) binding.ivCover.load()
        }
        initFolderCover()
        binding.run {
            ivCover.onClick {
                selectImage.launch {
                    mode = HandleFileContract.IMAGE
                }
            }
            btnCancel.onClick {
                dismiss()
            }
            btnOk.onClick {
                val groupName = tieGroupName.text?.toString()
                if (groupName.isNullOrEmpty()) {
                    toastOnUi("分组名称不能为空")
                } else {
                    val bookSort = binding.spSort.selectedItemPosition - 1
                    val coverPath = if (useFolderCover) bookGroup?.cover else binding.ivCover.bitmapPath
                    val enableRefresh = binding.cbEnableRefresh.isChecked
                    bookGroup?.let {
                        it.groupName = groupName
                        it.cover = coverPath
                        it.bookSort = bookSort
                        it.enableRefresh = enableRefresh
                        viewModel.upGroup(it) {
                            dismiss()
                        }
                    } ?: let {
                        viewModel.addGroup(
                            groupName,
                            bookSort,
                            enableRefresh,
                            coverPath
                        ) {
                            dismiss()
                        }
                    }
                }

            }
            btnDelete.onClick {
                deleteGroup {
                    bookGroup?.let {
                        viewModel.delGroup(it) {
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    private fun initFolderCover() {
        binding.ivCover.visible(!useFolderCover)
        binding.folderCover.visible(useFolderCover)
        if (!useFolderCover) return
        val group = bookGroup ?: return
        val selectedSort = MutableStateFlow(binding.spSort.selectedItemPosition - 1)
        binding.spSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSort.value = position - 1
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                appDb.bookDao.flowByGroup(BookGroup.IdAll),
                appDb.bookGroupDao.flowAll(),
                selectedSort
            ) { books, groups, sort ->
                FolderCoverPreview.create(
                    group.copy(bookSort = sort), books, groups, AppConfig.bookshelfSort
                ).covers
            }.flowOn(Dispatchers.Default)
                .flowWithLifecycle(viewLifecycleOwner.lifecycle)
                .collect { binding.folderCover.load(it) }
        }
    }

    private fun deleteGroup(ok: () -> Unit) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                ok.invoke()
            }
            noButton()
        }
    }

}
