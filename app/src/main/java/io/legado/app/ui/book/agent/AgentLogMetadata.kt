package io.legado.app.ui.book.agent

import com.google.gson.JsonObject

/** AI 请求日志只保留诊断所需的元数据，绝不写入请求或响应正文。 */
internal object AgentLogMetadata {

    fun format(
        model: String,
        body: JsonObject,
        elapsedMs: Long,
        httpCode: Int?
    ): String {
        val toolNames = body.getAsJsonArray("messages")
            ?.mapNotNull { message ->
                message.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.getAsJsonArray("tool_calls")
                    ?.mapNotNull { call ->
                        call.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.getAsJsonObject("function")
                            ?.get("name")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                    }
            }
            ?.flatten()
            ?.distinct()
            ?.joinToString(",")
            ?.ifBlank { "none" }
            ?: "none"
        val errorCode = httpCode?.toString() ?: "NETWORK"
        return "Agent 请求 model=$model requestChars=${body.toString().length} " +
            "tools=$toolNames errorCode=$errorCode elapsed=${elapsedMs}ms"
    }
}
