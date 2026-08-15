package io.legado.app.ui.book.agent

enum class AgentStepState {
    RUNNING,
    DONE,
    FAILED
}

data class AgentStep(
    val id: String,
    val title: String,
    val state: AgentStepState = AgentStepState.RUNNING,
    val detail: String? = null,
    val summary: String? = null,
    val durationMs: Long? = null
)
