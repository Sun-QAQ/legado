package io.legado.app.ui.main.explore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import splitties.views.onLongClick

/**
 * 发现页书源列表：卡片式书源 + 展开后的三列发现分类网格
 *
 * 说明：旧实现使用 FlexboxLayout 渲染分类并套用书源自定义的 flex 样式，
 * 新原型要求固定三列等宽网格，因此不再套用 [ExploreKind.style]（该样式仅含 flex 布局参数）。
 */
class ExploreAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<BookSourcePart, ItemFindBookBinding>(context) {

    /**
     * 已展开的书源地址（按地址记录，列表刷新后展开状态不会丢失）
     */
    private val expandedSources = linkedSetOf<String>()

    /**
     * 已解析的发现分类缓存，避免重复展示加载态
     */
    private val kindsCache = hashMapOf<String, List<ExploreKind>>()

    private val avatarPalette = arrayOf(
        intArrayOf(0xFFE8E3FF.toInt(), 0xFF5546C9.toInt()),
        intArrayOf(0xFFFFE2ED.toInt(), 0xFFA94870.toInt()),
        intArrayOf(0xFFD9F3E8.toInt(), 0xFF22755A.toInt()),
        intArrayOf(0xFFDCECFF.toInt(), 0xFF32699F.toInt()),
        intArrayOf(0xFFF4E5CE.toInt(), 0xFF8A5C22.toInt()),
        intArrayOf(0xFFEFE3FF.toInt(), 0xFF6B46C1.toInt())
    )

    override fun getViewBinding(parent: ViewGroup): ItemFindBookBinding {
        return ItemFindBookBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFindBookBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        val position = holder.layoutPosition
        val sourceUrl = item.bookSourceUrl
        val expanded = expandedSources.contains(sourceUrl)
        binding.run {
            tvName.text = item.bookSourceName
            val detail = detailText(item)
            tvDetail.text = detail
            tvDetail.isVisible = detail.isNotEmpty()
            bindAvatar(tvAvatar, item)
            ivStatus.rotation = if (expanded) 90f else 0f
            llCategories.isVisible = expanded
            if (!expanded) {
                llLoading.isVisible = false
                llCategoryGrid.removeAllViews()
                return
            }
            val kinds = kindsCache[sourceUrl]
            if (kinds != null) {
                llLoading.isVisible = false
                upKindList(llCategoryGrid, sourceUrl, kinds)
                return
            }
            llCategoryGrid.removeAllViews()
            llLoading.isVisible = true
            rotateLoading.loadingColor = context.accentColor
            Coroutine.async(callBack.scope) {
                item.exploreKinds()
            }.onSuccess { kindList ->
                kindsCache[sourceUrl] = kindList
                // 异步期间 item 可能已被回收或复用，需要确认视图仍对应同一书源
                if (holder.layoutPosition == position && getItem(position)?.bookSourceUrl == sourceUrl) {
                    llLoading.isVisible = false
                    upKindList(llCategoryGrid, sourceUrl, kindList)
                }
            }.onError {
                if (holder.layoutPosition == position && getItem(position)?.bookSourceUrl == sourceUrl) {
                    llLoading.isVisible = false
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFindBookBinding) {
        binding.apply {
            llTitle.setOnClickListener { toggleExpand(holder.layoutPosition) }
            llTitle.onLongClick {
                getItem(holder.layoutPosition)?.let { callBack.showSourceMenu(it) }
            }
            ivMenu.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.showSourceMenu(it) }
            }
        }
    }

    /**
     * 展开/收起书源，返回该行是否处于展开状态
     */
    fun toggleExpand(position: Int): Boolean {
        val source = getItem(position) ?: return false
        val sourceUrl = source.bookSourceUrl
        return if (expandedSources.remove(sourceUrl)) {
            notifyItemChanged(position)
            false
        } else {
            expandedSources.add(sourceUrl)
            notifyItemChanged(position)
            callBack.scrollTo(position)
            true
        }
    }

    /**
     * 是否已有展开的书源
     */
    fun hasExpanded(): Boolean = expandedSources.isNotEmpty()

    @SuppressLint("NotifyDataSetChanged")
    fun compressExplore(): Boolean {
        if (expandedSources.isEmpty()) return false
        expandedSources.clear()
        notifyDataSetChanged()
        return true
    }

    /**
     * 刷新某个书源的发现分类
     */
    fun refreshSource(source: BookSourcePart) {
        val position = getItems().indexOfFirst { it.bookSourceUrl == source.bookSourceUrl }
        Coroutine.async(callBack.scope) {
            source.clearExploreKindsCache()
        }.onSuccess {
            kindsCache.remove(source.bookSourceUrl)
            if (position >= 0) {
                notifyItemChanged(position)
            }
        }
    }

    /**
     * 书源头像：按地址稳定取色，取名称首字
     */
    private fun bindAvatar(view: TextView, item: BookSourcePart) {
        val colors = avatarPalette[abs(item.bookSourceUrl.hashCode()) % avatarPalette.size]
        view.text = item.bookSourceName.firstOrNull()?.toString() ?: "#"
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14.dpToPx().toFloat()
            setColor(colors[0])
        }
        view.setTextColor(colors[1])
    }

    private fun detailText(item: BookSourcePart): String {
        val parts = arrayListOf<String>()
        item.bookSourceGroup?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (item.hasLoginUrl) parts.add(context.getString(R.string.login))
        return parts.joinToString(" · ")
    }

    /**
     * 渲染三列等宽的分类网格
     */
    private fun upKindList(container: LinearLayout, sourceUrl: String, kinds: List<ExploreKind>) {
        container.removeAllViews()
        container.isVisible = kinds.isNotEmpty()
        kinds.chunked(COLUMN_COUNT).forEach { rowKinds ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowKinds.forEach { kind ->
                row.addView(createCategoryView(kind, sourceUrl), categoryLayoutParams())
            }
            // 补占位，保证不足一行的分类也是三列等宽
            repeat(COLUMN_COUNT - rowKinds.size) {
                row.addView(View(context), categoryLayoutParams())
            }
            container.addView(row)
        }
    }

    private fun createCategoryView(kind: ExploreKind, sourceUrl: String): TextView {
        return TextView(context).apply {
            text = kind.title
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = 40.dpToPx()
            setPadding(7.dpToPx(), 8.dpToPx(), 7.dpToPx(), 8.dpToPx())
            setBackgroundResource(R.drawable.bg_explore_category)
            setTextColor(context.primaryTextColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                if (kind.title.startsWith("ERROR:")) {
                    it.activity?.showDialogFragment(TextDialog("ERROR", kind.url))
                } else {
                    callBack.openExplore(sourceUrl, kind.title, kind.url)
                }
            }
        }
    }

    private fun categoryLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            val gap = 4.dpToPx()
            setMargins(gap, gap, gap, gap)
        }
    }

    interface CallBack {
        val scope: CoroutineScope
        fun scrollTo(pos: Int)
        fun openExplore(sourceUrl: String, title: String, exploreUrl: String?)
        fun editSource(sourceUrl: String)
        fun toTop(source: BookSourcePart)
        fun deleteSource(source: BookSourcePart)
        fun searchBook(bookSource: BookSourcePart)
        fun showSourceMenu(source: BookSourcePart)
    }

    companion object {
        private const val COLUMN_COUNT = 3
    }
}
