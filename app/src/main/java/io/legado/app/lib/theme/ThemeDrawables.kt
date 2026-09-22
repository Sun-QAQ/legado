package io.legado.app.lib.theme

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.utils.dpToPx

/** 保留资源中的圆角、尺寸和内边距，只替换可配置的颜色。 */
object ThemeDrawables {
    fun get(context: Context, id: Int, palette: ThemePalette = ThemePalette(context)): Drawable? {
        palette.color(id)?.let { return ColorDrawable(it) }
        val fill = when (id) {
            R.drawable.bg_bottom_sheet, R.drawable.bg_card_rounded,
            R.drawable.bg_btn_shelf, R.drawable.bg_chat_bubble_agent,
            R.drawable.bg_dialog_login, R.drawable.bg_find_book_group,
            R.drawable.bg_input_bar, R.drawable.bg_menu_card, R.drawable.bg_popup_menu,
            R.drawable.bg_search_pill, R.drawable.bg_source_card,
            R.drawable.bg_stat_card, R.drawable.shape_card_view,
            R.drawable.shape_fillet_btn, R.drawable.shape_fillet_btn_press,
            R.drawable.shape_explore_action, R.drawable.shape_explore_category -> palette.surface
            R.drawable.bg_explore_empty_visual, R.drawable.bg_source_avatar,
            R.drawable.shape_explore_action_press, R.drawable.shape_explore_category_press -> palette.accentContainer
            R.drawable.bg_sheet_handle, R.drawable.ic_divider,
            R.drawable.recyclerview_divider_horizontal, R.drawable.recyclerview_divider_vertical -> palette.divider
            R.drawable.bg_img_border -> palette.background
            else -> null
        }
        if (fill != null) {
            val drawable = ContextCompat.getDrawable(context, id)?.mutate() ?: return null
            val shape = (if (drawable is InsetDrawable) drawable.drawable else drawable) as? GradientDrawable
            shape?.setColor(fill)
            if (id == R.drawable.bg_img_border) shape?.setStroke(1.dpToPx(), palette.divider)
            return drawable
        }
        fun pressed(normal: Drawable?, active: Drawable?): StateListDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), active)
            addState(intArrayOf(), normal)
        }
        return when (id) {
            R.drawable.bg_chapter_item_divider -> GradientDrawable().apply {
                shape = GradientDrawable.LINE
                setStroke(1, palette.divider, 3f.dpToPx(), 3f.dpToPx())
            }
            R.drawable.bg_explore_category -> InsetDrawable(
                pressed(get(context, R.drawable.shape_explore_category, palette),
                    get(context, R.drawable.shape_explore_category_press, palette)), 4.dpToPx())
            R.drawable.bg_explore_action -> pressed(
                get(context, R.drawable.shape_explore_action, palette),
                get(context, R.drawable.shape_explore_action_press, palette))
            R.drawable.selector_fillet_btn_bg -> pressed(
                get(context, R.drawable.shape_fillet_btn, palette),
                (get(context, R.drawable.shape_fillet_btn_press, palette) as? GradientDrawable)?.apply {
                    setColor(palette.accentContainer)
                })
            R.drawable.selector_btn_accent_bg -> pressed(ColorDrawable(palette.accent),
                ColorDrawable(io.legado.app.utils.ColorUtils.darkenColor(palette.accent)))
            R.drawable.selector_common_bg -> StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(palette.accentContainer))
                addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(palette.accentContainer))
                addState(intArrayOf(), ColorDrawable(palette.background))
            }
            else -> null
        }
    }
}
