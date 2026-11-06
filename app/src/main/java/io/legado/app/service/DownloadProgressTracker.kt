package io.legado.app.service

internal data class DownloadProgress(
    val status: Int,
    val downloaded: Long,
    val total: Long,
    val terminal: Boolean = false,
    val successful: Boolean = false
) {
    val notificationProgress: Int
        get() = if (total <= 0) 0 else (downloaded.coerceAtLeast(0).toDouble() / total * 1000).toInt().coerceIn(0, 1000)
    val indeterminate get() = !terminal && total <= 0
}

/** Owned by the service main thread; worker queries return immutable progress values. */
internal class DownloadProgressTracker(private var nextNotificationId: Int) {
    private data class Entry(val notificationId: Int, var progress: DownloadProgress? = null)
    data class Change(val notificationId: Int, val notify: Boolean, val completed: Boolean)
    private val entries = linkedMapOf<Long, Entry>()

    fun register(id: Long): Int = entries.getOrPut(id) { Entry(nextNotificationId++) }.notificationId
    fun activeIds() = entries.filterValues { it.progress?.terminal != true }.keys.toList()
    fun remove(id: Long): Int? = entries.remove(id)?.notificationId
    fun update(id: Long, progress: DownloadProgress): Change? {
        val entry = entries[id] ?: return null
        val previous = entry.progress
        if (previous?.terminal == true || previous == progress) return null
        entry.progress = progress
        return Change(entry.notificationId,
            previous == null || previous.status != progress.status || previous.terminal != progress.terminal ||
                previous.notificationProgress != progress.notificationProgress || previous.indeterminate != progress.indeterminate,
            progress.successful && previous?.successful != true)
    }
}
