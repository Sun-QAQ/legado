package io.legado.app.ui.book.read

import android.os.Looper
import androidx.room.Room
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import splitties.init.appCtx
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class ChapterQueryFeatureTest {
    @Test fun suspendingChapterQueryLeavesMainThread() = runBlocking {
        val observed = AtomicBoolean()
        val onMainThread = AtomicBoolean()
        val db = Room.inMemoryDatabaseBuilder(appCtx, AppDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.contains("select * from chapters", ignoreCase = true)) {
                    observed.set(true)
                    onMainThread.set(Looper.myLooper() == Looper.getMainLooper())
                }
            }, Executor { it.run() }).build()
        try {
            withContext(Dispatchers.IO) {
                db.bookDao.insert(Book(bookUrl = "synthetic://chapter-query", name = "test"))
                db.bookChapterDao.insert(BookChapter(bookUrl = "synthetic://chapter-query", url = "chapter", title = "test", index = 0))
            }
            val chapter = withContext(Dispatchers.Main) {
                db.bookChapterDao.getChapterAsync("synthetic://chapter-query", 0)
            }
            assertEquals("test", chapter?.title)
            assertTrue(observed.get())
            assertFalse(onMainThread.get())
        } finally { db.close() }
    }
}
