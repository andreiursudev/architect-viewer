package io.stackvibed.viewer.topology

import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.KafkaConsumeEvent
import io.stackvibed.model.KafkaProduceEvent
import io.stackvibed.model.MethodEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.ScreenshotRecord
import io.stackvibed.model.UiActionEvent
import io.stackvibed.model.UiNavigationEvent
import io.stackvibed.model.UiNavigationKind
import io.stackvibed.model.view.Flow
import io.stackvibed.model.view.FlowStep
import io.stackvibed.model.view.FlowStepKind
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Locale

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
     * Kafka records (step 6 spec §6.1) go through a topic, `topic-<name>` ([ComponentIds.topic], then aliased):
     * - a produce is the step `produce <topic>` from its component to the topic; it nests like an outgoing call, under
     *   its owner's step, else under its enclosing entry's,
     * - a consume is the root step `consume <topic>` from the topic to its component. It is an entry like a request
     *   ([CallTree.enclosingEntry]): its listener method is its handler, and the methods and calls inside it nest under
     *   it. Its `causedBy` is the produce that sent its record ([CallTree.producerOf]); a produce nobody consumed stays
     *   a produce step, and nothing invents a consumer.
     *
     * In the method rules above a consume is a request: its handler, its method steps and the nesting of its calls. Both
     * Kafka steps have no status; an `errorType` marks them failed.
     *
     * Browser events (spec §4) are steps from the e2e client to the frontend, which no request answers:
     * - a page load or route change is the step `load <route>` or `→ <route>`, a click or submit `<event> <target>`;
     *   both are of kind `UI`, have no status and no parent,
     * - a fetch of a frontend (a client event of a component with ui events) nests like any call: the step of the server
     *   that answers it nests under the ui step the fetch belongs to ([CallTree.parentStepOf]); the fetch adds no step,
     * - an unanswered fetch of a frontend is a hop to the first segment of its path (`unknown-host` when it has none);
     *   a frontend reaches its API through the host that serves it, so the host says nothing.
     *
     * A ui step carries the id of the screenshot whose record lists its span (spec step 5 §4.1); no other step does.
     *
     * Without method events every unit and handler is null and the steps are those of a run recorded before step 3.
     */
    fun build(
        manifest: RecordingManifest,
        events: List<RecordingEvent>,
        aliases: Map<String, String>,
        screenshots: List<ScreenshotRecord> = emptyList(),
    ): Flow {
        fun alias(id: String) = aliases[id] ?: id
        val screenshotOf = screenshots.flatMap { record -> record.spanIds.map { it to record.id } }.toMap()

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
                    from = alias(event.component), to = alias(calleeOf(event, tree)),
                    kind = FlowStepKind.HTTP, label = "${event.method} ${pathOf(event.url)}", status = event.status,
                    fromUnit = tree.owner(event)?.className,
                )
                is MethodEvent -> {
                    val entry = tree.enclosingEntry(event)
                    if (entry == null || tree.isHandler(event)) null else step(
                        event, parent = tree.parentStepOf(event),
                        from = alias(event.component), to = alias(event.component),
                        kind = FlowStepKind.METHOD, label = event.methodName, // arguments are in the details, not on the arrow
                        status = null,
                        fromUnit = (tree.owner(event) ?: tree.handlerOf(entry))?.className, toUnit = event.className,
                    )
                }
                is UiNavigationEvent -> step(
                    event, parent = null, from = ComponentIds.E2E_CLIENT, to = alias(event.component), kind = FlowStepKind.UI,
                    label = when (event.kind) {
                        UiNavigationKind.LOAD -> "load ${event.route}"
                        UiNavigationKind.ROUTE -> "→ ${event.route}"
                    },
                    status = null, screenshot = screenshotOf[event.spanId],
                )
                is UiActionEvent -> step(
                    event, parent = null, from = ComponentIds.E2E_CLIENT, to = alias(event.component), kind = FlowStepKind.UI,
                    label = "${event.event.name.lowercase(Locale.ROOT)} ${event.target}", status = null,
                    screenshot = screenshotOf[event.spanId],
                )
                is KafkaProduceEvent -> step(
                    event, parent = tree.parentStepOf(event),
                    from = alias(event.component), to = alias(ComponentIds.topic(event.topic)),
                    kind = FlowStepKind.KAFKA, label = "produce ${event.topic}", status = null,
                    fromUnit = tree.owner(event)?.className,
                )
                is KafkaConsumeEvent -> {
                    val handler = tree.handlerOf(event)
                    step(
                        event, parent = null,
                        from = alias(ComponentIds.topic(event.topic)), to = alias(event.component),
                        kind = FlowStepKind.KAFKA, label = "consume ${event.topic}", status = null,
                        toUnit = handler?.className, handler = handler, causedBy = tree.producerOf(event)?.eventId,
                    )
                }
            }
        }
        return Flow(manifest.runId, manifest.scenarioId, steps)
    }

    /**
     * The component an unanswered call goes to: the called host; for a frontend, whose fetches go to the host that
     * serves it, the first path segment of the url (the API it addresses).
     */
    private fun calleeOf(call: HttpClientEvent, tree: CallTree): String {
        val raw = if (tree.hasUi(call.component)) {
            runCatching { URI(call.url).path }.getOrNull().orEmpty().split('/').firstOrNull { it.isNotEmpty() }.orEmpty()
        } else {
            call.serverAddress.orEmpty()
        }
        return ComponentIds.service(raw).ifEmpty { UNKNOWN_HOST } // "" or "..." slug to nothing
    }

    /** The path of [url] as the label of a call; the raw [url] when it does not parse. */
    private fun pathOf(url: String): String =
        runCatching { URI(url).path }.getOrNull()?.ifEmpty { "/" } ?: url

    /** A method step adds no connection to the board: its [connectionId] is null. */
    private fun step(
        event: RecordingEvent, parent: String?, from: String, to: String, kind: FlowStepKind, label: String, status: Int?,
        fromUnit: String? = null, toUnit: String? = null, handler: MethodEvent? = null, screenshot: String? = null,
        causedBy: String? = null,
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
        screenshot = screenshot,
        causedBy = causedBy,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val LABEL_LENGTH = 60
        const val UNKNOWN_HOST = "unknown-host"
    }
}

