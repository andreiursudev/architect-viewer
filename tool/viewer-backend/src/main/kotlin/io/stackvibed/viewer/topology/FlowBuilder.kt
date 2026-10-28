package io.stackvibed.viewer.topology

import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.view.Flow
import io.stackvibed.model.view.FlowStep
import io.stackvibed.model.workspace.ConnectionKind
import org.springframework.stereotype.Component
import java.net.URI

/** Turns the events of one run into the ordered steps shown on the flow board. */
@Component
class FlowBuilder {
    /**
     * [aliases] maps a raw component id to its curated id.
     *
     * One step per request or call, by who is on the other end:
     * - a server event whose parent span is a recorded client event is one hop from that caller to the server;
     *   the client event itself adds no step,
     * - a client event nobody answered (the callee was not recorded, or never reached) is a hop to the called host,
     * - any other server event is called by the e2e client.
     */
    fun build(manifest: RecordingManifest, events: List<RecordingEvent>, aliases: Map<String, String>): Flow {
        fun alias(id: String) = aliases[id] ?: id

        val requests = events.filterIsInstance<HttpServerEvent>()
        val callers = events.filterIsInstance<HttpClientEvent>().associateBy { it.traceId to it.spanId }
        fun callerOf(server: HttpServerEvent): HttpClientEvent? =
            server.parentSpanId?.let { callers[server.traceId to it] }
        val answered = requests.mapNotNull { callerOf(it) }.toSet()

        val steps = events.sortedBy { it.startEpochNanos }.mapNotNull { event ->
            when (event) {
                is HttpServerEvent -> {
                    val caller = callerOf(event)
                    step(
                        event, parent = caller?.let { enclosingRequest(it, requests)?.eventId },
                        from = alias(caller?.component ?: ComponentIds.E2E_CLIENT), to = alias(event.component),
                        kind = ConnectionKind.HTTP, label = "${event.method} ${event.route ?: event.path}", status = event.status,
                    )
                }
                is DbClientEvent -> step(
                    event, parent = enclosingRequest(event, requests)?.eventId,
                    from = alias(event.component), to = alias(ComponentIds.database(event.dbSystem, event.dbName)),
                    kind = ConnectionKind.DB, label = event.statement.replace(WHITESPACE, " ").trim().take(LABEL_LENGTH), status = null,
                )
                is HttpClientEvent -> if (event in answered) null else step(
                    event, parent = enclosingRequest(event, requests)?.eventId,
                    from = alias(event.component), to = alias(ComponentIds.service(event.serverAddress ?: UNKNOWN_HOST)),
                    kind = ConnectionKind.HTTP, label = "${event.method} ${pathOf(event.url)}", status = event.status,
                )
            }
        }
        return Flow(manifest.runId, manifest.scenarioId, steps)
    }

    /** The path of [url] as the label of a call; the raw [url] when it does not parse. */
    private fun pathOf(url: String): String =
        runCatching { URI(url).path }.getOrNull()?.ifEmpty { "/" } ?: url

    /**
     * The innermost request of the same component and trace whose time interval contains the [call] (a database call
     * or an outgoing HTTP call). Time containment rather than the span parent: the parent of a call is usually an
     * internal span that is not recorded.
     */
    private fun enclosingRequest(call: RecordingEvent, requests: List<HttpServerEvent>): HttpServerEvent? =
        requests
            .filter { it.component == call.component && it.traceId == call.traceId && contains(it, call) }
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
        const val UNKNOWN_HOST = "unknown-host"
    }
}
