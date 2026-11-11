package io.legado.app.help.source

import androidx.annotation.Keep
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.JsExtensions
import io.legado.app.utils.GSON

/** 每个登录窗口独立持有表单和刷新请求，脚本不持有 Activity 或 View。 */
@Keep
class SourceLoginScript(private val source: BaseSource) : JsExtensions by source {
    val data = hashMapOf<String, String>()
    var reloadRequested = false
        private set

    fun readUi(): List<RowUi> {
        reloadRequested = false
        return source.loginUi {
            put("java", this@SourceLoginScript)
            put("result", data)
        }.orEmpty().onEach { row ->
            if (row.type != RowUi.Type.button) {
                data.putIfAbsent(row.name, row.defaultValue ?: row.chars?.firstOrNull().orEmpty())
            }
        }
    }

    fun runAction(action: String) {
        source.evalJS("${source.getLoginJs().orEmpty()}\n$action") {
            put("java", this@SourceLoginScript)
            put("result", data)
        }
    }

    @JvmOverloads
    fun reLoginView(saveLoginData: Boolean = true) {
        if (saveLoginData) source.putLoginInfo(GSON.toJson(data))
        reloadRequested = true
    }

    fun upLoginData(values: Map<*, *>) {
        values.forEach { (key, value) ->
            if (key != null && value != null) data[key.toString()] = value.toString()
        }
    }
}
