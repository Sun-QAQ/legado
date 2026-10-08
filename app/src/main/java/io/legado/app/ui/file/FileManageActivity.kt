package io.legado.app.ui.file

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ActivityFileManageBinding
import io.legado.app.databinding.ItemFileManageBinding
import io.legado.app.databinding.ItemFileManagePathBinding
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.openFileUri
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

class FileManageActivity : VMBaseActivity<ActivityFileManageBinding, FileManageViewModel>() {

    override val binding by viewBinding(ActivityFileManageBinding::inflate)
    override val viewModel by viewModels<FileManageViewModel>()
    private val palette by lazy { ThemePalette(this) }
    private val searchView: SearchView by lazy {
        binding.searchView
    }
    private val pathAdapter by lazy {
        PathAdapter()
    }
    private val fileAdapter by lazy {
        FileAdapter()
    }
    private val currentFiles = arrayListOf<File>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initSearchView()
        viewModel.upFiles(viewModel.rootDoc)
    }

    private fun initView() {
        binding.root.setBackgroundColor(palette.background)
        binding.titleBar.elevation = 0f
        binding.searchView.background = roundedBackground(palette.surface, 16)
        binding.tvFileCount.setTextColor(palette.secondaryText)
        binding.tvEmptyMsg.setTextColor(palette.secondaryText)
        binding.rvPath.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvPath.adapter = pathAdapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        binding.recyclerView.adapter = fileAdapter
        binding.recyclerView.applyNavigationBarPadding()
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.lastDir != viewModel.rootDoc) {
                gotoLastDir()
                return@addCallback
            }
            finish()
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.file_manage_filter)
        searchView.isSubmitButtonEnabled = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                updateFiles()
                return false
            }
        })
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.dpToPx().toFloat()
    }

    private fun itemBackground(color: Int) = RippleDrawable(
        ColorStateList.valueOf(palette.accentContainerPressed),
        roundedBackground(color, 16),
        roundedBackground(android.graphics.Color.WHITE, 16)
    )

    private fun updateFiles() {
        val query = searchView.query.toString()
        val files = currentFiles.filter {
            it == viewModel.lastDir || it.name.contains(query, ignoreCase = true)
        }
        fileAdapter.setItems(files)
        val count = files.count { it != viewModel.lastDir }
        binding.tvFileCount.text = getString(R.string.file_manage_count, count)
        binding.tvEmptyMsg.text = getString(
            if (query.isEmpty()) R.string.file_manage_empty else R.string.file_manage_no_results
        )
        binding.tvEmptyMsg.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    private fun gotoLastDir() {
        viewModel.subDocs.removeLastOrNull()
        pathAdapter.setItems(viewModel.subDocs)
        viewModel.upFiles(viewModel.lastDir)
    }

    override fun observeLiveBus() {
        viewModel.filesLiveData.observe(this) {
            searchView.setQuery("", false)
            currentFiles.clear()
            currentFiles.addAll(it)
            updateFiles()
        }
    }

    inner class PathAdapter :
        RecyclerAdapter<File, ItemFileManagePathBinding>(this@FileManageActivity) {

        private fun stylePath(binding: ItemFileManagePathBinding) {
            binding.textView.setTextColor(palette.text)
            binding.imageView.imageTintList = ColorStateList.valueOf(palette.secondaryText)
            binding.root.background = itemBackground(palette.background)
        }

        init {
            addHeaderView {
                ItemFileManagePathBinding.inflate(inflater, it, false).apply {
                    textView.setText(R.string.file_manage_root)
                    stylePath(this)
                    root.setOnClickListener {
                        viewModel.subDocs.clear()
                        setItems(viewModel.subDocs)
                        viewModel.upFiles(viewModel.rootDoc)
                    }
                }
            }
        }

        override fun getViewBinding(parent: ViewGroup): ItemFileManagePathBinding {
            return ItemFileManagePathBinding.inflate(inflater, parent, false).apply {
                stylePath(this)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemFileManagePathBinding) {
            binding.root.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                viewModel.subDocs = viewModel.subDocs.take(position).toMutableList()
                setItems(viewModel.subDocs)
                viewModel.upFiles(viewModel.lastDir)
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemFileManagePathBinding,
            item: File,
            payloads: MutableList<Any>
        ) {
            binding.textView.text = item.name
        }

    }

    inner class FileAdapter : RecyclerAdapter<File, ItemFileManageBinding>(this@FileManageActivity) {
        override fun getViewBinding(parent: ViewGroup): ItemFileManageBinding {
            return ItemFileManageBinding.inflate(inflater, parent, false).apply {
                root.background = itemBackground(palette.surface)
                imageView.background = roundedBackground(palette.accentContainer, 14)
                imageView.imageTintList = ColorStateList.valueOf(palette.text)
                textView.setTextColor(palette.text)
                tvDetail.setTextColor(palette.secondaryText)
                ivArrow.imageTintList = ColorStateList.valueOf(palette.secondaryText)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemFileManageBinding) {
            binding.root.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                item?.let {
                    if (item == viewModel.lastDir) {
                        gotoLastDir()
                    } else if (item.isDirectory) {
                        viewModel.subDocs.add(item)
                        pathAdapter.setItems(viewModel.subDocs)
                        this@FileManageActivity.binding.rvPath.smoothScrollToPosition(pathAdapter.itemCount - 1)
                        viewModel.upFiles(item)
                    } else {
                        openFileUri(
                            FileProvider.getUriForFile(
                                this@FileManageActivity,
                                AppConst.authority,
                                item
                            )
                        )
                    }
                }
            }
            binding.root.setOnLongClickListener { view ->
                val item = getItemByLayoutPosition(holder.layoutPosition)
                if (item == viewModel.lastDir) {
                    return@setOnLongClickListener true
                }
                item?.let {
                    showFileMenu(view, item)
                }
                return@setOnLongClickListener true
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemFileManageBinding,
            item: File,
            payloads: MutableList<Any>
        ) {
            binding.ivArrow.visibility = if (item.isDirectory) View.VISIBLE else View.INVISIBLE
            if (item == viewModel.lastDir) {
                binding.imageView.setImageResource(R.drawable.ic_arrow_back)
                binding.textView.setText(R.string.file_manage_parent)
                binding.tvDetail.setText(R.string.file_manage_parent_hint)
            } else if (item.isDirectory) {
                binding.tvDetail.setText(R.string.file_manage_folder)
                binding.imageView.setImageResource(R.drawable.ic_folder_outline)
                binding.textView.text = item.name
            } else {
                binding.tvDetail.text = ConvertUtils.formatFileSize(item.length())
                binding.imageView.setImageResource(R.drawable.ic_file_document)
                binding.textView.text = item.name
            }
        }

        private fun showFileMenu(view: View, file: File) {
            val popupMenu = PopupMenu(context, view)
            popupMenu.inflate(R.menu.file_long_click)
            popupMenu.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_del -> viewModel.delFile(file)
                }
                true
            }
            popupMenu.show()
        }

    }

}
