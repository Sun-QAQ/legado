package io.legado.app.model.localBook

import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Closing the parser relinquishes ownership; its open image streams retain the backing file. */
internal class RetainedInputStreams(private val releaseResource: () -> Unit) : Closeable {
    private var count = 0
    private var closed = false
    private var released = false
    val isClosed: Boolean @Synchronized get() = closed

    @Synchronized
    fun retain(input: InputStream): InputStream {
        if (closed) {
            input.close()
            error("EPUB parser is closed")
        }
        count++
        return object : FilterInputStream(input) {
            private val streamClosed = AtomicBoolean()
            override fun close() {
                if (!streamClosed.compareAndSet(false, true)) return
                try { super.close() } finally { releaseStream() }
            }
        }
    }

    @Synchronized
    private fun releaseStream() { count--; releaseIfUnused() }

    @Synchronized
    override fun close() { closed = true; releaseIfUnused() }

    private fun releaseIfUnused() {
        if (closed && count == 0 && !released) {
            released = true
            releaseResource()
        }
    }
}
