package io.stackvibed.reporter.runs

import io.stackvibed.model.RecordingKind
import io.stackvibed.reporter.otlp.SpanTags

/** Run id (and folder) that collects spans which belong to neither a tagged run nor a manual window. */
const val UNASSIGNED_RUN = "_unassigned"

data class RunAssignment(val runId: String, val scenarioId: String?, val kind: RecordingKind)

/** Decides which run a span belongs to: its own tags first, then the manual window, else unassigned. */
class RunResolver(private val window: RecordingWindow) {
    fun resolve(tags: SpanTags): RunAssignment {
        tags.runId?.let { return RunAssignment(it, tags.scenarioId, RecordingKind.E2E) }
        return window.active() ?: RunAssignment(UNASSIGNED_RUN, null, RecordingKind.MANUAL)
    }
}
