package io.legado.app.ui.login

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.switchmaterial.SwitchMaterial
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 仅使用合成表单，不包含站点规则，也不请求网络。 */
@RunWith(AndroidJUnit4::class)
class SourceLoginUiFeatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** 可选本地验收入口；目标仅通过运行参数指定，不打包外部书源。 */
    @Test
    fun installedSourceCanRenderAndNavigate() {
        val arguments = InstrumentationRegistry.getArguments()
        val key = arguments.getString("loginSourceKey")
        val navigation = arguments.getString("loginNavigationLabel")
        assumeTrue(!key.isNullOrBlank() && !navigation.isNullOrBlank())
        val source = requireNotNull(appDb.bookSourceDao.getBookSource(key!!))
        val previousVariable = source.getVariable()
        val intent = Intent(instrumentation.targetContext, SourceLoginActivity::class.java)
            .putExtra("type", "bookSource").putExtra("key", key)
        try {
            ActivityScenario.launch<SourceLoginActivity>(intent).use { scenario ->
                await(scenario) { views(it).filterIsInstance<TextView>().any { v -> v.text.startsWith(navigation!!) && v.isEnabled } }
                screenshot("installed-source-login-menu.png")
                onDialog(scenario) {
                    views(it).filterIsInstance<TextView>().first { v -> v.text.startsWith(navigation!!) }.performClick()
                }
                await(scenario) { views(it).filterIsInstance<Spinner>().any { v -> v.isEnabled } }
                screenshot("installed-source-login-settings.png")
            }
        } finally {
            source.setVariable(previousVariable)
        }
    }

    @Test
    fun dynamicMenuSelectAndToggleDispatchValuesAndRebuildWithoutInitialCallbacks() {
        withSource(BookSource(
            loginUi = "<js>buildForm(source)</js>",
            jsLib = """
                function buildForm(src) {
                    if(String(src.getVariable()) !== 'controls') return [{name:'打开设置',type:'button',action:'openPage()'}];
                    return [
                        {name:'choice',viewName:"'显示模式'",type:'select',chars:['A','B'],default:'A',action:'saveChoice()'},
                        {name:'enabled',viewName:"'功能开关'",type:'toggle',chars:['关闭','开启'],default:'开启',action:'saveToggle()'},
                        {name:'重置表单',type:'button',action:'resetForm()'}
                    ];
                }
            """.trimIndent(),
            loginUrl = """
                function login() {}
                function openPage(){source.putVariable('controls');java.reLoginView(false);}
                function saveChoice(){source.put('choice',result.get('choice'));}
                function saveToggle(){source.put('enabled',result.get('enabled'));}
                function resetForm(){java.upLoginData({choice:'A',enabled:'开启'});java.reLoginView(false);}
            """.trimIndent()
        )) { source, scenario ->
            await(scenario) { views(it).filterIsInstance<TextView>().any { v -> v.text == "打开设置" } }
            onDialog(scenario) { views(it).filterIsInstance<TextView>().first { v -> v.text == "打开设置" }.performClick() }
            await(scenario) { views(it).filterIsInstance<Spinner>().any { v -> v.isEnabled } }
            instrumentation.waitForIdleSync()
            assertEquals("", source.get("choice"))
            assertEquals("", source.get("enabled"))
            onDialog(scenario) {
                assertTrue(views(it).filterIsInstance<SwitchMaterial>().single().isChecked)
                views(it).filterIsInstance<Spinner>().single().setSelection(1)
            }
            await(scenario) { source.get("choice") == "B" && views(it).filterIsInstance<Spinner>().single().isEnabled }
            onDialog(scenario) { views(it).filterIsInstance<SwitchMaterial>().single().performClick() }
            await(scenario) { source.get("enabled") == "关闭" && views(it).filterIsInstance<Spinner>().single().isEnabled }
            onDialog(scenario) { views(it).filterIsInstance<TextView>().first { v -> v.text == "重置表单" }.performClick() }
            await(scenario) {
                views(it).filterIsInstance<Spinner>().single().selectedItem == "A" &&
                    views(it).filterIsInstance<SwitchMaterial>().single().isChecked &&
                    views(it).filterIsInstance<Spinner>().single().isEnabled
            }
            assertEquals("B", source.get("choice"))
            assertEquals("关闭", source.get("enabled"))
            screenshot("source-login-controls.png")
        }
    }

    @Test
    fun legacyTextFormStillSubmitsCurrentInput() {
        withSource(BookSource(
            loginUi = """[{"name":"account","type":"text"},{"name":"password","type":"password"}]""",
            loginUrl = "function login(){source.put('submitted',result.get('account'));}"
        )) { source, scenario ->
            await(scenario) { views(it).filterIsInstance<EditText>().size == 2 && it.findViewById<View>(R.id.flexbox).isEnabled }
            onDialog(scenario) { root ->
                views(root).filterIsInstance<EditText>()[0].setText("test-user")
                views(root).filterIsInstance<EditText>()[1].setText("test-password")
                root.findViewById<androidx.appcompat.widget.Toolbar>(R.id.tool_bar).menu.performIdentifierAction(R.id.menu_ok, 0)
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (source.get("submitted") != "test-user" && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals("test-user", source.get("submitted"))
            assertEquals("test-user", source.getLoginInfoMap()!!["account"])
        }
    }

    @Test
    fun brokenScriptShowsErrorAndPreventsEmptySubmit() {
        withSource(BookSource(loginUi = "<js>throw new Error('synthetic-ui-error')</js>")) { _, scenario ->
            await(scenario) { views(it).filterIsInstance<TextView>().any { v -> v.text.contains("synthetic-ui-error") } }
            onDialog(scenario) {
                assertFalse(it.findViewById<androidx.appcompat.widget.Toolbar>(R.id.tool_bar).menu.findItem(R.id.menu_ok).isEnabled)
            }
            screenshot("source-login-error.png")
        }
    }

    private fun withSource(source: BookSource, test: (BookSource, ActivityScenario<SourceLoginActivity>) -> Unit) {
        source.bookSourceUrl = "test://login-ui/${UUID.randomUUID()}"
        source.bookSourceName = "登录界面回归测试"
        appDb.bookSourceDao.insert(source)
        try {
            val intent = Intent(instrumentation.targetContext, SourceLoginActivity::class.java)
                .putExtra("type", "bookSource").putExtra("key", source.bookSourceUrl)
            ActivityScenario.launch<SourceLoginActivity>(intent).use { test(source, it) }
        } finally {
            appDb.bookSourceDao.delete(source)
            source.removeLoginInfo()
            source.setVariable(null)
            for (key in listOf("choice", "enabled", "submitted")) CacheManager.delete("v_${source.getKey()}_$key")
        }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun onDialog(scenario: ActivityScenario<SourceLoginActivity>, action: (View) -> Unit) {
        scenario.onActivity { activity ->
            action(activity.supportFragmentManager.fragments.filterIsInstance<SourceLoginDialog>().single().requireView())
        }
    }

    private fun await(scenario: ActivityScenario<SourceLoginActivity>, predicate: (View) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments.filterIsInstance<SourceLoginDialog>()
                    .firstOrNull()?.view?.let { ready = predicate(it) }
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("登录界面未在预期时间内更新")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.externalCacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
