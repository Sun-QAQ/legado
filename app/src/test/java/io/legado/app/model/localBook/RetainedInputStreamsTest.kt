package io.legado.app.model.localBook

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class RetainedInputStreamsTest {
    @Test fun `parser closes immediately with no image readers`() {
        var released = 0
        val owner = RetainedInputStreams { released++ }
        owner.close()
        owner.close()
        assertEquals(1, released)
        assertTrue(owner.isClosed)
    }

    @Test fun `open image streams survive switching until the final reader closes`() {
        var released = false
        val owner = RetainedInputStreams { released = true }
        fun image() = owner.retain(object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(): Int { check(!released); return super.read() }
        })
        val first = image()
        val second = image()
        owner.close()
        assertFalse(released)
        assertEquals(1, first.read())
        first.close()
        first.close()
        assertFalse(released)
        assertEquals(1, second.read())
        second.close()
        assertTrue(released)
    }

    @Test fun `image close failure still releases the backing resource`() {
        var released = 0
        val owner = RetainedInputStreams { released++ }
        val image = owner.retain(object : InputStream() {
            override fun read() = -1
            override fun close() { throw IOException("broken close") }
        })
        owner.close()
        assertThrows(IOException::class.java) { image.close() }
        image.close()
        assertEquals(1, released)
    }

    @Test fun `closed parser rejects and closes newly supplied streams`() {
        var closed = false
        val owner = RetainedInputStreams {}
        owner.close()
        val image = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { closed = true } }
        assertThrows(IllegalStateException::class.java) { owner.retain(image) }
        assertTrue(closed)
    }
}
