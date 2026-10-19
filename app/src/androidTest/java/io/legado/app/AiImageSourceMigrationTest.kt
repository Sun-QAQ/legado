package io.legado.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.AiImageSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiImageSourceMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationPreservesOpenAiSourcesAndPersistsComfyUiWorkflow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "ai-image-source-migration-test"
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 84).apply {
            execSQL("""INSERT INTO aiImageSources
                (id, name, baseUrl, apiKey, model, imageSize, responseFormat, customBody, headers, enabled, lastUpdateTime)
                VALUES (1, '原生图配置', 'https://images.test/v1', 'key', 'image-model', '768x768', 'auto', '{"n":1}', NULL, 1, 123)""")
            close()
        }
        try {
            withDatabase(dbName) { db ->
                val existing = requireNotNull(db.aiImageSourceDao.get(1))
                assertEquals(AiImageSource.TYPE_OPENAI, existing.apiType)
                assertEquals("image-model", existing.model)
                assertEquals("key", existing.apiKey)
                assertEquals("""{"n":1}""", existing.customBody)
                db.aiImageSourceDao.insert(AiImageSource(id = 2, name = "ComfyUI",
                    apiType = AiImageSource.TYPE_COMFYUI, customBody = """{"prompt":{}}"""))
            }
            withDatabase(dbName) { db ->
                val comfy = requireNotNull(db.aiImageSourceDao.get(2))
                assertEquals(AiImageSource.TYPE_COMFYUI, comfy.apiType)
                assertEquals("""{"prompt":{}}""", comfy.customBody)
                assertEquals(2, db.aiImageSourceDao.all.size)
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
