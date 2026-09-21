package io.legado.app.ui.main.explore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexboxLayout
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
 * 发现页书源列表：卡片式书源 + 展开后的发现分类
 *
 * 分类使用 FlexboxLayout 渲染：默认每个分类占 1/3 宽（三列等宽），
 * 书源可通过 [ExploreKind.style] 自定义 flex 参数，例如
 * `{"layout_flexBasisPercent": 1, "layout_flexGrow": 1}` 可让该项独占整行。
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
                flexbox.removeAllViews()
                return
            }
            val kinds = kindsCache[sourceUrl]
            if (kinds != null) {
                llLoading.isVisible = false
                upKindList(flexbox, sourceUrl, kinds)
                return
            }
            flexbox.removeAllViews()
            llLoading.isVisible = true
            rotateLoading.loadingColor = context.accentColor
            Coroutine.async(callBack.scope) {
                item.exploreKinds()
            }.onSuccess { kindList ->
                kindsCache[sourceUrl] = kindList
                // 异步期间 item 可能已被回收或复用，需要确认视图仍对应同一书源
                if (holder.layoutPosition == position && getItem(position)?.bookSourceUrl == sourceUrl) {
                    llLoading.isVisible = false
                    upKindList(flexbox, sourceUrl, kindList)
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
     * 渲染分类：默认三列等宽，书源自定义的 flex 样式（如 flexBasisPercent=1 独占整行）优先生效
     */
    private fun upKindList(flexbox: FlexboxLayout, sourceUrl: String, kinds: List<ExploreKind>) {
        flexbox.removeAllViews()
        flexbox.isVisible = kinds.isNotEmpty()
        kinds.forEach { kind ->
            val tv = createCategoryView(kind, sourceUrl)
            tv.layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                width = 0
                flexShrink = 1f
                flexGrow = 0f
            }
            // 书源自定义样式：flexBasisPercent / flexGrow / wrapBefore 等
            kind.style().apply(tv)
            val lp = tv.layoutParams as FlexboxLayout.LayoutParams
            if (lp.flexBasisPercent < 0f) {
                // 未指定宽度比例的按三列等宽排布
                lp.flexBasisPercent = DEFAULT_BASIS_PERCENT
            }
            flexbox.addView(tv)
        }
    }

    private fun createCategoryView(kind: ExploreKind, sourceUrl: String): TextView {
        return TextView(context).apply {
            text = kind.title
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 背景内缩 4dp 形成网格间隙，故高度需要补偿
            minHeight = 48.dpToPx()
            setPadding(7.dpToPx(), 8.dpToPx(), 7.dpToPx(), 8.dpToPx())
            setBackgroundResource(R.drawable.bg_explore_category)
            setTextColor(context.primaryTextColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            if (kind.url.isNullOrBlank()) {
                // 无地址的分类通常是书源用来分组的标题，不可点击
                isClickable = false
            } else {
                setOnClickListener {
                    if (kind.title.startsWith("ERROR:")) {
                        it.activity?.showDialogFragment(TextDialog("ERROR", kind.url))
                    } else {
                        callBack.openExplore(sourceUrl, kind.title, kind.url)
                    }
                }
            }
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
        /**
         * 未指定 layout_flexBasisPercent 时的默认宽度比例：三列等宽。
         * 取值略小于 1/3，避免浮点累加后超出容器宽度而换行成两列。
         */
        private const val DEFAULT_BASIS_PERCENT = 0.3333f
    }
}
