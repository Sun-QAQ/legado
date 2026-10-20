package io.legado.app.ui.book.agent

import com.google.gson.JsonElement
import java.util.Locale

/** 保守估算，不依赖某个供应商的 tokenizer；真实超限由错误恢复路径兜底。 */
internal object AgentContextBudget {
    fun textTokens(text: String): Long {
        val ascii = text.count { it.code < 128 }.toLong()
        return (ascii + 2) / 3 + (text.length - ascii) * 2
    }

    fun tokens(value: JsonElement): Long = when {
        value.isJsonNull -> 1
        value.isJsonArray -> 4 + value.asJsonArray.sumOf { tokens(it) + 4 }
        value.isJsonObject -> {
            val obj = value.asJsonObject
            if (obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "image_url") {
                8192 // 不把图片的 Base64 长度当作文本 token。
            } else {
                8 + obj.entrySet().sumOf { textTokens(it.key) + tokens(it.value) + 4 }
            }
        }
        else -> textTokens(value.asString)
    }

    fun inputLimit(window: Int): Long = (window - minOf(8192, window / 4)).toLong() * 9 / 10

    /** 只识别结构化代码或明确的上下文超限描述，不向界面或日志泄露响应正文。 */
    fun isContextOverflow(status: Int, body: String?): Boolean {
        if (status !in setOf(400, 413, 422)) return false
        val text = body.orEmpty().take(65536).lowercase(Locale.ROOT)
        return listOf(
            "context_length_exceeded", "context_window_exceeded", "max_context_length",
            "maximum context length", "exceeds the context window", "context window exceeded",
            "context length exceeded", "context length is exceeded", "input is too long",
            "prompt is too long", "too many tokens", "exceeds the maximum number of tokens",
            "上下文长度超", "超出上下文", "上下文超限"
        ).any { it in text }
    }
}

internal class AgentContextOverflowException : Exception("上下文超过模型容量，请检查模型容量设置，或减少本轮输入和附件。")
