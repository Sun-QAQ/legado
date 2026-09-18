package io.legado.app.ui.book.agent

import android.content.Context
import java.io.File
import java.util.UUID

internal object AgentImageStorage {

    fun save(context: Context, conversationId: String, bytes: ByteArray): String {
        val directory = File(root(context), conversationId).apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.img")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun deleteConversation(context: Context, conversationId: String) {
        val directory = File(root(context), conversationId)
        if (directory.parentFile == root(context)) directory.deleteRecursively()
    }

    fun deleteAll(context: Context) {
        root(context).deleteRecursively()
    }

    private fun root(context: Context) = File(context.filesDir, "ai_generated_images")
}
