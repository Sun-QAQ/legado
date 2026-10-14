package io.legado.app.ui.book.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okio.ByteString.Companion.toByteString

/** Copy only the selected document, with a bound even when its provider reports no size. */
internal object AgentAttachmentReader {
    fun read(context: Context, uri: Uri): PreparedAgentAttachment {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "attachment"
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            AgentAttachments.readLimited(stream)
        } ?: error(context.getString(io.legado.app.R.string.agent_attachment_read_failed))
        return AgentAttachments.prepare(listOf(
            AgentAttachmentUpload(name, "data:application/octet-stream;base64,${bytes.toByteString().base64()}")
        )).single()
    }
}
