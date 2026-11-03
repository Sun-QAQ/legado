package io.legado.app.ui.main.explore

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExploreWebViewTest {
    @Test
    fun rendersResizesAndOpensCategoriesWithoutNavigatingTheBridgedPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var web: ExploreWebView
            val opened = CountDownLatch(1)
            var openedUrl = ""
            var openedTitle = ""
            var readyCount = 0
            scenario.onActivity { activity ->
                web = ExploreWebView(activity, "https://example.org/", """
                    <!doctype html><html><head><style>body{margin:0}#catalog{height:320px}</style></head>
                    <body><div id="catalog"><button id="category" onclick="java.open('explore','https://example.org/list?page={{page}}','测试分类')">测试分类</button></div></body></html>
                """.trimIndent(), onContentReady = { readyCount++ }) { url, title ->
                    openedUrl = url
                    openedTitle = title
                    opened.countDown()
                }
                val container = FrameLayout(activity)
                container.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1))
                activity.setContentView(container)
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var sized = false
            while (!sized && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { sized = web.height >= (320 * web.resources.displayMetrics.density).toInt() }
                if (!sized) SystemClock.sleep(100)
            }
            assertTrue("网页应按内容高度展开", sized)
            assertEquals(1, readyCount)
            scenario.onActivity {
                web.evaluateJavascript("document.getElementById('category').click()", null)
            }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertEquals("https://example.org/list?page={{page}}", openedUrl)
            assertEquals("测试分类", openedTitle)

            val checked = CountDownLatch(1)
            scenario.onActivity {
                web.evaluateJavascript("""
                    java.open('browser', 'https://example.org/elsewhere', '不支持');
                    location.href='https://example.net/';
                    setTimeout(function(){window.stillHere=!!document.getElementById('category');},100);
                """.trimIndent(), null)
            }
            SystemClock.sleep(300)
            var stayed = false
            scenario.onActivity {
                web.evaluateJavascript("window.stillHere === true") {
                    stayed = it == "true"
                    checked.countDown()
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            assertTrue("导航应留在原始页面", stayed)
            assertEquals("测试分类", openedTitle)
            scenario.onActivity {
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                web.dispose()
                assertEquals(null, web.parent)
                web.dispose()
            }
        }
    }
}
