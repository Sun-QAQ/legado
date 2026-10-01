package io.legado.app.web.utils

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URLConnection

object CoverResponse {

    fun create(file: File): NanoHTTPD.Response {
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
        )
    }
}
