package io.legado.app.ui.book.agent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemAgentMessageBinding
import io.legado.app.databinding.ItemAgentReplyBinding
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.databinding.ItemAgentStepBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.visible
import java.util.Locale

class AgentAdapter(
    private val context: Context,
    private val callBack: CallBack
) : RecyclerView.Adapter<ItemViewHolder>() {

    private val items = arrayListOf<AgentMessage>()
    private val expandedSteps = HashSet<Int>()

    fun setItems(newItems: List<AgentMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun addItem(item: AgentMessage) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    /**
     * 流式输出：仅更新最后一条（回复）消息的文本，避免整体刷新导致滚动跳动
     */
    fun updateLastText(text: String) {
        if (items.isEmpty()) return
        val last = items.last()
        if (!last.streaming) return
        items[items.size - 1] = last.copy(text = text)
        notifyItemChanged(items.size - 1)
    }

    fun clear() {
        items.clear()
        expandedSteps.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isUser) TYPE_USER else TYPE_REPLY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> ItemViewHolder(
                ItemAgentMessageBinding.inflate(inflater, parent, false)
            )

            TYPE_REPLY -> ItemViewHolder(
                ItemAgentReplyBinding.inflate(inflater, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.itemView
        if (item.isUser) {
            val viewBinding = ItemAgentMessageBinding.bind(binding)
            viewBinding.tvMessage.text = item.text
        } else {
            val viewBinding = ItemAgentReplyBinding.bind(binding)
            viewBinding.tvMessage.text = item.text
            viewBinding.tvMessage.visibility =
                if (item.text.isBlank()) View.GONE else View.VISIBLE
            viewBinding.btnLoadMore.visibility =
                if (item.canLoadMore) View.VISIBLE else View.GONE
            viewBinding.btnLoadMore.setOnClickListener {
                callBack.onLoadMore()
            }
            bindSteps(viewBinding, item, position)
            if (item.books.isEmpty()) {
                viewBinding.llBookResult.visibility = android.view.View.GONE
            } else {
                viewBinding.llBookResult.visible()
                viewBinding.tvResultCount.text =
                    context.getString(
                        R.string.select_count,
                        item.books.size,
                        item.books.size
                    )
                val bookAdapter = BookListAdapter(context, callBack)
                bookAdapter.setItems(item.books)
                viewBinding.rvBooks.layoutManager = WrapContentLinearLayoutManager(context)
                viewBinding.rvBooks.adapter = bookAdapter
            }
        }
    }

    private fun bindSteps(
        viewBinding: ItemAgentReplyBinding,
        item: AgentMessage,
        position: Int
    ) {
        if (item.steps.isEmpty()) {
            viewBinding.llStepsRoot.visibility = View.GONE
            return
        }
        viewBinding.llStepsRoot.visibility = View.VISIBLE
        viewBinding.tvStepsHeader.text =
            context.getString(R.string.agent_steps_count, item.steps.size)
        val expanded = expandedSteps.contains(position)
        viewBinding.llSteps.visibility = if (expanded) View.VISIBLE else View.GONE
        viewBinding.ivStepsArrow.setImageResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
        viewBinding.tvStepsHeader.setOnClickListener {
            if (!expandedSteps.add(position)) {
                expandedSteps.remove(position)
            }
            notifyItemChanged(position)
        }
        if (!expanded) return
        viewBinding.llSteps.removeAllViews()
        item.steps.forEach { step ->
            val rowBinding = ItemAgentStepBinding.inflate(
                LayoutInflater.from(context),
                viewBinding.llSteps,
                false
            )
            bindStep(rowBinding, step)
            viewBinding.llSteps.addView(rowBinding.root)
        }
    }

    private fun bindStep(binding: ItemAgentStepBinding, step: AgentStep) {
        binding.tvStepTitle.text = step.title
        val detail = buildString {
            step.detail?.let { append(it) }
            step.durationMs?.let { ms ->
                if (isNotEmpty()) append(" · ")
                append(formatDuration(ms))
            }
        }
        binding.tvStepDetail.text = detail
        binding.tvStepDetail.visibility =
            if (detail.isBlank()) View.GONE else View.VISIBLE
        binding.tvStepSummary.text = step.summary.orEmpty()
        binding.tvStepSummary.visibility =
            if (step.summary.isNullOrBlank()) View.GONE else View.VISIBLE
        when (step.state) {
            AgentStepState.RUNNING -> {
                binding.progressStep.visibility = View.VISIBLE
                binding.ivStepState.visibility = View.GONE
            }
            AgentStepState.DONE -> {
                binding.progressStep.visibility = View.GONE
                binding.ivStepState.visibility = View.VISIBLE
                binding.ivStepState.setImageResource(R.drawable.ic_check)
                binding.ivStepState.setColorFilter(ContextCompat.getColor(context, R.color.success))
            }
            AgentStepState.FAILED -> {
                binding.progressStep.visibility = View.GONE
                binding.ivStepState.visibility = View.VISIBLE
                binding.ivStepState.setImageResource(R.drawable.ic_warning)
                binding.ivStepState.setColorFilter(ContextCompat.getColor(context, R.color.error))
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        return String.format(Locale.US, "%.1fs", ms / 1000.0)
    }

    class BookListAdapter(
        private val context: Context,
        private val callBack: CallBack
    ) : RecyclerView.Adapter<ItemViewHolder>() {

        private val items = arrayListOf<SearchBook>()

        fun setItems(newItems: List<SearchBook>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            return ItemViewHolder(
                ItemSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = items[position]
            val binding = ItemSearchBinding.bind(holder.itemView)
            binding.tvName.text = item.name
            binding.tvAuthor.text = context.getString(R.string.author_show, item.author)
            binding.tvLasted.text =
                context.getString(R.string.lasted_show, item.latestChapterTitle)
            binding.tvIntroduce.text = item.trimIntro(context)
            binding.ivCover.load(
                item.coverUrl,
                item.name,
                item.author,
                AppConfig.loadCoverOnlyWifi,
                item.origin
            )
            binding.root.setOnClickListener {
                items.getOrNull(position)?.let {
                    callBack.openBook(it)
                }
            }
        }

    }

    interface CallBack {
        fun openBook(book: SearchBook)
        fun onLoadMore()
    }

    /**
     * 嵌套 RecyclerView 使用 wrap_content 时正确测量子项高度
     */
    private class WrapContentLinearLayoutManager(context: Context) :
        LinearLayoutManager(context) {

        override fun onMeasure(
            recycler: RecyclerView.Recycler,
            state: RecyclerView.State,
            widthSpec: Int,
            heightSpec: Int
        ) {
            if (heightSpec == ViewGroup.LayoutParams.WRAP_CONTENT ||
                View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED
            ) {
                val totalHeight = getTotalHeight(recycler, state.itemCount)
                super.onMeasure(
                    recycler,
                    state,
                    widthSpec,
                    View.MeasureSpec.makeMeasureSpec(totalHeight, View.MeasureSpec.EXACTLY)
                )
            } else {
                super.onMeasure(recycler, state, widthSpec, heightSpec)
            }
        }

        private fun getTotalHeight(recycler: RecyclerView.Recycler, count: Int): Int {
            var totalHeight = paddingTop + paddingBottom
            for (i in 0 until count) {
                val viewHolder = runCatching { recycler.getViewForPosition(i) }.getOrNull()
                    ?: continue
                measureChildWithMargins(
                    viewHolder,
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                totalHeight += getDecoratedMeasuredHeight(viewHolder)
                runCatching { recycler.recycleView(viewHolder) }
            }
            return totalHeight
        }

    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_REPLY = 1
    }

}
