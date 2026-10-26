package io.legado.app.ui.about

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadSessionRecorderTest {
    @Test
    fun readerResumeCheckpointsAndPausePersistTwoSessionsWithoutTheBackgroundGap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = "时段回归-${UUID.randomUUID()}"
        val book = Book(bookUrl = "local:recorder-$name", name = name, author = "测试作者")
        val enabled = AppConfig.enableReadRecord
        try {
            instrumentation.runOnMainSync {
                AppConfig.enableReadRecord = true
                ReadBook.onReaderResumed(book)
            }
            Thread.sleep(80)
            instrumentation.runOnMainSync { ReadBook.upReadTime() }
            Thread.sleep(50)
            instrumentation.runOnMainSync { ReadBook.onReaderPaused() }
            globalExecutor.submit {}.get()
            val first = appDb.readSessionDao.all.filter { it.bookName == name }.single()
            assertTrue(first.readTime >= 100)
            assertEquals(first.endTime - first.startTime, first.readTime)
            Thread.sleep(100)
            instrumentation.runOnMainSync { ReadBook.onReaderResumed(book) }
            Thread.sleep(80)
            instrumentation.runOnMainSync { ReadBook.onReaderPaused() }
            globalExecutor.submit {}.get()
            val sessions = appDb.readSessionDao.all.filter { it.bookName == name }.sortedBy { it.startTime }
            assertEquals(2, sessions.size)
            assertTrue(sessions[1].startTime - sessions[0].endTime >= 80)
            assertEquals(sessions.sumOf { it.readTime }, appDb.readRecordDao.getReadTime(name))
            assertEquals(sessions.sumOf { it.readTime },
                appDb.readStatDao.all.filter { it.bookName == name }.sumOf { it.readTime })
        } finally {
            instrumentation.runOnMainSync {
                ReadBook.onReaderPaused()
                AppConfig.enableReadRecord = enabled
            }
            globalExecutor.submit {
                appDb.runInTransaction {
                    appDb.readSessionDao.deleteByName(name)
                    appDb.readStatDao.deleteByName(name)
                    appDb.readRecordDao.deleteByName(name)
                }
            }.get()
        }
    }
}
