package io.legado.app.ui.book.agent

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.legado.app.lib.theme.view.ThemeEditText

/** 多行显示对话输入，同时保留软键盘的发送动作。 */
class AgentInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ThemeEditText(context, attrs) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        // TextView 默认给多行输入附加此标志，键盘会因此把发送键改为换行。
        outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
        return connection
    }
}
