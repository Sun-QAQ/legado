package io.legado.app.model.webBook

import com.script.ScriptBindings
import com.script.CompiledScript
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.NgJsSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import kotlin.coroutines.coroutineContext

/** 每次调用拥有独立全局作用域，避免多个书籍/并发请求之间污染 config 和临时变量。 */
object NgJsRuntime {
    private val compiled = object : LinkedHashMap<String, CompiledScript>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompiledScript>?) = size > 8
    }

    @Synchronized
    private fun compile(script: String): CompiledScript =
        compiled.getOrPut(script) { RhinoScriptEngine.compile(script) }

    private val cryptoJs by lazy {
        appCtx.assets.open("js/crypto-js.js").bufferedReader().use { it.readText() }
    }

    suspend fun call(source: BookSource, function: String, vararg args: Any?): String {
        require(function.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")))
        val arguments = GSON.toJson(args)
        return evaluate(source, """
            (function() {
                if (typeof $function !== 'function') throw new Error('NG JS 缺少 $function 函数');
                var value = $function.apply(this, $arguments);
                if (value && typeof value.then === 'function') throw new Error('暂不支持异步 NG JS 函数');
                return JSON.stringify(value == null ? null : value);
            }).call(this)
        """.trimIndent())
    }

    suspend fun evaluate(source: BookSource, expression: String): String = withTimeout(60_000) {
        require(NgJsSource.isNg(source)) { "不是 NG JS 书源" }
        val bindings = ScriptBindings().apply {
            put("source", source)
            put("java", NgJsBridge(source))
            put("cookie", CookieStore)
            put("cache", CacheManager)
            put("baseUrl", source.bookSourceUrl)
        }
        evaluateScript(source.jsLib!!.removePrefix(NgJsSource.MARKER), expression, bindings, cryptoJs)
    }

    internal suspend fun evaluateScript(
        script: String, expression: String, bindings: ScriptBindings, library: String
    ): String {
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        compile(library).eval(scope, coroutineContext)
        compile(script).eval(scope, coroutineContext)
        return RhinoScriptEngine.eval(expression, scope, coroutineContext)?.toString() ?: "null"
    }
}
