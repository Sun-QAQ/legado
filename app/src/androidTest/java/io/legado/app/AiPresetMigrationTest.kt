package io.legado.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.AiPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPresetMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesPersonaAndSupportsPresetPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "ai-preset-migration-test"
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 83).apply {
            execSQL("INSERT INTO aiPersonas (id, name, prompt, lastUpdateTime) VALUES (101, '原人格', '原系统提示词', 1)")
            close()
        }
        try {
            withDatabase(dbName) { db ->
                assertEquals("原系统提示词", db.aiPersonaDao.get(101)?.prompt)
                assertEquals(emptyList<AiPreset>(), db.aiPresetDao.all)
                db.aiPresetDao.insert(AiPreset(201, "简洁", "简洁回答", 2))
            }
            withDatabase(dbName) { db ->
                assertEquals("简洁回答", db.aiPresetDao.get(201)?.prompt)
                db.aiPresetDao.insert(AiPreset(201, "简洁", "三句话回答", 3))
                assertEquals(1, db.aiPresetDao.all.size)
                assertEquals("三句话回答", db.aiPresetDao.get(201)?.prompt)
                db.aiPresetDao.delete(201)
                assertNull(db.aiPresetDao.get(201))
                assertEquals("原系统提示词", db.aiPersonaDao.get(101)?.prompt)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun withDatabase(name: String, block: (AppDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }
}
