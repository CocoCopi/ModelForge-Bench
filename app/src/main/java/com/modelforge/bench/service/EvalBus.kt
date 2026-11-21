package com.modelforge.bench.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The single live view of an evaluation. The service owns the run; the UI only
 * observes this object. Keeping it in one place means a configuration change,
 * a trip to the home screen, or a process-local service restart cannot lose the
 * progress the notification is already showing.
 */
data class EvalLive(
    val running: Boolean = false,
    val modelId: String = "",
    val modelName: String = "",
    val stage: String = "",
    val fraction: Double = 0.0,
    val detail: String = "",
    val startedAt: Long = 0L,
    val finishedRunId: String? = null,
    val error: String? = null
)

object EvalBus {

    private val _state = MutableStateFlow(EvalLive())
    val state: StateFlow<EvalLive> = _state.asStateFlow()

    fun start(modelId: String, modelName: String) {
        _state.value = EvalLive(
            running = true,
            modelId = modelId,
            modelName = modelName,
            stage = "Preparing",
            fraction = 0.0,
            startedAt = System.currentTimeMillis()
        )
    }

    fun progress(stage: String, fraction: Double, detail: String = "") {
        _state.update {
            it.copy(
                stage = stage,
                fraction = fraction.coerceIn(0.0, 1.0),
                detail = detail
            )
        }
    }

    fun finished(runId: String?) {
        _state.update {
            it.copy(
                running = false,
                fraction = 1.0,
                stage = if (runId == null) "Failed" else "Finished",
                finishedRunId = runId
            )
        }
    }

    fun failed(message: String) {
        _state.update {
            it.copy(running = false, stage = "Failed", error = message)
        }
    }

    fun consume() {
        _state.update { it.copy(finishedRunId = null, error = null) }
    }
}
