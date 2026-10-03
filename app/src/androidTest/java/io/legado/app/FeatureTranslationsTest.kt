package io.legado.app

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class FeatureTranslationsTest {
    @Test
    fun localizedFeaturesKeepPlaceholdersAndJsonExamples() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (language in listOf("en-US", "ja-JP", "es-ES", "pt-BR", "vi", "zh-CN")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = context.createConfigurationContext(configuration)
            if (language != "en-US") {
                assertFalse(language, localized.getString(R.string.ai_source_manage) == "AI suppliers")
            }
            val result = localized.getString(R.string.agent_ai_book_done, "TITLE", "AUTHOR", 3, 1234)
            for (value in listOf("TITLE", "AUTHOR", "3", "1234")) {
                assertTrue("$language: $result", result.contains(value))
            }
            assertFalse(language, result.contains("%"))
            assertTrue(language, localized.getString(R.string.ai_source_headers_hint)
                .contains("{\"Authorization\":\"...\"}"))
            assertEquals(language, 7, localized.resources.getStringArray(R.array.ai_thinking_efforts).size)
            if (language != "en-US") {
                assertFalse(language, localized.resources.getStringArray(R.array.ai_thinking_modes)[0] == "Model default")
            }
        }
    }
}
