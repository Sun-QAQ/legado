package io.legado.app.help.source

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensions
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class NativeVideoPlaybackTest {
    class BrowserBridge(private val source: BookSource) : JsExtensions {
        override fun getSource() = source
    }

    @Test
    fun scriptBrowserCallIsCapturedWithoutLaunchingAnActivity() {
        val playback = NativeVideoPlayback("video-source")
        val bindings = ScriptBindings().apply {
            put("java", BrowserBridge(BookSource(bookSourceUrl = "video-source")))
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        val result = RhinoScriptEngine.compile(
            "java.startBrowser('https://media.example/episode.mp4?token=abc', 'Episode'); 'done'"
        ).eval(scope, Job() + playback)

        assertEquals("done", result)
        assertEquals("https://media.example/episode.mp4?token=abc", playback.playUrl)
    }

    @Test
    fun mediaBrowserCallsProvideTheNativePlayUrl() {
        val playback = NativeVideoPlayback("video-source")
        for (url in listOf(
            "https://media.example/episode.mp4?token=a%2Bb&quality=hd",
            "https://media.example/playlist.M3U8#episode",
            "https://media.example/episode.webm",
            "https://media.example/episode.mp4,{\"headers\":{\"Referer\":\"https://example.org\"}}"
        )) {
            assertTrue(url, playback.interceptBrowser("video-source", url))
            assertEquals(url, playback.playUrl)
        }
    }

    @Test
    fun ordinaryPagesAndVerificationLinksStillOpenInBrowser() {
        val playback = NativeVideoPlayback("video-source")
        for (url in listOf(
            "https://example.org/login",
            "https://example.org/challenge?next=episode.mp4",
            "https://example.org/watch/123",
            "javascript:play('https://media.example/episode.mp4')",
            "file:///episode.mp4"
        )) {
            assertFalse(url, playback.interceptBrowser("video-source", url))
        }
        assertNull(playback.playUrl)
    }

    @Test
    fun requestsFromOtherSourcesAreNotIntercepted() {
        val playback = NativeVideoPlayback("video-source")
        assertFalse(playback.interceptBrowser("other-source", "https://media.example/episode.mp4"))
        assertFalse(playback.interceptBrowser(null, "https://media.example/episode.mp4"))
        assertNull(playback.playUrl)
    }

    @Test
    fun playbackContextIsLimitedToItsOwnLoad() {
        val first = NativeVideoPlayback("video-source")
        val second = NativeVideoPlayback("video-source")
        val context = EmptyCoroutineContext + first
        assertEquals(first, context[NativeVideoPlayback])
        first.interceptBrowser("video-source", "https://media.example/episode.mp4")
        assertNull(second.playUrl)
        assertNull(EmptyCoroutineContext[NativeVideoPlayback])
    }
}
