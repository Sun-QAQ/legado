package io.legado.app.ui.book.info.edit

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.addType
import io.legado.app.model.ReadBook
import java.util.UUID

class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {
    var book: Book? = null
    val bookData = MutableLiveData<Book>()

    fun loadBook(bookUrl: String) {
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                bookData.postValue(it)
            }
        }
    }

    fun initCreateBook(groupId: Long) {
        val bookUrl = "loc_created://${UUID.randomUUID()}"
        val newBook = Book(
            bookUrl = bookUrl,
            tocUrl = bookUrl,
            origin = BookType.localTag,
            type = BookType.text or BookType.local or BookType.created,
            group = groupId,
            order = appDb.bookDao.minOrder - 1
        )
        book = newBook
        bookData.postValue(newBook)
    }

    fun createBook(book: Book, success: (() -> Unit)?) {
        execute {
            val chapter = BookChapter(
                url = "${book.bookUrl}#0",
                title = "第 1 章",
                bookUrl = book.bookUrl,
                index = 0
            )
            book.totalChapterNum = 1
            book.latestChapterTitle = chapter.title
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(chapter)
        }.onSuccess {
            success?.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put("创建书籍失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("创建书籍失败\n$it", it, true)
            }
        }
    }

    fun saveBook(book: Book, success: (() -> Unit)?) {
        execute {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.book = book
            }
            appDb.bookDao.update(book)
        }.onSuccess {
            success?.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }
}