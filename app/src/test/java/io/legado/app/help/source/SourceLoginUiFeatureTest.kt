package io.legado.app.help.source

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SourceLoginUiFeatureTest {
    class MemorySource : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = null
        override var loginUi: String? = null
        override var header: String? = null
        override var enabledCookieJar: Boolean? = true
        override var jsLib: String? = null
        private var variableValue = ""
        var savedLoginInfo: String? = null
        override fun getKey() = "login-ui-test"
        override fun getTag() = "登录 UI 测试"
        override fun getVariable() = variableValue
        override fun setVariable(variable: String?) { variableValue = variable.orEmpty() }
        override fun putLoginInfo(info: String): Boolean { savedLoginInfo = info; return true }
        override fun evalJS(jsStr: String, bindingsConfig: ScriptBindings.() -> Unit): Any? {
            val bindings = ScriptBindings().apply {
                put("source", this@MemorySource)
                put("java", this@MemorySource)
                apply(bindingsConfig)
            }
            return RhinoScriptEngine.eval("${jsLib.orEmpty()}\n$jsStr", bindings)
        }
    }

    @Test
    fun staticJsonRetainsLegacyFieldsWithoutExecutingScript() {
        val rows = SourceLoginUi.parse("""[{"name":"账号"},{"name":"密码","type":"password"},{"name":"登录","type":"button","action":"login()"}]""") {
            error("静态 JSON 不应执行脚本")
        }
        assertEquals(listOf("text", "password", "button"), rows.map { it.type })
        assertEquals("login()", rows[2].action)
    }

    @Test
    fun dynamicUiAcceptsBothMarkersAndStringOrNativeArrayResults() {
        val source = MemorySource().apply {
            loginUrl = "function buildUi() { return [{name:source.getTag(),type:'button'}]; }"
        }
        for (rule in listOf(
            " <js>buildUi()</js> ",
            "@js:JSON.stringify(buildUi())",
            "<JS>var rows=buildUi(); rows;</JS>"
        )) {
            source.loginUi = rule
            assertEquals("登录 UI 测试", source.loginUi()!!.single().name)
        }
    }

    @Test
    fun actionReceivesFormAndCanUpdateValuesAndRebuildPage() {
        val source = MemorySource().apply {
            loginUi = """<js>String(source.getVariable()) === 'next' ? [{name:'mode',viewName:"'显示模式'",type:'select',chars:['一','二'],default:'一',action:'save()'}] : [{name:'下一页',type:'button'}]</js>"""
            loginUrl = """function next(){source.putVariable('next');java.upLoginData({mode:'二',hidden:'保留'});java.reLoginView(false);} function save(){source.putVariable(result.get('mode'));}"""
        }
        val session = SourceLoginScript(source)
        assertEquals("button", session.readUi().single().type)
        session.runAction("next()")
        assertTrue(session.reloadRequested)
        assertNull(source.savedLoginInfo)
        val row = session.readUi().single()
        assertEquals("显示模式", row.viewName)
        assertEquals("二", session.data["mode"])
        assertEquals("保留", session.data["hidden"])
        session.runAction("save()")
        assertEquals("二", source.getVariable())
        session.runAction("java.reLoginView()")
        assertEquals("二", GSON.fromJson(source.savedLoginInfo, Map::class.java)["mode"])
    }

    @Test
    fun controlsUseDefaultsAndKeepIndependentDialogState() {
        val source = MemorySource().apply {
            loginUi = """[{"name":"enabled","type":"toggle","chars":["关闭","开启"],"default":"开启"}]"""
        }
        val first = SourceLoginScript(source)
        val second = SourceLoginScript(source)
        first.readUi()
        first.data["enabled"] = "关闭"
        first.readUi()
        second.readUi()
        assertEquals("关闭", first.data["enabled"])
        assertEquals("开启", second.data["enabled"])
    }

    @Test
    fun invalidResultsAndScriptFailuresPropagateInsteadOfBecomingEmptyUi() {
        for (rule in listOf("<js>throw new Error('failure')</js>", "@js:undefined", "@js:({rows:[]})", "[null]", "<js>[]", """[{"type":"select","name":"empty"}]""", """[{"type":"toggle","chars":["one"]}]""")) {
            assertThrows(Exception::class.java) { SourceLoginUi.parse(rule) { RhinoScriptEngine.eval(it) } }
        }
    }

    /** 仅本地验收：实际外部书源保持在已忽略目录，CI 不需要该附件。 */
    @Test
    fun localFixtureCanNavigateAndRenderSettingsWithoutNetwork() {
        val fixturePath = System.getenv("LOGIN_UI_SOURCE_FIXTURE")
        assumeTrue(fixturePath != null && File(fixturePath).isFile)
        val source = MemorySource().apply {
            jsLib = File(fixturePath!!).readText()
            loginUi = File(fixturePath).resolveSibling("0-loginUi.txt").readText()
            loginUrl = File(fixturePath).resolveSibling("0-loginUrl.txt").readText()
        }
        val session = SourceLoginScript(source)
        val menu = session.readUi()
        assertTrue(menu.size >= 2)
        // 只执行菜单导航；网络登录与清理会话按钮不在该离线验收中运行。
        session.runAction(menu[1].action!!)
        assertTrue(session.reloadRequested)
        val controls = session.readUi().filter { it.type == "select" || it.type == "toggle" }
        assertTrue(controls.isNotEmpty())
        assertTrue(controls.all { !it.viewName.isNullOrBlank() })
    }
}
