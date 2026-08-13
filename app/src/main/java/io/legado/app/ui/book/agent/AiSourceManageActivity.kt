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
import io.legado.app.data.entities.AiSource
import io.legado.app.databinding.ActivityAiSourceBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * AI 供应商管理
 */
class AiSourceManageActivity :
    VMBaseActivity<ActivityAiSourceBinding, AiSourceManageViewModel>(),
    AiSourceAdapter.CallBack {

    override val binding by viewBinding(ActivityAiSourceBinding::inflate)
    override val viewModel by viewModels<AiSourceManageViewModel>()
    private val adapter by lazy { AiSourceAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.aiSourceDao.observeAll().catch {
                AppLog.put("AI供应商管理界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(kotlinx.coroutines.Dispatchers.IO).conflate().collect {
                adapter.setItems(it, adapter.diffItemCallback)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_source_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_ai_source -> showDialogFragment(AiSourceEditDialog())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun enable(enabled: Boolean, aiSource: AiSource) {
        viewModel.enable(enabled, aiSource)
    }

    override fun edit(aiSource: AiSource) {
        showDialogFragment(AiSourceEditDialog(aiSource.id))
    }

    override fun fetchModels(aiSource: AiSource) {
        viewModel.fetchModels(aiSource)
    }

    override fun delete(aiSource: AiSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del_any, aiSource.name))
            noButton()
            yesButton {
                viewModel.delete(aiSource)
                toastOnUi(R.string.delete)
            }
        }
    }

}
