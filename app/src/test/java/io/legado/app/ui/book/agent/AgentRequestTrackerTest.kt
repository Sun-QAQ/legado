package io.legado.app.ui.book.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AgentRequestTrackerTest {

    @Test
    fun `取消请求后仍保留任务引用直到切换会话完成等待`() = runBlocking {
        val tracker = AgentRequestTracker()
        val job = Job()
        tracker.track(job)

        tracker.cancel()

        assertFalse(job.isActive)
        assertSame(job, tracker.job)

        tracker.cancelAndJoin()
        assertNull(tracker.job)
    }
}
