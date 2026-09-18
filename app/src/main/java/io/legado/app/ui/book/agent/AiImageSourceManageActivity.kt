package io.legado.app.ui.book.agent

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageSource
import io.legado.app.databinding.ActivityAiSourceBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class AiImageSourceManageActivity :
    VMBaseActivity<ActivityAiSourceBinding, AiImageSourceManageViewModel>(),
    AiImageSourceAdapter.CallBack {

    override val binding by viewBinding(ActivityAiSourceBinding::inflate)
    override val viewModel by viewModels<AiImageSourceManageViewModel>()
    private val adapter by lazy { AiImageSourceAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setTitle(R.string.ai_image_source_manage)
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.aiImageSourceDao.observeAll().catch {
                AppLog.put("获取AI生图供应商失败", it)
            }.flowOn(Dispatchers.IO).conflate().collect { sources ->
                adapter.currentId = viewModel.resolveDefault(sources)
                adapter.setItems(sources, adapter.diffItemCallback)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_image_source_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_add_ai_image_source) {
            showDialogFragment(AiImageSourceEditDialog())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun enable(enabled: Boolean, source: AiImageSource) = viewModel.enable(enabled, source)

    override fun edit(source: AiImageSource) = showDialogFragment(AiImageSourceEditDialog(source.id))

    override fun setDefault(source: AiImageSource) {
        viewModel.setDefault(source)
        adapter.currentId = source.id
        adapter.notifyDataSetChanged()
        toastOnUi(R.string.ai_image_source_default_set)
    }

    override fun delete(source: AiImageSource) {
        alert(R.string.delete) {
            setMessage(getString(R.string.sure_del_any, source.name))
            noButton()
            yesButton { viewModel.delete(source) }
        }
    }
}
