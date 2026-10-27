package io.stackvibed.reporter.runs

import io.stackvibed.model.RecordingKind
import java.util.UUID

class WindowAlreadyActive(val active: RunAssignment) :
    RuntimeException("A recording window for '${active.scenarioId}' is already active")

/** The one manual recording window: while open, untagged spans are attributed to it. */
class RecordingWindow {
    private var active: RunAssignment? = null

    @Synchronized
    fun start(scenarioId: String): RunAssignment {
        active?.let { throw WindowAlreadyActive(it) }
        return RunAssignment(UUID.randomUUID().toString(), scenarioId, RecordingKind.MANUAL).also { active = it }
    }

    @Synchronized
    fun stop(): RunAssignment? = active.also { active = null }

    @Synchronized
    fun active(): RunAssignment? = active
}
