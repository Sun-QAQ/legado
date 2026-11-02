package io.legado.app.help.voice

sealed interface VoiceCommand {
    data class OpenBook(val name: String) : VoiceCommand
    /** App-created choice identity; never accepted from AI responses or speech text. */
    data class OpenShelfBook(val bookUrl: String) : VoiceCommand
    data class OpenOrdinal(val number: Int) : VoiceCommand
    data class SwitchGroup(val name: String) : VoiceCommand
    data class SwitchShelfGroup(val groupId: Long) : VoiceCommand
    data class OpenPage(val discovery: Boolean) : VoiceCommand
    data class TurnPage(val next: Boolean) : VoiceCommand
    data class EyePage(val enabled: Boolean) : VoiceCommand
    data object Cancel : VoiceCommand
}

/** Exact command grammar. Unknown or negated requests never execute a guessed action. */
object VoiceCommandParser {
    fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[\\s，。！？、,.!?《》“”\"：:]"), "")

    fun isNegated(text: String): Boolean = Regex(
        "^(?:你好小悦)?(?:请帮我|麻烦帮我|帮我|请)?(?:不要|别|不用|不需要|不想)"
    ).containsMatchIn(normalize(text))

    fun parse(text: String): VoiceCommand? {
        var value = normalize(text).removePrefix("你好小悦")
        for (prefix in listOf("请帮我", "麻烦帮我", "帮我", "请")) {
            if (value.startsWith(prefix)) { value = value.removePrefix(prefix); break }
        }
        value = value.removeSuffix("谢谢").removeSuffix("一下")
        // Compound requests and references need context; do not consume them as literal titles.
        if (Regex("然后|接着|[再并](?:打开|开启|关闭|切换|翻|进入)|(?:上次|昨天|刚才|正在)(?:看|读)|这本|那本").containsMatchIn(value)) return null
        return when (value) {
            "取消", "算了", "停止监听", "退出助手" -> VoiceCommand.Cancel
            "下一页", "往后翻", "翻到下一页", "向后翻页", "翻页" -> VoiceCommand.TurnPage(true)
            "上一页", "往前翻", "翻到上一页", "向前翻页" -> VoiceCommand.TurnPage(false)
            "打开发现", "进入发现", "切换到发现", "发现" -> VoiceCommand.OpenPage(true)
            "打开书架", "返回书架", "回到书架", "进入书架", "书架" -> VoiceCommand.OpenPage(false)
            "打开眼动翻页", "开启眼动翻页", "启用眼动翻页" -> VoiceCommand.EyePage(true)
            "关闭眼动翻页", "停用眼动翻页", "停止眼动翻页" -> VoiceCommand.EyePage(false)
            else -> parseTarget(value)
        }
    }

    private fun parseTarget(value: String): VoiceCommand? {
        Regex("(?:打开|阅读|看|我要看)?第([0-9零〇一二两三四五六七八九十百]+)本(?:书)?")
            .matchEntire(value)?.let {
                return number(it.groupValues[1])?.takeIf { n -> n in 1..999 }
                    ?.let(VoiceCommand::OpenOrdinal)
            }
        Regex("(?:切换到|切到|进入|打开)(.+)分组").matchEntire(value)?.let {
            return VoiceCommand.SwitchGroup(it.groupValues[1])
        }
        Regex("(?:切换到|切到)(.+)").matchEntire(value)?.let {
            return VoiceCommand.SwitchGroup(it.groupValues[1])
        }
        Regex("(?:打开|阅读|我要看|看)(.+)").matchEntire(value)?.let {
            return VoiceCommand.OpenBook(it.groupValues[1])
        }
        return null
    }

    fun choice(text: String): Int? {
        val value = normalize(text).removePrefix("选择").removePrefix("选")
            .removePrefix("第").removeSuffix("个").removeSuffix("本")
        return number(value)?.takeIf { it > 0 }
    }

    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        if (value.isEmpty()) return null
        val digits = "零一二三四五六七八九"
        val chinese = value.replace('两', '二').replace('〇', '零')
        if (!Regex("(?:[一二三四五六七八九]百(?:零[一二三四五六七八九]|[一二三四五六七八九]十[一二三四五六七八九]?)?|[一二三四五六七八九]?十[一二三四五六七八九]?|[零一二三四五六七八九])").matches(chinese)) return null
        var result = 0
        var digit = 0
        for (char in chinese) {
            when {
                char in digits -> digit = digits.indexOf(char)
                char == '十' || char == '百' -> {
                    result += (if (digit == 0) 1 else digit) * if (char == '十') 10 else 100
                    digit = 0
                }
                else -> return null
            }
        }
        return result + digit
    }
}

/** Do not silently pick the first version when several shelf entries share a title. */
object VoiceEntityMatcher {
    fun <T> match(query: String, items: List<T>, name: (T) -> String): List<T> {
        val key = VoiceCommandParser.normalize(query)
        if (key.isEmpty()) return emptyList()
        val exact = items.filter { VoiceCommandParser.normalize(name(it)) == key }
        return exact.ifEmpty {
            items.filter { VoiceCommandParser.normalize(name(it)).contains(key) }
        }
    }
}
