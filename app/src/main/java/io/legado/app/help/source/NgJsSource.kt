package io.legado.app.help.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.utils.GSON
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.Token
import org.mozilla.javascript.ast.*

/** NG 脚本保存在已有 jsLib 字段中，随 JSON 导出/备份，不增加数据库字段。 */
object NgJsSource {
    const val MARKER = "// legado:ng-js:v1\n"

    fun isNg(source: BaseSource?) = source?.jsLib?.startsWith(MARKER) == true

    fun parse(text: String): BookSource {
        val script = text.trimStart('\uFEFF').trim()
        require(script.length <= 2 * 1024 * 1024) { "NG JS 书源不能超过 2 MB" }
        val ast = Parser(CompilerEnvirons().apply {
            languageVersion = Context.VERSION_ES6
        }).parse(script, "NG JS", 1)
        val nodes = ast.statements
        val config = nodes.filterIsInstance<VariableDeclaration>()
            .flatMap { it.variables }
            .firstOrNull { (it.target as? Name)?.identifier == "config" }
            ?.initializer as? ObjectLiteral
            ?: error("NG JS 书源需要声明 config 对象")
        val functions = nodes.filterIsInstance<FunctionNode>().map { it.name }.toSet()
        for (name in listOf("search", "getBookInfo", "getChapters", "getContent")) {
            require(name in functions) { "NG JS 书源缺少 $name 函数" }
        }
        // 只读取字面量，导入预览不会执行脚本、登录或发起网络请求。
        val source = GSON.fromJson(literal(config), BookSource::class.java)
        require(!source.bookSourceUrl.isNullOrBlank() && !source.bookSourceName.isNullOrBlank()) {
            "NG JS config 缺少书源地址或名称"
        }
        source.jsLib = MARKER + script
        source.searchUrl = source.bookSourceUrl
        source.ruleSearch = SearchRule(
            bookList = "$[*]", name = "$.name", author = "$.author", bookUrl = "$.bookUrl",
            coverUrl = "$.coverUrl", intro = "$.intro", kind = "$.kind",
            wordCount = "$.wordCount", lastChapter = "$.latestChapterTitle"
        )
        source.ruleBookInfo = BookInfoRule(
            name = "$.name", author = "$.author", coverUrl = "$.coverUrl", intro = "$.intro",
            kind = "$.kind", wordCount = "$.wordCount", lastChapter = "$.latestChapterTitle",
            tocUrl = "$.tocUrl", canReName = "true"
        )
        source.ruleToc = TocRule(
            chapterList = "$[*]", chapterName = "$.title", chapterUrl = "$.url",
            isVolume = "$.isVolume", isVip = "$.isVip", isPay = "$.isPay", updateTime = "$.tag"
        )
        source.ruleContent = ContentRule(content = "@js:result")
        if ("loginUi" in functions) {
            source.loginUi = "[]" // 动态界面在打开登录页后求值。
            source.loginUrl = "@js:// NG JS loginAction"
        }
        source.enabledExplore = "explore" in functions && !source.exploreUrl.isNullOrBlank()
        return source
    }

    private fun literal(node: AstNode): JsonElement = when (node) {
        is StringLiteral -> JsonPrimitive(node.value)
        is NumberLiteral -> JsonPrimitive(node.number)
        is KeywordLiteral -> when (node.type) {
            Token.TRUE -> JsonPrimitive(true)
            Token.FALSE -> JsonPrimitive(false)
            Token.NULL -> JsonNull.INSTANCE
            else -> error("config 仅支持 JSON 字面量")
        }
        is UnaryExpression -> {
            require(node.operator == Token.NEG && node.operand is NumberLiteral) {
                "config 仅支持 JSON 字面量"
            }
            JsonPrimitive(-(node.operand as NumberLiteral).number)
        }
        is ArrayLiteral -> JsonArray().apply { node.elements.forEach { add(literal(it)) } }
        is ObjectLiteral -> JsonObject().apply {
            node.elements.forEach { property ->
                require(property.type == Token.COLON) { "config 不支持 getter 或方法" }
                val key = when (val left = property.left) {
                    is Name -> left.identifier
                    is StringLiteral -> left.value
                    else -> error("config 属性名格式错误")
                }
                add(key, literal(property.right))
            }
        }
        else -> error("config 仅支持 JSON 字面量，不能执行表达式")
    }
}
