package io.legado.app.ui.book.read

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.App
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadBookSessionFeatureTest {
    @Test
    fun sourceChangeAfterUnreadyInitializationStartsRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<App>()
        val store = ViewModelStore()
        val originalBook = ReadBook.book
        val originalCallback = ReadBook.callBack
        val enabled = AppConfig.enableReadRecord
        val name = "换源时段回归-${UUID.randomUUID()}"
        val book = Book(bookUrl = "local:$name", name = name, origin = BookType.localTag)
        lateinit var viewModel: ReadBookViewModel
        try {
            instrumentation.runOnMainSync {
                AppConfig.enableReadRecord = true
                ReadBook.book = null
                ReadBook.callBack = null
                ReadBook.onReaderResumed(null)
                viewModel = ViewModelProvider(store,
                    ViewModelProvider.AndroidViewModelFactory(application))[ReadBookViewModel::class.java]
                assertFalse(viewModel.isReadingSessionReady)
                viewModel.changeTo(book, emptyList())
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = viewModel.isReadingSessionReady }
                if (!ready) Thread.sleep(20)
            }
            assertTrue("Changing source must finish reading-session initialization", ready)
            Thread.sleep(100)
            instrumentation.runOnMainSync {
                ReadBook.upReadTime()
                ReadBook.onReaderPaused()
            }
            globalExecutor.submit {}.get()
            val session = appDb.readSessionDao.all.single { it.bookName == name }
            assertEquals(book.bookUrl, session.bookUrl)
            assertTrue(session.readTime > 0)
            assertEquals(session.readTime, appDb.readRecordDao.getReadTime(name))
            assertEquals(session.readTime,
                appDb.readStatDao.all.single { it.bookName == name }.readTime)
        } finally {
            instrumentation.runOnMainSync {
                store.clear()
                ReadBook.onReaderPaused()
                ReadBook.book = originalBook
                ReadBook.callBack = originalCallback
                AppConfig.enableReadRecord = enabled
            }
            globalExecutor.submit {
                appDb.runInTransaction {
                    appDb.bookDao.delete(book)
                    appDb.readSessionDao.deleteByName(name)
                    appDb.readStatDao.deleteByName(name)
                    appDb.readRecordDao.deleteByName(name)
                }
            }.get()
        }
    }
}
