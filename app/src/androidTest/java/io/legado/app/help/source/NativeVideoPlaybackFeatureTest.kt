package io.legado.app.help.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.exception.ContentEmptyException
import io.legado.app.model.webBook.BookContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVideoPlaybackFeatureTest {
    private val sourceKey = "native-video-fixture"
    private val mediaUrl = "https://media.example/episode.mp4?token=fixture"

    @Test
    fun capturedMediaSurvivesEmptyContentAndTextReplacement() = runBlocking {
        val playback = NativeVideoPlayback(sourceKey)
        val content = withContext(playback) {
            analyze(ContentRule(
                content = "@js:java.startBrowser('$mediaUrl', 'Episode'); ''",
                replaceRegex = "##.*##",
            ))
        }
        assertEquals(mediaUrl, playback.playUrl)
        assertEquals(mediaUrl, content)
    }

    @Test
    fun emptyContentWithoutCapturedMediaStillFails() = runBlocking {
        val playback = NativeVideoPlayback(sourceKey)
        try {
            withContext(playback) { analyze(ContentRule(content = "@js:''")) }
            fail("Empty content without a captured media URL must fail")
        } catch (_: ContentEmptyException) {
            assertNull(playback.playUrl)
        }
    }

    @Test
    fun ordinaryContentStillUsesTextReplacement() = runBlocking {
        val playback = NativeVideoPlayback(sourceKey)
        val content = withContext(playback) {
            analyze(ContentRule(content = "@js:'before'", replaceRegex = "##before##after"))
        }
        assertNull(playback.playUrl)
        assertEquals("after", content.trim())
    }

    private suspend fun analyze(rule: ContentRule): String {
        val book = Book(bookUrl = "https://example.invalid/book", name = "Video fixture")
        val chapter = BookChapter(
            bookUrl = book.bookUrl,
            url = "https://example.invalid/chapter",
            title = "Episode",
        )
        return BookContent.analyzeContent(
            bookSource = BookSource(bookSourceUrl = sourceKey, ruleContent = rule),
            book = book,
            bookChapter = chapter,
            baseUrl = chapter.url,
            redirectUrl = chapter.url,
            body = "fixture",
            nextChapterUrl = "https://example.invalid/next",
            needSave = false,
        )
    }
}
