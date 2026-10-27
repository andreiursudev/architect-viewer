package io.stackvibed.viewer.topology

import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.view.Flow
import io.stackvibed.model.view.FlowStep
import io.stackvibed.model.workspace.ConnectionKind
import org.springframework.stereotype.Component

/** Turns the events of one run into the ordered steps shown on the flow board. */
@Component
class FlowBuilder {
    /** [aliases] maps a raw component id to its curated id. */
    fun build(manifest: RecordingManifest, events: List<RecordingEvent>, aliases: Map<String, String>): Flow {
        fun alias(id: String) = aliases[id] ?: id

        val httpEvents = events.filterIsInstance<HttpServerEvent>()
        val steps = events.sortedBy { it.startEpochNanos }.map { event ->
            when (event) {
                is HttpServerEvent -> step(
                    event, parent = null,
                    from = alias(ComponentIds.E2E_CLIENT), to = alias(event.component),
                    kind = ConnectionKind.HTTP, label = "${event.method} ${event.route ?: event.path}", status = event.status,
                )
                is DbClientEvent -> step(
                    event, parent = enclosingRequest(event, httpEvents)?.eventId,
                    from = alias(event.component), to = alias(ComponentIds.database(event.dbSystem, event.dbName)),
                    kind = ConnectionKind.DB, label = event.statement.replace(WHITESPACE, " ").trim().take(LABEL_LENGTH), status = null,
                )
            }
        }
        return Flow(manifest.runId, manifest.scenarioId, steps)
    }

    /**
     * The innermost request of the same component and trace whose time interval contains the call. Time containment
     * rather than the span parent: the parent of a database call is usually an internal span that is not recorded.
     */
    private fun enclosingRequest(db: DbClientEvent, requests: List<HttpServerEvent>): HttpServerEvent? =
        requests
            .filter { it.component == db.component && it.traceId == db.traceId && contains(it, db) }
            .minWithOrNull(compareBy<HttpServerEvent> { it.durationNanos }.thenByDescending { it.startEpochNanos })

    private fun contains(outer: RecordingEvent, inner: RecordingEvent): Boolean =
        outer.startEpochNanos <= inner.startEpochNanos &&
            inner.startEpochNanos + inner.durationNanos <= outer.startEpochNanos + outer.durationNanos

    private fun step(
        event: RecordingEvent, parent: String?, from: String, to: String, kind: ConnectionKind, label: String, status: Int?,
    ) = FlowStep(
        stepId = event.eventId,
        parentStepId = parent,
        from = from,
        to = to,
        connectionId = "$from--$to",
        kind = kind,
        label = label,
        startEpochNanos = event.startEpochNanos,
        durationNanos = event.durationNanos,
        status = status,
        event = event,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val LABEL_LENGTH = 60
    }
}
