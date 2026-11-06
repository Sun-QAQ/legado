package io.legado.app.service

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTrackerTest {
    @Test fun `parallel downloads leave polling on success failure and cancellation`() {
        val tracker = DownloadProgressTracker(10000)
        (1L..3L).forEach(tracker::register)
        assertEquals(listOf(1L, 2L, 3L), tracker.activeIds())
        assertTrue(tracker.update(1, DownloadProgress(8, 100, 100, terminal = true, successful = true))!!.completed)
        tracker.update(2, DownloadProgress(16, 0, 100, terminal = true))
        assertEquals(listOf(3L), tracker.activeIds())
        tracker.remove(3)
        assertTrue(tracker.activeIds().isEmpty())
        assertNull(tracker.update(3, DownloadProgress(2, 30, 100)))
        assertNull(tracker.update(1, DownloadProgress(2, 50, 100)))
    }

    @Test fun `unchanged and invisible progress changes do not refresh notifications`() {
        val tracker = DownloadProgressTracker(10000)
        tracker.register(1)
        val first = DownloadProgress(2, 100000, 100000000)
        assertTrue(tracker.update(1, first)!!.notify)
        assertNull(tracker.update(1, first))
        assertFalse(tracker.update(1, first.copy(downloaded = 100001))!!.notify)
        assertTrue(tracker.update(1, first.copy(status = 4))!!.notify)
    }

    @Test fun `notification IDs are returned consistently and never reused after removal`() {
        val tracker = DownloadProgressTracker(10000)
        val first = tracker.register(9876543210)
        assertEquals(first, tracker.register(9876543210))
        assertEquals(first, tracker.update(9876543210, DownloadProgress(2, 1, 2))!!.notificationId)
        assertEquals(first, tracker.remove(9876543210))
        assertNotEquals(first, tracker.register(9))
    }

    @Test fun `large and unknown sizes have bounded correct progress`() {
        assertEquals(500, DownloadProgress(2, 3_000_000_000, 6_000_000_000).notificationProgress)
        assertEquals(1000, DownloadProgress(2, Long.MAX_VALUE, Long.MAX_VALUE).notificationProgress)
        assertEquals(1000, DownloadProgress(2, 200, 100).notificationProgress)
        assertEquals(0, DownloadProgress(2, -1, 100).notificationProgress)
        assertTrue(DownloadProgress(2, 0, -1).indeterminate)
        assertFalse(DownloadProgress(8, 100, 100, terminal = true).indeterminate)
    }
}
