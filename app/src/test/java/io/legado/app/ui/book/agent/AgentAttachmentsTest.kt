package io.legado.app.ui.book.agent

import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream

class AgentAttachmentsTest {
    private fun upload(name: String, bytes: ByteArray) = AgentAttachmentUpload(name, "data:application/octet-stream;base64,${bytes.toByteString().base64()}")

    @Test
    fun selectedFilesAreReadWithAHardLimitEvenWithoutProviderSize() {
        val limit = 5 * 1024 * 1024
        val bytes = ByteArray(limit) { 65 }
        assertArrayEquals(bytes, AgentAttachments.readLimited(ByteArrayInputStream(bytes)))
        assertTrue(runCatching {
            AgentAttachments.readLimited(ByteArrayInputStream(ByteArray(limit + 1)))
        }.isFailure)
    }

    @Test
    fun accumulatedDraftRejectsTooManyFilesAndExcessTotalSize() {
        val attachment = PreparedAgentAttachment("photo.jpg", "image/jpeg", ByteArray(3 * 1024 * 1024))
        AgentAttachments.validateDraft(List(3) { attachment })
        assertTrue(runCatching { AgentAttachments.validateDraft(List(4) { attachment }) }.isFailure)
        val text = PreparedAgentAttachment("note.txt", "text/plain", byteArrayOf(65))
        assertTrue(runCatching { AgentAttachments.validateDraft(List(5) { text }) }.isFailure)
    }

    @Test
    fun detectsImageFromBytesRatherThanDeclaredMime() {
        val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
        val prepared = AgentAttachments.prepare(listOf(upload("../测试.jpg", jpeg))).single()
        assertEquals("image/jpeg", prepared.mimeType)
        assertEquals("测试.jpg", prepared.name)
        assertArrayEquals(jpeg, prepared.bytes)
    }

    @Test
    fun rejectsBinaryInvalidEncodingAndTooManyFiles() {
        val invalid = listOf(
            listOf(upload("document.pdf", "%PDF".toByteArray())),
            listOf(upload("binary.txt", byteArrayOf(0, 1, 2))),
            listOf(upload("invalid.txt", byteArrayOf(-1))),
            List(5) { upload("test.txt", "test".toByteArray()) },
            listOf(upload("huge.txt", ByteArray(256 * 1024 + 1) { 65 }))
        )
        invalid.forEach { assertTrue(runCatching { AgentAttachments.prepare(it) }.isFailure) }
    }

    @Test
    fun textFilesBecomeModelContextAndImagesBecomeImageUrlParts() {
        val text = File.createTempFile("agent-text", ".txt")
        val image = File.createTempFile("agent-image", ".img")
        try {
            text.writeText("这是文件里的内容。")
            image.writeBytes(byteArrayOf(-1, -40, -1, 1))
            val turn = AgentChatTurn("user", "请分析", attachments = listOf(
                AgentAttachment("notes.txt", "text/plain", text.path, text.length().toInt()),
                AgentAttachment("photo.jpg", "image/jpeg", image.path, 4)
            ))
            val content = AgentAttachments.content(turn).asJsonArray
            assertTrue(content[0].asJsonObject["text"].asString.contains("这是文件里的内容。"))
            assertTrue(content[1].asJsonObject["image_url"].asJsonObject["url"].asString.startsWith("data:image/jpeg;base64,"))
            val restored = AgentConversationCodec.decodeTurns(AgentConversationCodec.encodeTurns(listOf(turn))).single()
            assertEquals(turn, restored)
        } finally { text.delete(); image.delete() }
    }

    @Test
    fun oldConversationsRemainPlainTextAndHaveNoAttachments() {
        val turn = AgentConversationCodec.decodeTurns("[{\"role\":\"user\",\"content\":\"旧消息\"}]").single()
        assertEquals("旧消息", AgentAttachments.content(turn).asString)
        assertTrue(AgentConversationCodec.decodeMessages("[{\"isUser\":true,\"text\":\"旧消息\"}]").single().attachments.isEmpty())
    }

    @Test
    fun missingAttachmentFailsExplicitlyRatherThanSilentlyOmittingIt() {
        val missing = File(System.getProperty("java.io.tmpdir"), "missing-agent-${System.nanoTime()}.txt")
        val turn = AgentChatTurn("user", "分析", attachments = listOf(AgentAttachment("note.txt", "text/plain", missing.path, 1)))
        assertTrue(runCatching { AgentAttachments.content(turn) }.exceptionOrNull()?.message.orEmpty().contains("重新上传"))
    }
}
