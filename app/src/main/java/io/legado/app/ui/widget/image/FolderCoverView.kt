package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import io.legado.app.lib.theme.ThemePalette
import io.legado.app.ui.main.bookshelf.style2.FolderBookCover
import io.legado.app.utils.dpToPx

/** Fixed slots keep a one-book folder in the top-left corner instead of enlarging its cover. */
class FolderCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private val inset = 6.dpToPx()
    private val gap = 3.dpToPx()
    private val covers = List(4) {
        CoverImageView(context, filletRadius = 2f.dpToPx()).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }
    private var displayedCovers: List<FolderBookCover>? = null

    init {
        background = GradientDrawable().apply {
            cornerRadius = 6f.dpToPx()
            setColor(ThemePalette(context).surface)
        }
        clipToOutline = true
    }

    fun load(items: List<FolderBookCover>) {
        if (items == displayedCovers) return
        displayedCovers = items.toList()
        covers.forEachIndexed { index, image ->
            val item = items.getOrNull(index)
            if (item == null) {
                Glide.with(image).clear(image)
                image.setImageDrawable(null)
                image.visibility = GONE
            } else {
                image.visibility = VISIBLE
                image.load(item.path, item.name, item.author, false, item.origin)
            }
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 7 / 5
        setMeasuredDimension(width, height)
        val coverWidth = ((width - inset * 2 - gap) / 2).coerceAtLeast(0)
        covers.filter { it.visibility != GONE }.forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(coverWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(coverWidth * 7 / 5, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        covers.forEachIndexed { index, image ->
            if (image.visibility != GONE) {
                val x = inset + index % 2 * (image.measuredWidth + gap)
                val y = inset + index / 2 * (image.measuredHeight + gap)
                image.layout(x, y, x + image.measuredWidth, y + image.measuredHeight)
            }
        }
    }
}