/**
 * Who calls whom inside one run: the callers of requests, the owners of events, the handlers of entries (requests and
 * consumed records), the produce behind each consume and the ui steps fetches belong to.
 */
private class CallTree(events: List<RecordingEvent>) {
    private val bySpan = events.associateBy { it.traceId to it.spanId }

    /** Where work enters a component: a request it serves or a record it consumes. */
    private val entries: Map<Pair<String, String>, List<RecordingEvent>> =
        events.filter { isEntry(it) }.groupBy { it.component to it.traceId }
    private val produces = events.filterIsInstance<KafkaProduceEvent>()
    private val methods = events.filterIsInstance<MethodEvent>()
    private val methodsByScope = methods.groupBy { it.component to it.traceId }
    private val uiEvents: Map<String, List<RecordingEvent>> =
        events.filter { it is UiNavigationEvent || it is UiActionEvent }.groupBy { it.component }
    private val answered = events.filterIsInstance<HttpServerEvent>().mapNotNullTo(HashSet()) { callerOf(it)?.eventId }
    private val owners: Map<String, MethodEvent?> =
        events.filterNot { isEntry(it) }.associate { it.eventId to findOwner(it) }

    /** Entry event id to its handler: the earliest method without an owner that the entry encloses. */
    private val handlers: Map<String, MethodEvent> = methods
        .filter { owner(it) == null }
        .groupBy { enclosingEntry(it)?.eventId }
        .mapNotNull { (entry, candidates) -> entry?.let { it to candidates.minWith(EARLIEST_FIRST) } }
        .toMap()

