package io.legado.app.help.source

import io.legado.app.data.entities.rule.RowUi
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/** 登录 UI 支持静态 JSON 和返回 JSON 字符串或原生数组的 JavaScript。 */
object SourceLoginUi {
    fun script(rule: String): String? {
        val text = rule.trim()
        return when {
            text.startsWith("@js:", true) -> text.substring(4)
            text.startsWith("<js>", true) -> {
                require(text.endsWith("</js>", true)) { "登录 UI 缺少 </js>" }
                text.substring(4, text.length - 5)
            }
            else -> null
        }
    }

    fun parse(rule: String, evaluate: (String) -> Any?): List<RowUi> {
        val script = script(rule)
        val json = if (script == null) rule else evaluate("""
            (function() {
                var ui = eval(${GSON.toJson(script)});
                return typeof ui === 'string' ? ui : JSON.stringify(ui);
            })()
        """.trimIndent())?.toString()
        val rows = GSON.fromJsonArray<RowUi>(json).getOrThrow()
        rows.forEach { row ->
            require(row.type in setOf("text", "password", "button", "select", "toggle")) {
                "登录 UI 不支持控件类型：${row.type}"
            }
            if (row.type == RowUi.Type.select || row.type == RowUi.Type.toggle) {
                require(!row.chars.isNullOrEmpty()) { "登录 UI 选项不能为空：${row.name}" }
                require(row.type != RowUi.Type.toggle || row.chars?.size == 2) {
                    "登录 UI 开关需要两个选项：${row.name}"
                }
            }
            row.viewName?.let { name ->
                row.viewName = runCatching { evaluate(name)?.toString() }.getOrNull() ?: name
            }
        }
        return rows
    }
}
