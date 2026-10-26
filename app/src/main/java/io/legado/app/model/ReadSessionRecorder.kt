package io.legado.app.model

import android.os.SystemClock
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import java.time.ZoneId

/** Capture book and clocks before enqueueing IO, so a subsequent book switch cannot misattribute time. */
internal object ReadSessionRecorder {
    private val tracker by lazy { ReadSessionTracker(androidId) }
    private var book: ReadSessionTracker.Book? = null
    @Volatile var readerActive: Boolean = false
        private set

    @Synchronized
    fun resume(book: Book?) {
        readerActive = true
        this.book = book?.let { ReadSessionTracker.Book(it.bookUrl, it.name, it.author) }
        checkpoint()
    }

    @Synchronized
    fun setBook(book: Book?) {
        this.book = book?.let { ReadSessionTracker.Book(it.bookUrl, it.name, it.author) }
        checkpoint()
    }

    @Synchronized
    fun pause() {
        readerActive = false
        book = null
        checkpoint()
    }

    @Synchronized
    fun checkpoint(enabled: Boolean = AppConfig.enableReadRecord) {
        val increments = tracker.update(readerActive, enabled, book,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault())
        if (increments.isEmpty()) return
        globalExecutor.execute {
            runCatching { ReadSessionStorage.append(appDb, increments) }.onFailure {
                AppLog.put("保存阅读时段失败", it)
            }
        }
    }
}
