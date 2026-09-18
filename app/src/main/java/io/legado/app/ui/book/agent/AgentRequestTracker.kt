package io.legado.app.ui.book.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * 保留已取消任务的引用，直到会话切换明确等待其收尾，避免旧回复写入新会话。
 */
internal class AgentRequestTracker {

    var job: Job? = null
        private set

    fun track(job: Job) {
        this.job = job
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun cancelAndJoin() {
        val current = job
        current?.cancelAndJoin()
        if (job === current) {
            job = null
        }
    }
}
