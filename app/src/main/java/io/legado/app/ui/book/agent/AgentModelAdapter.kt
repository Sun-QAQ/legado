package io.legado.app.ui.book.agent

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AiSource
import io.legado.app.data.entities.AiModelThinking
import com.google.android.material.chip.Chip
import io.legado.app.databinding.ItemAgentModelBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import kotlin.math.roundToInt

internal sealed interface AgentModelItem {
    data class Supplier(val source: AiSource) : AgentModelItem
    data class Model(val source: AiSource, val name: String) : AgentModelItem
    data class Hint(val text: String) : AgentModelItem
    data object Manage : AgentModelItem
}

/** 在输入栏上方按供应商分组显示模型，长列表由 RecyclerView 按需加载。 */
internal class AgentModelAdapter(
    context: Context,
    private val select: (AiSource, String) -> Unit,
    private val configureThinking: (AiSource, String, AiModelThinking) -> Unit,
    private val manage: () -> Unit
) : RecyclerAdapter<AgentModelItem, ItemAgentModelBinding>(context) {

    private var currentId = 0L
    private var sources = emptyList<AiSource>()
    private val collapsedSupplierIds = mutableSetOf<Long>()
    private var editingThinking: Pair<Long, String>? = null
    private val efforts = listOf("", "minimal", "low", "medium", "high", "xhigh", "max")

    fun currentGroupPosition(): Int = (0 until itemCount).firstOrNull {
        (getItem(it) as? AgentModelItem.Supplier)?.source?.id == currentId
    } ?: 0

    fun update(sources: List<AiSource>, selectedId: Long) {
        currentId = selectedId
        this.sources = sources.toList()
        collapsedSupplierIds.retainAll(sources.map { it.id }.toSet())
        renderGroups()
    }

    private fun renderGroups() {
        setItems(buildList {
            if (sources.isEmpty()) add(AgentModelItem.Hint(context.getString(R.string.agent_no_supplier)))
            sources.forEach { source ->
                add(AgentModelItem.Supplier(source))
                if (source.id in collapsedSupplierIds) return@forEach
                val models = (listOf(source.model) + source.getModelList()).filter { it.isNotBlank() }.distinct()
                if (models.isEmpty()) add(AgentModelItem.Hint(context.getString(R.string.ai_source_no_models)))
                models.forEach { add(AgentModelItem.Model(source, it)) }
            }
            add(AgentModelItem.Manage)
        })
    }

    override fun getViewBinding(parent: ViewGroup) = ItemAgentModelBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAgentModelBinding,
        item: AgentModelItem,
        payloads: MutableList<Any>
    ) {
        val selected = item is AgentModelItem.Model && item.source.id == currentId && item.name == item.source.model
        val heading = item is AgentModelItem.Supplier
        binding.tvName.text = when (item) {
            is AgentModelItem.Supplier -> item.source.name.ifBlank { context.getString(R.string.ai_source_supplier) }
            is AgentModelItem.Model -> item.name
            is AgentModelItem.Hint -> item.text
            AgentModelItem.Manage -> context.getString(R.string.ai_source_manage)
        }
        binding.tvName.textSize = if (item is AgentModelItem.Hint) 12f else 14f
        binding.tvName.setTypeface(null, if (heading) Typeface.BOLD else Typeface.NORMAL)
        // 使用两个全角空格的字宽缩进，不改变模型 ID 或无障碍朗读内容。
        binding.tvName.setPaddingRelative(
            if (item is AgentModelItem.Model) binding.tvName.paint.measureText("\u3000\u3000").roundToInt() else 0,
            0, 0, 0
        )
        binding.tvName.setTextColor(when {
            selected || item == AgentModelItem.Manage -> context.accentColor
            item is AgentModelItem.Hint -> context.secondaryTextColor
            else -> context.primaryTextColor
        })
        binding.ivSelected.isVisible = selected
        bindThinking(binding, item)
        binding.ivExpand.isVisible = heading
        if (item is AgentModelItem.Supplier) {
            binding.ivExpand.setImageResource(
                if (item.source.id in collapsedSupplierIds) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
        }
        binding.root.isSelected = selected
        binding.root.isClickable = heading || item is AgentModelItem.Model || item == AgentModelItem.Manage
        binding.root.isFocusable = binding.root.isClickable
        ViewCompat.setAccessibilityHeading(binding.root, heading)
        binding.root.contentDescription = when {
            item is AgentModelItem.Supplier -> context.getString(
                if (item.source.id in collapsedSupplierIds) R.string.agent_expand_model_group else R.string.agent_collapse_model_group,
                binding.tvName.text
            )
            selected -> "${binding.tvName.text}, ${context.getString(R.string.ai_source_current)}"
            else -> null
        }
    }

    private fun thinkingLabel(config: AiModelThinking): String = when (config.mode) {
        "disabled" -> context.resources.getStringArray(R.array.ai_thinking_modes)[2]
        "enabled" -> {
            val index = efforts.indexOf(config.effort)
            if (index > 0) context.resources.getStringArray(R.array.ai_thinking_efforts)[index]
            else context.resources.getStringArray(R.array.ai_thinking_modes)[1]
        }
        else -> context.getString(R.string.ai_thinking_default_short)
    }

    private fun bindThinking(binding: ItemAgentModelBinding, item: AgentModelItem) {
        binding.btnThinking.isVisible = item is AgentModelItem.Model
        binding.thinkingOptions.isVisible = item is AgentModelItem.Model && editingThinking == (item.source.id to item.name)
        binding.thinkingOptions.removeAllViews()
        if (item !is AgentModelItem.Model) return
        val config = item.source.getModelThinking(item.name)
        val label = thinkingLabel(config)
        binding.btnThinking.text = context.getString(R.string.ai_thinking_level_short, label)
        binding.btnThinking.contentDescription = context.getString(R.string.ai_thinking_for_model, item.name, label)
        binding.btnThinking.setTextColor(context.accentColor)
        if (!binding.thinkingOptions.isVisible) return
        binding.thinkingOptions.setPaddingRelative(binding.tvName.paddingStart, 0, 0, 0)
        val options = listOf(config.copy(mode = "default"), config.copy(mode = "disabled")) +
            efforts.map { config.copy(mode = "enabled", effort = it) }
        options.forEach { option ->
            binding.thinkingOptions.addView(Chip(context).apply {
                id = View.generateViewId()
                text = thinkingLabel(option)
                textSize = 12f
                isCheckable = true
                isChecked = option.mode == config.mode &&
                    (option.mode != "enabled" || option.effort == config.effort)
                setOnClickListener {
                    configureThinking(item.source, item.name, option)
                    editingThinking = null
                    renderGroups()
                }
            })
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAgentModelBinding) {
        binding.root.setOnClickListener {
            when (val item = getItem(holder.layoutPosition)) {
                is AgentModelItem.Supplier -> {
                    if (!collapsedSupplierIds.add(item.source.id)) collapsedSupplierIds.remove(item.source.id)
                    if (item.source.id in collapsedSupplierIds && editingThinking?.first == item.source.id) editingThinking = null
                    renderGroups()
                }
                is AgentModelItem.Model -> select(item.source, item.name)
                AgentModelItem.Manage -> manage()
                else -> Unit
            }
        }
        binding.btnThinking.setOnClickListener {
            val item = getItem(holder.layoutPosition) as? AgentModelItem.Model ?: return@setOnClickListener
            val key = item.source.id to item.name
            editingThinking = if (editingThinking == key) null else key
            renderGroups()
        }
    }
}
