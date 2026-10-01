package io.legado.app.web.utils

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverResponseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun animatedGifRetainsBothFramesThroughProxyResponse() {
        val file = temporaryFolder.newFile("cover-cache")
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(file).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                for (color in listOf(Color.RED, Color.BLUE)) {
                    val frame = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
                    frame.createGraphics().let { graphics ->
                        graphics.color = color
                        graphics.fillRect(0, 0, 2, 2)
                        graphics.dispose()
                    }
                    writer.writeToSequence(IIOImage(frame, null, null), null)
                }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }

        CoverResponse.create(file).use { response ->
            assertEquals("image/gif", response.mimeType)
            val bytes = response.data.readBytes()
            assertArrayEquals(file.readBytes(), bytes)
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            try {
                ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                    reader.input = input
                    assertEquals(2, reader.getNumImages(true))
                    assertEquals(Color.RED.rgb, reader.read(0).getRGB(0, 0))
                    assertEquals(Color.BLUE.rgb, reader.read(1).getRGB(0, 0))
                }
            } finally {
                reader.dispose()
            }
        }
    }

    @Test
    fun staticPngUsesImageMimeTypeWithoutAnExtension() {
        val file = temporaryFolder.newFile("static-cover-cache")
        ImageIO.write(BufferedImage(280, 390, BufferedImage.TYPE_INT_RGB), "png", file)
        CoverResponse.create(file).use { response ->
            assertEquals("image/png", response.mimeType)
            assertArrayEquals(file.readBytes(), response.data.readBytes())
        }
    }
}
