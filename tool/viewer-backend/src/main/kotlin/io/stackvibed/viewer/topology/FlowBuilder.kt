package io.stackvibed.viewer.topology

import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.MethodEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.UiActionEvent
import io.stackvibed.model.UiNavigationEvent
import io.stackvibed.model.view.Flow
import io.stackvibed.model.view.FlowStep
import io.stackvibed.model.view.FlowStepKind
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
     *
     * Method events (spec §3) place steps on classes inside a component:
     * - the earliest method of a request without an owner ([CallTree.owner]) is its handler: it adds no step; the
     *   request step lands on its class (`toUnit`) and carries it as `handler`,
     * - every other method inside a request is a method step from its owner's class (the handler's class when it has
     *   no owner) to its own, nested under the owner's step; a method inside no request adds no step,
     * - database and client calls leave from their owner's class and nest under its step, a called server under the
     *   step of its caller's owner; without an owner they nest under the enclosing request as before.
     *
     * Without method events every unit and handler is null and the steps are those of a run recorded before step 3.
     */
    fun build(manifest: RecordingManifest, events: List<RecordingEvent>, aliases: Map<String, String>): Flow {
        fun alias(id: String) = aliases[id] ?: id

        val tree = CallTree(events)
        val steps = events.sortedBy { it.startEpochNanos }.mapNotNull { event ->
            when (event) {
                is HttpServerEvent -> {
                    val caller = tree.callerOf(event)
                    val handler = tree.handlerOf(event)
                    step(
                        event, parent = caller?.let { tree.parentStepOf(it) },
                        from = alias(caller?.component ?: ComponentIds.E2E_CLIENT), to = alias(event.component),
                        kind = FlowStepKind.HTTP, label = "${event.method} ${event.route ?: event.path}", status = event.status,
                        fromUnit = caller?.let { tree.owner(it)?.className }, toUnit = handler?.className, handler = handler,
                    )
                }
                is DbClientEvent -> step(
                    event, parent = tree.parentStepOf(event),
                    from = alias(event.component), to = alias(ComponentIds.database(event.dbSystem, event.dbName)),
                    kind = FlowStepKind.DB, label = event.statement.replace(WHITESPACE, " ").trim().take(LABEL_LENGTH), status = null,
                    fromUnit = tree.owner(event)?.className,
                )
                is HttpClientEvent -> if (tree.isAnswered(event)) null else step(
                    event, parent = tree.parentStepOf(event),
                    from = alias(event.component), to = alias(ComponentIds.service(event.serverAddress.orEmpty()).ifEmpty { UNKNOWN_HOST }), // "" or "..." slug to nothing
                    kind = FlowStepKind.HTTP, label = "${event.method} ${pathOf(event.url)}", status = event.status,
                    fromUnit = tree.owner(event)?.className,
                )
                is MethodEvent -> {
                    val request = tree.enclosingRequest(event)
                    if (request == null || tree.isHandler(event)) null else step(
                        event, parent = tree.parentStepOf(event),
                        from = alias(event.component), to = alias(event.component),
                        kind = FlowStepKind.METHOD, label = event.methodName, // arguments are in the details, not on the arrow
                        status = null,
                        fromUnit = (tree.owner(event) ?: tree.handlerOf(request))?.className, toUnit = event.className,
                    )
                }
                // Browser events become steps in a later change.
                is UiNavigationEvent, is UiActionEvent -> null
            }
        }
        return Flow(manifest.runId, manifest.scenarioId, steps)
    }

    /** The path of [url] as the label of a call; the raw [url] when it does not parse. */
    private fun pathOf(url: String): String =
        runCatching { URI(url).path }.getOrNull()?.ifEmpty { "/" } ?: url

    /** A method step adds no connection to the board: its [connectionId] is null. */
    private fun step(
        event: RecordingEvent, parent: String?, from: String, to: String, kind: FlowStepKind, label: String, status: Int?,
        fromUnit: String? = null, toUnit: String? = null, handler: MethodEvent? = null,
    ) = FlowStep(
        stepId = event.eventId,
        parentStepId = parent,
        from = from,
        to = to,
        fromUnit = fromUnit,
        toUnit = toUnit,
        connectionId = if (kind == FlowStepKind.METHOD) null else "$from--$to",
        kind = kind,
        label = label,
        startEpochNanos = event.startEpochNanos,
        durationNanos = event.durationNanos,
        status = status,
        event = event,
        handler = handler,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val LABEL_LENGTH = 60
        const val UNKNOWN_HOST = "unknown-host"
    }
}

