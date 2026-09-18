package io.legado.app.ui.book.agent

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiPersonaBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiToolManageActivity : BaseActivity<ActivityAiPersonaBinding>() {

    override val binding by viewBinding(ActivityAiPersonaBinding::inflate)
    private val adapter by lazy {
        AiToolAdapter(this) { name, enabled ->
            AgentToolPreferences.setEnabled(this, name, enabled)
            refreshTools()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setTitle(R.string.agent_tools)
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        refreshTools()
    }

    override fun onResume() {
        super.onResume()
        refreshTools()
    }

    private fun refreshTools() {
        adapter.setItems(
            AgentTools.infos(AgentToolPreferences.disabled(this)),
            adapter.diffItemCallback
        )
    }
}
