package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.model.VideoPlay
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.VideoPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

class VideoPlayViewModel(application: Application) : BaseViewModel(application) {
    val titleData = MutableLiveData<String>()
    val coverData = MutableLiveData<String>()

    fun initData(intent: Intent) = VideoPlay.apply {
        execute {
            val bookUrl = intent.getStringExtra("bookUrl") ?: book?.bookUrl ?: return@execute
            val book = appDb.bookDao.getBook(bookUrl) ?: return@execute
            inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            initBook(book)
        }.onFinally {
            saveRead()
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = VideoPlay.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            VideoPlay.upData(book)
        } else {
            VideoPlay.resetData(book)
        }
        titleData.postValue(book.name)
        coverData.postValue(book.getDisplayCover())
        if (book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }
        if (VideoPlay.chapterSize == 0 && !loadChapterList(book)) {
            return
        }
        upPlay()
    }

    /**
     * 进入界面自动播放, 正在播放时跳过
     */
    private fun upPlay() {
        if (!VideoPlayService.isRun || VideoPlay.status == Status.STOP) {
            VideoPlay.loadOrUpPlayUrl()
        }
    }

    private suspend fun loadBookInfo(book: Book): Boolean {
        val bookSource = VideoPlay.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(bookSource, book)
            return true
        } catch (e: Exception) {
            AppLog.put("详情页出错: ${e.localizedMessage}", e, true)
            return false
        }
    }

    private suspend fun loadChapterList(book: Book): Boolean {
        val bookSource = VideoPlay.bookSource ?: return true
        try {
            val oldBook = book.copy()
            val cList = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*cList.toTypedArray())
            VideoPlay.chapterSize = cList.size
            VideoPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
            VideoPlay.upDurChapter()
            return true
        } catch (e: Exception) {
            context.toastOnUi(R.string.error_load_toc)
            return false
        }
    }

    fun upSource() {
        execute {
            val book = VideoPlay.book ?: return@execute
            VideoPlay.bookSource = book.getBookSource()
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        execute {
            VideoPlay.book?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            VideoPlay.book?.delete()
            appDb.bookDao.insert(book)
            VideoPlay.book = book
            VideoPlay.bookSource = source
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            VideoPlay.upDurChapter()
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            VideoPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

}
