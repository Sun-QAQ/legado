package io.legado.app.ui.rss.read

import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.ui.main.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 使用本机合成网页验证通用浏览器功能，不创建或导入实际订阅源。 */
@RunWith(AndroidJUnit4::class)
class RssWebTabsFeatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: PageServer
    private val url get() = "http://127.0.0.1:${server.listeningPort}/page"

    @Before fun startServer() {
        server = PageServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After fun stopServer() = server.stop()

    @Test fun minimizingAndReopeningPreservesDomScrollAndDoesNotRequestPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: WebView
            scenario.onActivity {
                it.rssTabs.open("tab-one", "Test page", url)
                original = web(it.rssTabs.activeTab!!)
                original.settings.javaScriptEnabled = true
            }
            awaitPage(scenario, "Test page")
            evaluate(scenario, "document.getElementById('entry').value='unsent';window.marker=42;window.scrollTo(0,700)")
            awaitCondition(scenario) { original.scrollY > 0 }
            var scroll = 0
            val requests = server.requests.get()
            scenario.onActivity {
                scroll = original.scrollY
                assertTrue(scroll > 0)
                it.rssTabs.activeTab!!.requireView()
                    .findViewById<io.legado.app.ui.widget.TitleBar>(R.id.title_bar)
                    .menu.performIdentifierAction(R.id.menu_rss_minimize, 0)
                assertNull(it.rssTabs.activeTab)
                assertEquals(View.GONE, it.findViewById<View>(R.id.rss_tab_container).visibility)
                assertEquals(1, it.rssTabs.tabs.size)
                assertTrue(it.rssTabs.resume("tab-one"))
                assertEquals(View.GONE, it.findViewById<View>(R.id.bottom_navigation_container).visibility)
                assertSame(original, web(it.rssTabs.activeTab!!))
                assertEquals(scroll, original.scrollY)
            }
            assertEquals("\"unsent:42\"", evaluate(scenario,
                "document.getElementById('entry').value+':'+window.marker"))
            assertEquals(requests, server.requests.get())
        }
    }

    @Test fun sourceIdentityReusesTabAndClosingOneKeepsTheOther() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var first: ReadRssFragment
            scenario.onActivity {
                it.rssTabs.open("tab-one", "First", url)
                first = it.rssTabs.activeTab!!
            }
            awaitPage(scenario, "Test page")
            scenario.onActivity {
                it.rssTabs.minimize()
                it.rssTabs.open("tab-one", "Changed URL", "$url?new-url")
                assertSame(first, it.rssTabs.activeTab)
                assertEquals(1, it.rssTabs.tabs.size)
                it.rssTabs.open("tab-two", "Second", "$url?second")
            }
            awaitPage(scenario, "Test page")
            scenario.onActivity {
                assertEquals(2, it.rssTabs.tabs.size)
                assertTrue(first.isHidden)
                assertNotNull(first.view)
                it.rssTabs.close("tab-one")
                assertNull(first.view)
                assertEquals("tab-two", it.rssTabs.activeTab?.sourceKey)
                it.rssTabs.close("tab-two")
                assertEquals(0, it.rssTabs.tabs.size)
                assertNull(it.rssTabs.activeTab)
            }
        }
    }

    @Test fun backNavigatesHistoryThenMinimizesAndTabListCanClosePage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.rssTabs.open("tab-one", "First", url) }
            awaitPage(scenario, "Test page")
            scenario.onActivity { web(it.rssTabs.activeTab!!).loadUrl("$url?next") }
            awaitCondition(scenario) {
                it.rssTabs.activeTab?.let { tab ->
                    tab.pageUrl == "$url?next" && web(tab).progress == 100 && web(tab).canGoBack()
                } == true
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitCondition(scenario) { it.rssTabs.activeTab?.pageUrl == url }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario.onActivity {
                assertNull(it.rssTabs.activeTab)
                assertEquals(1, it.rssTabs.tabs.size)
                it.showRssTabs()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val dialog = it.supportFragmentManager.findFragmentByTag("rss_tabs_dialog") as RssTabsDialog
                dialog.requireDialog().findViewById<View>(R.id.tab_close).performClick()
                assertEquals(0, it.rssTabs.tabs.size)
                assertNotNull(dialog.requireDialog().findViewById<View>(android.R.id.button1))
                dialog.dismiss()
            }
        }
    }

    @Test fun recreationRestoresTabsAndCurrentAddressWithoutLoadingHiddenTabs() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.rssTabs.open("tab-one", "First", url) }
            awaitPage(scenario, "Test page")
            scenario.onActivity { web(it.rssTabs.activeTab!!).loadUrl("$url?current") }
            awaitCondition(scenario) { it.rssTabs.activeTab?.pageUrl == "$url?current" }
            scenario.onActivity { it.rssTabs.open("tab-two", "Second", "$url?second") }
            awaitPage(scenario, "Test page")
            val before = server.requests.get()
            scenario.recreate()
            awaitPage(scenario, "Test page")
            scenario.onActivity {
                assertEquals(2, it.rssTabs.tabs.size)
                assertEquals("tab-two", it.rssTabs.activeTab?.sourceKey)
                assertEquals("$url?second", it.rssTabs.activeTab?.pageUrl)
                assertTrue(it.rssTabs.tabs.first { tab -> tab.sourceKey == "tab-one" }.isHidden)
            }
            assertEquals("Only the active tab should reload", before + 1, server.requests.get())
            scenario.onActivity { assertTrue(it.rssTabs.resume("tab-one")) }
            awaitPage(scenario, "Test page")
            scenario.onActivity { assertEquals("$url?current", it.rssTabs.activeTab?.pageUrl) }
            // Save a synthetic-page screenshot without exposing any user subscription content.
            SystemClock.sleep(400)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "rss-web-tab.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            scenario.onActivity { it.showRssTabs() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "rss-web-tabs-list.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag("rss_tabs_dialog") as RssTabsDialog).dismiss()
                it.rssTabs.minimize()
            }
            val requestsBeforeMinimizedRecreation = server.requests.get()
            scenario.recreate()
            scenario.onActivity {
                assertEquals(2, it.rssTabs.tabs.size)
                assertNull(it.rssTabs.activeTab)
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.bottom_navigation_container).visibility)
                assertEquals(requestsBeforeMinimizedRecreation, server.requests.get())
                assertTrue(it.rssTabs.resume("tab-one"))
            }
            awaitPage(scenario, "Test page")
            scenario.onActivity { assertEquals("$url?current", it.rssTabs.activeTab?.pageUrl) }
        }
    }

    private fun web(tab: ReadRssFragment): WebView = tab.requireView().findViewById(R.id.web_view)

    private fun awaitPage(scenario: ActivityScenario<MainActivity>, title: String) =
        awaitCondition(scenario) { it.rssTabs.activeTab?.let { tab -> web(tab).title == title && web(tab).progress == 100 } == true }

    private fun awaitCondition(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for web tab state")
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        scenario.onActivity { web(it.rssTabs.activeTab!!).evaluateJavascript(script) { value ->
            result = value
            latch.countDown()
        } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private class PageServer : NanoHTTPD("127.0.0.1", 0) {
        val requests = AtomicInteger()
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/page") requests.incrementAndGet()
            return newFixedLengthResponse(Response.Status.OK, "text/html", """
                <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Test page</title></head><body style="font-family:sans-serif;padding:20px">
                <h1>Web tab test</h1><input id="entry" placeholder="Draft text">
                <p>Minimize and return to continue.</p><div style="height:2400px"></div></body></html>
            """.trimIndent()).apply { addHeader("Cache-Control", "no-store") }
        }
    }
}
