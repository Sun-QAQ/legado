package io.legado.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateAllPreservesBooksProgressGroupsAndConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-50-current-${UUID.randomUUID()}"
        val bookUrl = "synthetic://migration-book"
        try {
            helper.createDatabase(databaseName, 50).use { db ->
                db.execSQL("INSERT INTO books (bookUrl, name, author, origin, type, `group`, durChapterIndex, durChapterPos, durChapterTitle, totalChapterNum, readConfig) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                    bookUrl, "迁移书籍", "测试作者", BookType.localTag, BookType.local or BookType.text,
                    128L, 7, 42, "第八章", 20, """{"reversePageSwipe":true,"pageAnim":3}"""
                ))
                db.execSQL("INSERT INTO book_groups (groupId, groupName, `order`, show) VALUES (128, '迁移分组', 1, 1)")
                db.execSQL("INSERT INTO replace_rules (id, name, pattern, replacement, isRegex, isEnabled) VALUES (701, '迁移配置', 'x', 'y', 0, 1)")
            }
            // Use the same explicit migrations as production; Room supplies its declared auto migrations.
            repeat(2) {
                val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                    .addMigrations(*DatabaseMigrations.migrations).build()
                try {
                    val book = db.bookDao.getBook(bookUrl)!!
                    assertEquals("迁移书籍", book.name)
                    assertEquals("测试作者", book.author)
                    assertEquals(128L, book.group)
                    assertEquals(7, book.durChapterIndex)
                    assertEquals(42, book.durChapterPos)
                    assertEquals("第八章", book.durChapterTitle)
                    assertEquals(20, book.totalChapterNum)
                    assertEquals(true, book.readConfig?.reversePageSwipe)
                    assertEquals(3, book.readConfig?.pageAnim)
                    db.openHelper.writableDatabase.query("SELECT groupName FROM book_groups WHERE groupId=128").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("迁移分组", cursor.getString(0))
                    }
                    db.openHelper.writableDatabase.query("SELECT name, pattern, replacement, isRegex, isEnabled FROM replace_rules WHERE id=701").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("迁移配置", cursor.getString(0))
                        assertEquals("x", cursor.getString(1))
                        assertEquals("y", cursor.getString(2))
                        assertEquals(0, cursor.getInt(3))
                        assertEquals(1, cursor.getInt(4))
                    }
                } finally {
                    db.close()
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
