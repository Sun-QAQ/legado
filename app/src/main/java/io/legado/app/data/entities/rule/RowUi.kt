package io.legado.app.data.entities.rule

import com.google.gson.annotations.SerializedName

data class RowUi(
    var name: String = "",
    var type: String = "text",
    var action: String? = null,
    var style: FlexChildStyle? = null,
    var viewName: String? = null,
    var chars: List<String>? = null,
    @SerializedName("default") var defaultValue: String? = null
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val select = "select"
        const val toggle = "toggle"

    }

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

}
