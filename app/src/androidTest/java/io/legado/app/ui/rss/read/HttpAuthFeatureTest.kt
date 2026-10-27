package io.legado.app.ui.rss.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 仅使用本机合成 HTTP 认证服务器，不创建订阅源或访问外部站点。 */
@RunWith(AndroidJUnit4::class)
class HttpAuthFeatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: AuthServer

    @Before
    fun startServer() {
        server = AuthServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun challengeShowsLoginAndCorrectCredentialsLoadPage() {
        launch().use { scenario ->
            awaitDialog()
            onView(withId(android.R.id.message)).check(
                matches(withText(instrumentation.targetContext.getString(
                    R.string.http_auth_message, "127.0.0.1"
                ) + "\n${server.realm}"))
            )
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "http-auth-dialog.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            submit("reader", "correct-password")
            awaitTitle(scenario, "Authenticated")
            assertTrue(server.authenticated.get() > 0)
            val requests = server.authenticated.get()
            scenario.onActivity { it.findViewById<WebView>(R.id.web_view).reload() }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (server.authenticated.get() <= requests && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue("Reload should reuse the authenticated session", server.authenticated.get() > requests)
            awaitTitle(scenario, "Authenticated")
        }
    }

    @Test
    fun wrongPasswordAllowsRetryWithClearedInputs() {
        launch().use { scenario ->
            awaitDialog()
            submit("reader", "wrong-password")
            awaitDialog()
            onView(withId(R.id.http_auth_username)).check(matches(withText("")))
            onView(withId(R.id.http_auth_password)).check(matches(withText("")))
            submit("reader", "correct-password")
            awaitTitle(scenario, "Authenticated")
            assertTrue(server.challenges.get() >= 2)
        }
    }

    @Test
    fun cancelButtonEndsAuthentication() {
        launch().use { scenario ->
            awaitDialog()
            onView(withId(android.R.id.button2)).perform(click())
            awaitTitle(scenario, "Unauthorized")
            assertTrue(server.authenticated.get() == 0)
        }
    }

    @Test
    fun backDismissesLoginAndEndsAuthentication() {
        launch().use { scenario ->
            awaitDialog()
            pressBack()
            awaitTitle(scenario, "Unauthorized")
            assertTrue(server.authenticated.get() == 0)
        }
    }

    @Test
    fun closingPageDismissesPendingLogin() {
        lateinit var dialogRoot: View
        launch().use {
            awaitDialog()
            onView(withId(R.id.http_auth_username)).check { view, error ->
                if (error != null) throw error
                dialogRoot = view.rootView
            }
        }
        instrumentation.waitForIdleSync()
        assertFalse(dialogRoot.isAttachedToWindow)
    }

    @Test
    fun navigatingAwayDismissesPendingLogin() {
        launch().use { scenario ->
            awaitDialog()
            lateinit var dialogRoot: View
            onView(withId(R.id.http_auth_username)).check { view, error ->
                if (error != null) throw error
                dialogRoot = view.rootView
            }
            scenario.onActivity {
                it.findViewById<WebView>(R.id.web_view)
                    .loadUrl("http://127.0.0.1:${server.listeningPort}/public")
            }
            awaitTitle(scenario, "Public page")
            assertFalse(dialogRoot.isAttachedToWindow)
        }
    }

    private fun launch(): ActivityScenario<ReadRssActivity> {
        val context = instrumentation.targetContext
        return ActivityScenario.launch(Intent(context, ReadRssActivity::class.java).apply {
            putExtra("origin", "http://127.0.0.1:${server.listeningPort}/")
            putExtra("title", "HTTP authentication test")
        })
    }

    private fun submit(username: String, password: String) {
        onView(withId(R.id.http_auth_username)).perform(replaceText(username))
        onView(withId(R.id.http_auth_password)).perform(replaceText(password))
        onView(withId(android.R.id.button1)).perform(click())
    }

    private fun awaitDialog() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            try {
                onView(withId(R.id.http_auth_username)).check(matches(isDisplayed()))
                return
            } catch (error: NoMatchingViewException) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
                SystemClock.sleep(50)
            }
        }
    }

    private fun awaitTitle(scenario: ActivityScenario<ReadRssActivity>, title: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var loaded = false
            scenario.onActivity { loaded = it.findViewById<WebView>(R.id.web_view).title == title }
            if (loaded) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Page did not load: $title")
    }

    private class AuthServer : NanoHTTPD("127.0.0.1", 0) {
        val realm = "http-auth-test-${UUID.randomUUID()}"
        val challenges = AtomicInteger()
        val authenticated = AtomicInteger()

        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/public") {
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                    "<html><head><title>Public page</title></head><body>Public</body></html>")
            }
            // 合成测试凭据 reader:correct-password。
            if (session.headers["authorization"] == "Basic cmVhZGVyOmNvcnJlY3QtcGFzc3dvcmQ=") {
                authenticated.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                    "<html><head><title>Authenticated</title></head><body>Signed in</body></html>")
            }
            challenges.incrementAndGet()
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html",
                "<html><head><title>Unauthorized</title></head><body>Unauthorized</body></html>")
                .apply { addHeader("WWW-Authenticate", "Basic realm=\"$realm\"") }
        }
    }
}
