package io.legado.app.web.utils

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest

object CoverResponse {

    fun create(file: File, ifNoneMatch: String? = null): NanoHTTPD.Response {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var size = stream.read(buffer)
            while (size != -1) {
                digest.update(buffer, 0, size)
                size = stream.read(buffer)
            }
        }
        val etag = "\"${digest.digest().joinToString("") { "%02x".format(it) }}\""
        val unchanged = ifNoneMatch?.split(',')?.any {
            val tag = it.trim().removePrefix("W/")
            tag == "*" || tag == etag
        } == true
        if (unchanged) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_MODIFIED,
                "application/octet-stream",
                null,
                0
            ).apply {
                addHeader("Cache-Control", "private, max-age=86400")
                addHeader("ETag", etag)
            }
        }
        // Glide 缓存文件没有图片扩展名，使用文件头判断类型。
        val mimeType = file.inputStream().buffered().use { stream ->
            stream.mark(16)
            val header = ByteArray(12)
            val headerSize = stream.read(header)
            stream.reset()
            if (headerSize == 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            ) {
                "image/webp"
            } else {
                URLConnection.guessContentTypeFromStream(stream)
                    ?: "application/octet-stream"
            }
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            file.inputStream(),
            file.length()
        ).apply {
            addHeader("Cache-Control", "private, max-age=86400")
            addHeader("ETag", etag)
        }
    }
}
