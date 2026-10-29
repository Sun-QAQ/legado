package io.legado.app.ui.rss.read

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.showDialogFragment

@Suppress("unused")
class RssJsExtensions(private val fragment: ReadRssFragment) : JsExtensions {

    override fun getSource(): BaseSource? {
        return fragment.getSource()
    }

    fun searchBook(key: String) {
        SearchActivity.start(fragment.requireContext(), key)
    }

    fun addBook(bookUrl: String) {
        fragment.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }

}
