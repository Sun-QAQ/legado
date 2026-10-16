package io.legado.app.ui.main.explore

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import com.google.android.flexbox.FlexboxLayout

/** 默认分类按整数像素分成三列，避免百分比四舍五入后第三项溢出换行。 */
class ExploreCategoryLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FlexboxLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val availableWidth =
                (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            for (index in 0 until childCount) {
                val params = getChildAt(index).layoutParams
                if (params is DefaultColumnLayoutParams) {
                    params.flexBasisPercent = -1f
                    params.width = (availableWidth / 3 - params.leftMargin - params.rightMargin)
                        .coerceAtLeast(0)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 只标记没有指定宽度比例的分类，自定义 flex 参数仍由 Flexbox 处理。 */
    class DefaultColumnLayoutParams :
        FlexboxLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
}
