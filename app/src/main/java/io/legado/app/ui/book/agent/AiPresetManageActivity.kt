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
import io.legado.app.data.entities.AiPreset
import io.legado.app.databinding.ActivityAiPresetBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * AI 预设管理
 */
class AiPresetManageActivity :
    VMBaseActivity<ActivityAiPresetBinding, AiPresetManageViewModel>(),
    AiPresetAdapter.CallBack {

    override val binding by viewBinding(ActivityAiPresetBinding::inflate)
    override val viewModel by viewModels<AiPresetManageViewModel>()
    private val adapter by lazy { AiPresetAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.tvUsage.setTextColor(secondaryTextColor)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.aiPresetDao.observeAll().catch {
                AppLog.put("AI预设管理界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(kotlinx.coroutines.Dispatchers.IO).conflate().collect {
                adapter.currentId = viewModel.resolveCurrent(it)
                val noPreset = AiPreset(
                    id = AiPresetAdapter.NO_PRESET_ID,
                    name = getString(R.string.agent_preset_default),
                    prompt = getString(R.string.ai_preset_default_desc),
                    lastUpdateTime = 0L
                )
                adapter.setItems(listOf(noPreset) + it, adapter.diffItemCallback)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_preset_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_ai_preset -> showDialogFragment(AiPresetEditDialog())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun edit(aiPreset: AiPreset) {
        showDialogFragment(AiPresetEditDialog(aiPreset.id))
    }

    override fun select(aiPreset: AiPreset) {
        viewModel.select(aiPreset)
        adapter.currentId = aiPreset.id
        toastOnUi(getString(R.string.agent_preset_switched, aiPreset.name))
    }

    override fun delete(aiPreset: AiPreset) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del_any, aiPreset.name))
            noButton()
            yesButton {
                viewModel.delete(aiPreset)
                toastOnUi(R.string.delete)
            }
        }
    }

}