/** Who calls whom inside one run: the callers of requests, the owners of events and the handlers of requests. */
private class CallTree(events: List<RecordingEvent>) {
    private val bySpan = events.associateBy { it.traceId to it.spanId }
    private val requests = events.filterIsInstance<HttpServerEvent>().groupBy { it.component to it.traceId }
    private val methods = events.filterIsInstance<MethodEvent>()
    private val methodsByScope = methods.groupBy { it.component to it.traceId }
    private val answered = requests.values.flatten().mapNotNullTo(HashSet()) { callerOf(it)?.eventId }
    private val owners: Map<String, MethodEvent?> =
        events.filter { it !is HttpServerEvent }.associate { it.eventId to findOwner(it) }

    /** Request event id to its handler: the earliest method without an owner that the request encloses. */
    private val handlers: Map<String, MethodEvent> = methods
        .filter { owner(it) == null }
        .groupBy { enclosingRequest(it)?.eventId }
        .mapNotNull { (request, candidates) -> request?.let { it to candidates.minWith(EARLIEST_FIRST) } }
        .toMap()

    /** Handler event id to the id of its request, whose step the handler is merged into. */
    private val requestOfHandler = handlers.entries.associate { (request, handler) -> handler.eventId to request }

    fun callerOf(server: HttpServerEvent): HttpClientEvent? =
        server.parentSpanId?.let { bySpan[server.traceId to it] as? HttpClientEvent }

    fun isAnswered(client: HttpClientEvent): Boolean = client.eventId in answered

    fun handlerOf(request: HttpServerEvent): MethodEvent? = handlers[request.eventId]

    fun isHandler(method: MethodEvent): Boolean = method.eventId in requestOfHandler

    /**
     * The method event that called [event] (spec §3.2): the method whose span is its parent span; none when the parent
     * is another recorded event (a request, for its handler). When the parent span is not recorded, the innermost method
     * of the same component and trace whose time interval contains [event], as [enclosingRequest] picks a request; only
     * a strictly longer one, so that two methods of the same interval never own each other (a cycle of steps).
     */
    fun owner(event: RecordingEvent): MethodEvent? = owners[event.eventId]

    /** The step [event] nests under: its owner's step when it has one, else its enclosing request's. */
    fun parentStepOf(event: RecordingEvent): String? =
        owner(event)?.let { stepOf(it) } ?: enclosingRequest(event)?.eventId

    /**
     * The innermost request of the same component and trace whose time interval contains [event] (a method, a database
     * call or an outgoing HTTP call). Time containment rather than the span parent: the parent of a call is usually a
     * method span, or an internal span that is not recorded.
     */
    fun enclosingRequest(event: RecordingEvent): HttpServerEvent? = innermost(requests[event.component to event.traceId], event)

    /** The step of [method]: its request's step for a handler, its own for any other method inside a request. */
    private fun stepOf(method: MethodEvent): String? =
        requestOfHandler[method.eventId] ?: method.eventId.takeIf { enclosingRequest(method) != null }

    private fun findOwner(event: RecordingEvent): MethodEvent? {
        val parent = event.parentSpanId?.let { bySpan[event.traceId to it] }
        if (parent != null) return parent as? MethodEvent
        val longer = methodsByScope[event.component to event.traceId].orEmpty().filter { it.durationNanos > event.durationNanos }
        return innermost(longer, event)
    }

    /** The shortest of [candidates] (other than [event]) containing [event] in time; the later one of equal length. */
    private fun <T : RecordingEvent> innermost(candidates: List<T>?, event: RecordingEvent): T? =
        candidates.orEmpty()
            .filter { it.eventId != event.eventId && contains(it, event) }
            .minWithOrNull(compareBy<T> { it.durationNanos }.thenByDescending { it.startEpochNanos })

    private fun contains(outer: RecordingEvent, inner: RecordingEvent): Boolean =
        outer.startEpochNanos <= inner.startEpochNanos &&
            inner.startEpochNanos + inner.durationNanos <= outer.startEpochNanos + outer.durationNanos

    private companion object {
        /** Of two methods starting together, the outer (longer) one comes first. */
        val EARLIEST_FIRST = compareBy<MethodEvent> { it.startEpochNanos }.thenByDescending { it.durationNanos }
    }
}
