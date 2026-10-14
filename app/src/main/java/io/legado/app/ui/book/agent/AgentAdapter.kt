package io.legado.app.ui.book.agent

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemAgentMessageBinding
import io.legado.app.databinding.ItemAgentAttachmentBinding
import io.legado.app.databinding.ItemAgentReplyBinding
import io.legado.app.databinding.ItemAgentSourceRepositoryBinding
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.databinding.ItemAgentStepBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.visible
import io.legado.app.utils.dpToPx
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File

class AgentAdapter(
    private val context: Context,
    private val callBack: CallBack
) : RecyclerView.Adapter<ItemViewHolder>() {

    private val items = arrayListOf<AgentMessage>()
    private val expandedSteps = HashSet<Int>()
    private var renderScope: CoroutineScope? = null
    private val renderJobs = mutableMapOf<TextView, Job>()
    private val pendingTexts = mutableMapOf<TextView, String>()
    private val markdownMutex = Mutex()
    private val imageRequestManager = Glide.with(context)
    private val imageAnimationListener = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean = false

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            startAgentImageAnimation(resource)
            return false
        }
    }
    private val markwon = Markwon.builder(context)
        .usePlugin(
            GlideImagesPlugin.create(object : GlideImagesPlugin.GlideStore {
                override fun load(drawable: AsyncDrawable): RequestBuilder<Drawable> {
                    return imageRequestManager
                        .load(drawable.destination)
                        .addListener(imageAnimationListener)
                }

                override fun cancel(target: Target<*>) {
                    imageRequestManager.clear(target)
                }
            })
        )
        .usePlugin(HtmlPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .build()

    fun setItems(newItems: List<AgentMessage>) {
        if (items == newItems) return
        val diff = DiffUtil.calculateDiff(AgentMessageDiff(items, newItems), false)
        if (newItems.size < items.size || newItems.firstOrNull() != items.firstOrNull()) {
            expandedSteps.clear()
        }
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
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
        if (!last.streaming || last.text == text) return
        items[items.size - 1] = last.copy(text = text)
        notifyItemChanged(items.size - 1, AgentMessagePayload.TEXT)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        renderScope?.cancel()
        renderScope = null
        renderJobs.clear()
        pendingTexts.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: ItemViewHolder) {
        if (holder.binding is ItemAgentReplyBinding) {
            renderJobs.remove(holder.binding.tvMessage)?.cancel()
            pendingTexts.remove(holder.binding.tvMessage)
            holder.binding.tvMessage.text = ""
        }
        super.onViewRecycled(holder)
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

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int, payloads: MutableList<Any>) {
        if (!items[position].isUser && payloads.isNotEmpty() && payloads.all { it is AgentMessagePayload }) {
            val binding = ItemAgentReplyBinding.bind(holder.itemView)
            if (AgentMessagePayload.TEXT in payloads) bindReplyText(binding, items[position])
            if (AgentMessagePayload.STEPS in payloads) bindSteps(binding, items[position], position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.itemView
        if (item.isUser) {
            val viewBinding = ItemAgentMessageBinding.bind(binding)
            viewBinding.tvMessage.text = item.text
            viewBinding.tvMessage.backgroundTintList = ColorStateList.valueOf(context.accentColor)
            viewBinding.llAttachments.removeAllViews()
            viewBinding.llAttachments.visibility = if (item.attachments.isEmpty()) View.GONE else View.VISIBLE
            item.attachments.forEach { attachment ->
                val attachmentBinding = ItemAgentAttachmentBinding.inflate(
                    LayoutInflater.from(context), viewBinding.llAttachments, false
                )
                attachmentBinding.tvName.text = attachment.name
                attachmentBinding.btnRemove.visibility = View.GONE
                if (attachment.mimeType.startsWith("image/")) {
                    imageRequestManager.load(File(attachment.path))
                        .error(R.drawable.image_loading_error).into(attachmentBinding.ivPreview)
                    attachmentBinding.root.setOnClickListener { callBack.openGeneratedImage(attachment.path) }
                }
                viewBinding.llAttachments.addView(attachmentBinding.root)
            }
        } else {
            val viewBinding = ItemAgentReplyBinding.bind(binding)
            bindReplyText(viewBinding, item)
            viewBinding.btnLoadMore.visibility =
                if (item.canLoadMore) View.VISIBLE else View.GONE
            viewBinding.btnLoadMore.backgroundTintList =
                ColorStateList.valueOf(context.accentColor)
            viewBinding.btnLoadMore.setOnClickListener {
                callBack.onLoadMore()
            }
            bindSteps(viewBinding, item, position)
            bindGeneratedImages(viewBinding, item)
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
            if (item.repositorySources.isEmpty()) {
                viewBinding.llSourceRepositoryResult.visibility = View.GONE
            } else {
                viewBinding.llSourceRepositoryResult.visible()
                viewBinding.tvSourceRepositoryCount.text = context.getString(
                    R.string.agent_source_repository_count,
                    item.repositorySources.size
                )
                val sourceAdapter = SourceListAdapter(context, callBack)
                sourceAdapter.setItems(item.repositorySources)
                viewBinding.rvSourceRepository.layoutManager =
                    WrapContentLinearLayoutManager(context)
                viewBinding.rvSourceRepository.adapter = sourceAdapter
            }
        }
    }

    private fun bindReplyText(binding: ItemAgentReplyBinding, item: AgentMessage) {
        val textView = binding.tvMessage
        textView.visibility = if (item.text.isBlank()) View.GONE else View.VISIBLE
        if (item.text.isBlank()) {
            renderJobs.remove(textView)?.cancel()
            pendingTexts.remove(textView)
            textView.text = ""
            return
        }
        pendingTexts[textView] = item.text
        if (renderJobs[textView]?.isActive == true) return
        val scope = renderScope ?: return
        renderJobs[textView] = scope.launch {
            try {
                while (true) {
                    val text = pendingTexts[textView] ?: break
                    // 后台串行解析，期间到达的新 token 合并为下一次快照。
                    // 不反复取消正在解析的长回复，避免始终来不及显示。
                    val markdown = markdownMutex.withLock {
                        withContext(Dispatchers.Default) { markwon.toMarkdown(text) }
                    }
                    val latest = pendingTexts[textView] ?: break
                    if (latest.startsWith(text)) markwon.setParsedMarkdown(textView, markdown)
                    if (latest == text) break
                }
            } finally {
                if (renderJobs[textView] === coroutineContext[Job]) renderJobs.remove(textView)
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

    private fun bindGeneratedImages(binding: ItemAgentReplyBinding, item: AgentMessage) {
        binding.llGeneratedImages.removeAllViews()
        binding.llGeneratedImages.visibility =
            if (item.generatedImages.isEmpty()) View.GONE else View.VISIBLE
        item.generatedImages.forEach { generated ->
            val imageView = AppCompatImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    280.dpToPx()
                ).apply { bottomMargin = 6.dpToPx() }
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = generated.prompt
                setOnClickListener { callBack.openGeneratedImage(generated.path) }
            }
            imageRequestManager.load(File(generated.path))
                .error(R.drawable.image_loading_error)
                .into(imageView)
            binding.llGeneratedImages.addView(imageView)
        }
    }

    class SourceListAdapter(
        private val context: Context,
        private val callBack: CallBack
    ) : RecyclerView.Adapter<ItemViewHolder>() {

        private val items = arrayListOf<SourceRepositoryItem>()

        fun setItems(newItems: List<SourceRepositoryItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            return ItemViewHolder(
                ItemAgentSourceRepositoryBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = items[position]
            val binding = ItemAgentSourceRepositoryBinding.bind(holder.itemView)
            binding.tvName.text = item.name
            binding.tvSourceUrl.text = item.sourceUrl.ifBlank {
                context.getString(R.string.agent_source_repository_no_url)
            }
            binding.tvMeta.text = buildList {
                item.version.takeIf { it.isNotBlank() }?.let(::add)
                item.capabilities.takeIf { it.isNotEmpty() }?.joinToString("/")?.let(::add)
                add(context.getString(R.string.agent_source_downloads, item.downloads))
            }.joinToString(" · ")
            val authorText = listOf(item.author, item.updatedAt)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            binding.tvAuthor.text = authorText
            binding.tvAuthor.visibility = if (authorText.isBlank()) View.GONE else View.VISIBLE
            binding.btnImport.backgroundTintList = ColorStateList.valueOf(context.accentColor)
            binding.btnImport.setOnClickListener {
                items.getOrNull(holder.bindingAdapterPosition)?.let(callBack::importBookSource)
            }
        }
    }

    interface CallBack {
        fun openBook(book: SearchBook)
        fun onLoadMore()
        fun importBookSource(source: SourceRepositoryItem)
        fun openGeneratedImage(path: String)
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

internal fun startAgentImageAnimation(resource: Any): Boolean {
    val animation = resource as? Animatable ?: return false
    animation.start()
    return true
}
