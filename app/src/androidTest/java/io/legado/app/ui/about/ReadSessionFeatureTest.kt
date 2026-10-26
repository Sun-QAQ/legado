package io.legado.app.ui.about

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.model.ReadSessionStorage
import io.legado.app.model.ReadSessionTracker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadSessionFeatureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
    )
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 26)
    private val start = date.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
    private val book = ReadSessionTracker.Book("local:fixture", "时段测试书", "测试作者")

    @Test
    fun migrationFrom85PreservesHistoryAndPersistsSessionsAcrossReopening() {
        val name = "read-session-migration-${UUID.randomUUID()}"
        helper.createDatabase(name, 85).use {
            it.execSQL("INSERT INTO readRecord (deviceId,bookName,readTime,lastRead) VALUES ('old','原阅读记录',60000,1)")
            it.execSQL("INSERT INTO readStats (bookName,date,readTime) VALUES ('原阅读记录',1,60000)")
        }
        try {
            fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations).build()
            open().useDb { db ->
                assertEquals(60_000L, db.readRecordDao.allTime)
                assertEquals(60_000L, db.readStatDao.all.single().readTime)
                assertTrue(db.readSessionDao.all.isEmpty())
                val tracker = ReadSessionTracker("device")
                tracker.update(true, true, book, start, 0, zone)
                ReadSessionStorage.append(db, tracker.update(false, true, null, start + 1_000, 1_000, zone))
            }
            open().useDb { db ->
                assertEquals(book.name, db.readSessionDao.all.single().bookName)
                assertEquals(61_000L, db.readRecordDao.allTime)
                assertEquals(2, db.readStatDao.all.size)
                db.openHelper.readableDatabase.query("PRAGMA user_version").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(86, it.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun checkpointReplayAndDeletionKeepSessionDailyAndLifetimeTotalsConsistent() {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().useDb { db ->
            val tracker = ReadSessionTracker("device")
            tracker.update(true, true, book, start, 0, zone)
            val first = tracker.update(true, true, book, start + 30_000, 30_000, zone)
            val last = tracker.update(false, true, null, start + 45_000, 45_000, zone)
            ReadSessionStorage.append(db, first)
            ReadSessionStorage.append(db, first)
            ReadSessionStorage.append(db, last)
            ReadSessionStorage.append(db, first)
            assertEquals(1, db.readSessionDao.all.size)
            assertEquals(45_000L, db.readSessionDao.all.single().readTime)
            assertEquals(45_000L, db.readRecordDao.allTime)
            assertEquals(45_000L, db.readStatDao.all.single().readTime)
            ReadSessionStorage.delete(db, last.single().session.id)
            ReadSessionStorage.delete(db, last.single().session.id)
            assertTrue(db.readSessionDao.all.isEmpty())
            assertEquals(0L, db.readRecordDao.allTime)
            assertEquals(0L, db.readStatDao.all.single().readTime)
        }
    }

    @Test
    fun midnightWritesBothDaysAndBackupMergeDoesNotDuplicateOrShortenSessions() {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().useDb { db ->
            val tracker = ReadSessionTracker("device")
            val midnightStart = date.atTime(23, 59, 50).atZone(zone).toInstant().toEpochMilli()
            tracker.update(true, true, book, midnightStart, 0, zone)
            val parts = tracker.update(false, true, null, midnightStart + 30_000, 30_000, zone)
            ReadSessionStorage.append(db, parts)
            assertEquals(listOf(10_000L, 20_000L), db.readStatDao.all.sortedBy { it.date }.map { it.readTime })
            val backup = db.readSessionDao.all
            ReadSessionStorage.restore(db, backup)
            ReadSessionStorage.restore(db, listOf(backup[0].copy(
                endTime = backup[0].startTime + 1_000, readTime = 1_000)))
            assertEquals(backup, db.readSessionDao.all)
            assertEquals(30_000L, db.readRecordDao.allTime)
            db.readSessionDao.clear()
            ReadSessionStorage.restore(db, backup)
            assertEquals(backup, db.readSessionDao.all)
            assertEquals(30_000L, db.readRecordDao.allTime)
        }
    }

    private inline fun <T> AppDatabase.useDb(block: (AppDatabase) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}
