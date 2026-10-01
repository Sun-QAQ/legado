package io.legado.app.web.utils

import com.bumptech.glide.gifdecoder.GifHeaderParser
import fi.iki.elonen.NanoHTTPD
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverResponseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unchangedCoverReturnsNotModifiedWithoutImageData() {
        val file = temporaryFolder.newFile("cached-cover")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val etag = CoverResponse.create(file).use { response ->
            assertEquals("private, max-age=86400", response.getHeader("Cache-Control"))
            response.getHeader("ETag")
        }
        CoverResponse.create(file, "\"another-cover\", W/$etag").use { response ->
            assertEquals(NanoHTTPD.Response.Status.NOT_MODIFIED, response.status)
            assertEquals(etag, response.getHeader("ETag"))
            assertEquals("private, max-age=86400", response.getHeader("Cache-Control"))
            assertEquals(0, response.data.readBytes().size)
        }
    }

    @Test
    fun changedCoverReturnsNewDataEvenWithSameSizeAndTimestamp() {
        val file = temporaryFolder.newFile("changed-cover")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val originalTime = file.lastModified()
        val etag = CoverResponse.create(file).use { it.getHeader("ETag") }
        file.writeBytes(byteArrayOf(4, 5, 6))
        file.setLastModified(originalTime)
        CoverResponse.create(file, etag).use { response ->
            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertNotEquals(etag, response.getHeader("ETag"))
            assertArrayEquals(byteArrayOf(4, 5, 6), response.data.readBytes())
        }
    }

    @Test
    fun animatedGifRetainsBothFramesThroughProxyResponse() {
        // 2×2 GIF：第一帧为红色，第二帧为蓝色。
        val bytes = Base64.getDecoder().decode(
            "R0lGODlhAgACAPAAAP8AAP8AACwAAAAAAgACAEAIBgABCAQQEAAsAAAAAAIAAgDAAAD/AAD/CAYAAQgEEBAAOw=="
        )
        val file = temporaryFolder.newFile("cover-cache")
        file.writeBytes(bytes)
        CoverResponse.create(file).use { response ->
            assertEquals("image/gif", response.mimeType)
            val received = response.data.readBytes()
            assertArrayEquals(bytes, received)
            val header = GifHeaderParser().setData(received).parseHeader()
            assertEquals(0, header.status)
            assertEquals(2, header.numFrames)
        }
    }

    @Test
    fun staticPngUsesImageMimeTypeWithoutAnExtension() {
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMxkAAAAASUVORK5CYII="
        )
        val file = temporaryFolder.newFile("static-cover-cache")
        file.writeBytes(bytes)
        CoverResponse.create(file).use { response ->
            assertEquals("image/png", response.mimeType)
            assertArrayEquals(bytes, response.data.readBytes())
        }
    }
}
