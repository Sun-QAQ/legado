package io.legado.app.ui.book.agent

internal class AgentImageSourceSelection(
    load: () -> Long,
    private val save: (Long) -> Unit
) {
    var currentId: Long = load().coerceAtLeast(0L)
        private set

    fun select(id: Long) {
        currentId = id.coerceAtLeast(0L)
        save(currentId)
    }

    fun resolve(enabledIds: List<Long>): Long {
        if (currentId !in enabledIds) {
            select(enabledIds.firstOrNull() ?: 0L)
        }
        return currentId
    }
}
