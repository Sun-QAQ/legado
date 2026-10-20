package io.legado.app.data.entities

import com.google.gson.JsonObject
import java.net.URI
import java.util.Locale

/** 每个供应商的每个模型独立保存思考选项；默认不改变服务端行为。 */
data class AiModelThinking(
    val mode: String = "default",
    val effort: String = "",
    val format: String = "auto"
) {
    fun applyTo(body: JsonObject, baseUrl: String, model: String) {
        if (mode == "default") return
        val enabled = mode == "enabled"
        val resolvedFormat = resolveFormat(baseUrl, model)
        when (resolvedFormat) {
            "thinking" -> body.add("thinking", JsonObject().apply {
                addProperty("type", if (enabled) "enabled" else "disabled")
            })
            "qwen" -> body.addProperty("enable_thinking", enabled)
        }
        if (!enabled) {
            if (resolvedFormat == "openai") body.addProperty("reasoning_effort", "none")
            return
        }
        if (resolvedFormat == "qwen") {
            val budget = when (effort) {
                "minimal" -> 512
                "low" -> 1024
                "medium" -> 4096
                "high" -> 8192
                "xhigh" -> 16384
                "max" -> 32768
                else -> null
            }
            budget?.let { body.addProperty("thinking_budget", it) }
        } else if (effort.isNotBlank()) {
            body.addProperty("reasoning_effort", effort)
        } else if (resolvedFormat == "openai") {
            body.addProperty("reasoning_effort", "medium")
        }
    }

    fun resolveFormat(baseUrl: String, model: String): String {
        if (format != "auto") return format
        val host = runCatching { URI(baseUrl).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        val modelName = model.lowercase(Locale.ROOT)
        return when {
            host.contains("dashscope") || host.contains("qwencloud") -> "qwen"
            host == "api.deepseek.com" || host.endsWith(".deepseek.com") -> "thinking"
            host.endsWith(".z.ai") || host == "open.bigmodel.cn" -> "thinking"
            host == "api.openai.com" || host == "opencode.ai" -> "openai"
            modelName.startsWith("qwen") -> "qwen"
            modelName.startsWith("deepseek") || modelName.startsWith("glm") -> "thinking"
            else -> "openai"
        }
    }
}
