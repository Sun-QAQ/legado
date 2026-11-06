package io.legado.app.model.localBook

import android.system.Os
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import me.ag2s.epublib.domain.LazyResource
import me.ag2s.epublib.domain.LazyResourceProvider
import org.junit.Assert.*
import org.junit.Test
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubLifecycleFeatureTest {
    private val image = ByteArray(12000) { (it % 251).toByte() }

    private fun fixture(directory: File, name: String): Book {
        val file = File(directory, "$name.epub")
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Lifecycle</dc:title><dc:creator>Test</dc:creator><dc:identifier id="uid">test</dc:identifier><dc:language>zh</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="picture" href="picture.png" media-type="image/png"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="chapter"/></spine></package>""",
            "OEBPS/toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Lifecycle</text></docTitle><navMap><navPoint id="one" playOrder="1"><navLabel><text>Chapter</text></navLabel><content src="chapter.xhtml"/></navPoint></navMap></ncx>""",
            "OEBPS/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><p>正文<a epub:type="noteref" href="notes.xhtml#n1">1</a><a epub:type="noteref" href="notes.xhtml#n2">2</a></p></body></html>""",
            "OEBPS/notes.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><aside epub:type="footnote" id="n1">第一条注释</aside><aside epub:type="footnote" id="n2">第二条注释</aside></body></html>"""
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("OEBPS/picture.png")); zip.write(image); zip.closeEntry()
        }
        val cover = File(directory, "$name.jpg").apply { writeBytes(byteArrayOf()) }
        return Book(bookUrl = file.absolutePath, originName = file.name, coverUrl = cover.absolutePath, type = BookType.local or BookType.text)
    }

    private fun descriptorCount(file: String) = File("/proc/self/fd").listFiles().orEmpty().count {
        runCatching { File(Os.readlink(it.absolutePath)).canonicalPath }.getOrNull() == File(file).canonicalPath
    }

    @Test fun imagesSurviveSwitchingAndFilesCloseWithoutGarbageCollection() {
        val directory = File(appCtx.cacheDir, "epub-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        var imageStream: InputStream? = null
        try {
            val first = fixture(directory, "first")
            val second = fixture(directory, "second")
            imageStream = EpubFile.getImage(first, "OEBPS/picture.png")!!
            assertEquals(1, descriptorCount(first.bookUrl))
            EpubFile.getChapterList(second)
            EpubFile.clear()
            assertEquals(1, descriptorCount(first.bookUrl))
            assertEquals(0, descriptorCount(second.bookUrl))
            assertArrayEquals(image, imageStream.readBytes())
            imageStream.close()
            assertEquals(0, descriptorCount(first.bookUrl))
            repeat(20) {
                EpubFile.getChapterList(if (it % 2 == 0) first else second)
                EpubFile.clear()
            }
            assertEquals(0, descriptorCount(first.bookUrl) + descriptorCount(second.bookUrl))
        } finally {
            imageStream?.close()
            EpubFile.clear()
            directory.deleteRecursively()
        }
    }

    @Test fun noteDocumentReusePreservesEveryReferenceAndBrokenBooksCloseDescriptors() {
        val directory = File(appCtx.cacheDir, "epub-notes-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val book = fixture(directory, "notes")
            val chapter = EpubFile.getChapterList(book).first { it.url.contains("chapter.xhtml") }
            repeat(2) {
                val notes = arrayListOf<String>()
                EpubNote.extract(EpubFile.getContent(book, chapter)!!, notes)
                assertEquals(listOf("第一条注释", "第二条注释"), notes)
            }
            EpubFile.clear()
            val broken = File(directory, "broken.epub").apply { writeText("not a zip") }
            val badBook = book.copy(bookUrl = broken.absolutePath, originName = broken.name)
            repeat(10) {
                assertTrue(runCatching { EpubFile.getChapterList(badBook) }.isFailure)
                EpubFile.clear()
                assertEquals(0, descriptorCount(broken.absolutePath))
            }
        } finally {
            EpubFile.clear()
            directory.deleteRecursively()
        }
    }

    @Test fun lazyResourceClosesItsStreamWhenReadingFails() {
        var closed = false
        val resource = LazyResource(LazyResourceProvider {
            object : InputStream() {
                override fun read(): Int = throw IOException("synthetic read failure")
                override fun close() { closed = true }
            }
        }, "broken.bin")
        assertTrue(runCatching { resource.data }.isFailure)
        assertTrue(closed)
    }
}
