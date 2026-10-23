@file:Suppress("unused")

package io.legado.app.ui.widget.code

import android.content.Context
import android.widget.ArrayAdapter
import io.legado.app.R
fun CodeView.useSourceSyntax(language: SourceSyntaxHighlighter.Language = SourceSyntaxHighlighter.Language.RULE) {
    setSyntaxLanguage(language)
}

fun Context.arrayAdapter(keywords: Array<String>): ArrayAdapter<String> {
    return ArrayAdapter(this, R.layout.item_1line_text_and_del, R.id.text_view, keywords)
}
