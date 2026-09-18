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
import io.legado.app.data.entities.AiPersona
import io.legado.app.databinding.ActivityAiPersonaBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * AI 人格管理
 */
class AiPersonaManageActivity :
    VMBaseActivity<ActivityAiPersonaBinding, AiPersonaManageViewModel>(),
    AiPersonaAdapter.CallBack {

    override val binding by viewBinding(ActivityAiPersonaBinding::inflate)
    override val viewModel by viewModels<AiPersonaManageViewModel>()
    private val adapter by lazy { AiPersonaAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.aiPersonaDao.observeAll().catch {
                AppLog.put("AI人格管理界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(kotlinx.coroutines.Dispatchers.IO).conflate().collect {
                adapter.currentId = viewModel.resolveCurrent(it)
                val defaultPersona = AiPersona(
                    id = AiPersonaAdapter.DEFAULT_PERSONA_ID,
                    name = getString(R.string.agent_persona_default),
                    prompt = getString(R.string.ai_persona_default_desc),
                    lastUpdateTime = 0L
                )
                adapter.setItems(listOf(defaultPersona) + it, adapter.diffItemCallback)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_persona_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_ai_persona -> showDialogFragment(AiPersonaEditDialog())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun edit(aiPersona: AiPersona) {
        showDialogFragment(AiPersonaEditDialog(aiPersona.id))
    }

    override fun select(aiPersona: AiPersona) {
        viewModel.select(aiPersona)
        adapter.currentId = aiPersona.id
        toastOnUi(getString(R.string.agent_persona_switched, aiPersona.name))
    }

    override fun delete(aiPersona: AiPersona) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del_any, aiPersona.name))
            noButton()
            yesButton {
                viewModel.delete(aiPersona)
                toastOnUi(R.string.delete)
            }
        }
    }

}