    /** Handler event id to the id of its entry, whose step the handler is merged into. */
    private val entryOfHandler = handlers.entries.associate { (entry, handler) -> handler.eventId to entry }

    fun callerOf(server: HttpServerEvent): HttpClientEvent? =
        server.parentSpanId?.let { bySpan[server.traceId to it] as? HttpClientEvent }

    fun isAnswered(client: HttpClientEvent): Boolean = client.eventId in answered

    /** The method [entry] (a request or a consume) landed on: the listener method of a consume. */
    fun handlerOf(entry: RecordingEvent): MethodEvent? = handlers[entry.eventId]

    fun isHandler(method: MethodEvent): Boolean = method.eventId in entryOfHandler

    /**
     * The produce that sent the record of [consume]: the produce whose span is its parent span; else the produce of the
     * same topic, partition and offset, when both events know their partition and offset; else none (the produce was
     * not recorded).
     */
    fun producerOf(consume: KafkaConsumeEvent): KafkaProduceEvent? {
        val parent = consume.parentSpanId?.let { bySpan[consume.traceId to it] }
        if (parent is KafkaProduceEvent) return parent
        val partition = consume.partition ?: return null
        val offset = consume.offset ?: return null
        return produces.firstOrNull { it.topic == consume.topic && it.partition == partition && it.offset == offset }
    }

    /**
     * The method event that called [event] (spec §3.2): the method whose span is its parent span; none when the parent
     * is another recorded event (a request, for its handler). When the parent span is not recorded, the innermost method
     * of the same component and trace whose time interval contains [event], as [enclosingEntry] picks an entry; only
     * a strictly longer one, so that two methods of the same interval never own each other (a cycle of steps).
     */
    fun owner(event: RecordingEvent): MethodEvent? = owners[event.eventId]

    fun hasUi(component: String): Boolean = component in uiEvents

    /**
     * The step [event] nests under: its owner's step when it has one, else its enclosing entry's. A fetch of a
     * frontend nests under the ui event it belongs to ([uiStepOf]).
     */
    fun parentStepOf(event: RecordingEvent): String? =
        if (event is HttpClientEvent && hasUi(event.component)) uiStepOf(event)
        else owner(event)?.let { stepOf(it) } ?: enclosingEntry(event)?.eventId

    /**
     * The ui event whose span is the parent span of [fetch]; else the latest ui event of its component that started
     * no later than the fetch (the later one in the list of two starting together); else none.
     */
    private fun uiStepOf(fetch: HttpClientEvent): String? {
        val candidates = uiEvents[fetch.component].orEmpty()
        val parent = fetch.parentSpanId?.let { bySpan[fetch.traceId to it] }
        if (parent != null && parent in candidates) return parent.eventId
        return candidates.filter { it.startEpochNanos <= fetch.startEpochNanos }.sortedBy { it.startEpochNanos }.lastOrNull()?.eventId
    }

    /**
     * The innermost entry (a request or a consume) of the same component and trace whose time interval contains [event]
     * (a method, a database call, an outgoing HTTP call or a produce). Time containment rather than the span parent: the
     * parent of a call is usually a method span, or an internal span that is not recorded.
     */
    fun enclosingEntry(event: RecordingEvent): RecordingEvent? = innermost(entries[event.component to event.traceId], event)

    /** The step of [method]: its entry's step for a handler, its own for any other method inside an entry. */
    private fun stepOf(method: MethodEvent): String? =
        entryOfHandler[method.eventId] ?: method.eventId.takeIf { enclosingEntry(method) != null }

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
        /** An entry has no owner: it is where a request or a consumed record enters its component. */
        fun isEntry(event: RecordingEvent): Boolean = event is HttpServerEvent || event is KafkaConsumeEvent

        /** Of two methods starting together, the outer (longer) one comes first. */
        val EARLIEST_FIRST = compareBy<MethodEvent> { it.startEpochNanos }.thenByDescending { it.durationNanos }
    }
}
