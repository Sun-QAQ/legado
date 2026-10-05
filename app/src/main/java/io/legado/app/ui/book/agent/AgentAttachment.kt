package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class AgentAttachment(val name: String, val mimeType: String, val path: String, val size: Int)
data class AgentAttachmentUpload(val name: String, val dataUrl: String)
internal data class PreparedAgentAttachment(val name: String, val mimeType: String, val bytes: ByteArray)

internal object AgentAttachments {
    private const val IMAGE_LIMIT = 5 * 1024 * 1024
    private const val TEXT_LIMIT = 256 * 1024
    private val textExtensions = setOf("txt", "md", "csv", "json", "log", "xml", "html", "htm", "yaml", "yml", "js", "ts", "py", "kt", "java", "css", "sql")

    fun prepare(uploads: List<AgentAttachmentUpload>): List<PreparedAgentAttachment> {
        require(uploads.size <= 4) { "每条消息最多添加 4 个附件" }
        var total = 0
        return uploads.map { upload ->
            val name = upload.name.substringAfterLast('/').substringAfterLast('\\').take(180)
            require(name.isNotBlank()) { "附件文件名不能为空" }
            require(upload.dataUrl.startsWith("data:") && upload.dataUrl.substringBefore(',').endsWith(";base64")) { "附件编码无效" }
            val data = upload.dataUrl.substringAfter(',', "")
            require(data.length <= (IMAGE_LIMIT * 4 / 3 + 8)) { "单张图片不能超过 5 MB" }
            val bytes = data.decodeBase64()?.toByteArray() ?: error("附件 Base64 编码无效")
            require(bytes.isNotEmpty()) { "$name 是空文件" }
            val mime = when {
                bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
                bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
                bytes.size >= 6 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a" || String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a") -> "image/gif"
                bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> {
                    require(name.substringAfterLast('.', "").lowercase() in textExtensions) { "支持 PNG、JPEG、GIF、WebP 图片及 TXT、Markdown、CSV、JSON 等文本文件" }
                    require(bytes.size <= TEXT_LIMIT) { "文本文件不能超过 256 KB" }
                    val text = decodeText(bytes)
                    require(!text.contains('\u0000')) { "$name 不是有效的 UTF-8 文本文件" }
                    "text/plain"
                }
            }
            require(bytes.size <= IMAGE_LIMIT) { "单张图片不能超过 5 MB" }
            total += bytes.size
            require(total <= 10 * 1024 * 1024) { "附件总大小不能超过 10 MB" }
            PreparedAgentAttachment(name, mime, bytes)
        }
    }

    private fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) { error("文本文件需使用 UTF-8 编码") }

    /** 纯文本请求保持原格式；带图片时使用兼容 OpenAI 的 image_url 内容块。 */
    fun content(turn: AgentChatTurn): JsonElement {
        val attachments = turn.attachments.orEmpty()
        if (attachments.isEmpty()) return JsonPrimitive(turn.content)
        val text = buildString {
            append(turn.content)
            attachments.filter { it.mimeType == "text/plain" }.forEach {
                val file = File(it.path)
                check(file.exists()) { "附件 ${it.name} 已不存在，请重新上传" }
                append("\n\n[附件：${it.name}]\n")
                append(decodeText(file.readBytes()))
                append("\n[附件结束]")
            }
        }
        val images = attachments.filter { it.mimeType.startsWith("image/") }
        if (images.isEmpty()) return JsonPrimitive(text)
        return JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
            images.forEach {
                val file = File(it.path)
                check(file.exists()) { "图片附件 ${it.name} 已不存在，请重新上传" }
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:${it.mimeType};base64,${file.readBytes().toByteString().base64()}")
                    })
                })
            }
        }
    }
}
