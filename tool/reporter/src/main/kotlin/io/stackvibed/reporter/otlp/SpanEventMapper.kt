package io.stackvibed.reporter.otlp

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.trace.v1.Span
import io.stackvibed.agent.contract.StackAttributes
import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.MethodEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.UiActionEvent
import io.stackvibed.model.UiActionKind
import io.stackvibed.model.UiNavigationEvent
import io.stackvibed.model.UiNavigationKind
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/** The scenario and run the agent copied from baggage onto a span; either may be absent. */
data class SpanTags(val scenarioId: String?, val runId: String?)

/** An event whose run id is decided later, once the reporter has resolved where the span belongs. */
fun interface EventDraft {
    fun withRun(runId: String): RecordingEvent
}

data class MappedSpan(val tags: SpanTags, val toEvent: EventDraft)

/**
 * Maps OTLP spans to recording events: HTTP server spans, DB client spans, HTTP client spans (the browser's fetch spans among
 * them), the INTERNAL spans the agent writes for method calls (those carrying `stack.method.class`) and the INTERNAL
 * navigation and action spans of the browser package, recognised by their span name. Every other span is dropped, and so are
 * DB client spans without a statement and ui spans with an unknown kind.
 */
class SpanEventMapper {
    private val json = JsonMapper.builder().build()

    fun map(request: ExportTraceServiceRequest): List<MappedSpan> = buildList {
        for (resourceSpans in request.resourceSpansList) {
            val component = componentOf(resourceSpans.resource.attributesList)
            for (scopeSpans in resourceSpans.scopeSpansList) {
                for (span in scopeSpans.spansList) {
                    val attrs = span.attributesList.associate { it.key to it.value }
                    val draft = when (span.kind) {
                        Span.SpanKind.SPAN_KIND_SERVER -> httpDraft(span, attrs, component)
                        Span.SpanKind.SPAN_KIND_CLIENT ->
                            if (isDb(attrs)) dbDraft(span, attrs, component) else clientDraft(span, attrs, component)
                        Span.SpanKind.SPAN_KIND_INTERNAL -> when (span.name) {
                            StackAttributes.NAVIGATION_SPAN -> navigationDraft(span, attrs, component)
                            StackAttributes.ACTION_SPAN -> actionDraft(span, attrs, component)
                            else -> methodDraft(span, attrs, component)
                        }
                        else -> null
                    } ?: continue
                    add(MappedSpan(SpanTags(attrs.string(StackAttributes.SCENARIO), attrs.string(StackAttributes.RUN)), draft))
                }
            }
        }
    }

    private fun componentOf(resourceAttributes: List<KeyValue>): String {
        val serviceName = resourceAttributes.firstOrNull { it.key == "service.name" }?.value?.stringValue
        return serviceName?.let(ComponentIds::service)?.ifEmpty { null } ?: UNKNOWN_SERVICE
    }

    private fun httpDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val method = attrs.string("http.request.method") ?: return null
        return EventDraft { runId ->
            HttpServerEvent(
                eventId = eventId(span),
                runId = runId,
                traceId = span.traceId.hex(),
                spanId = span.spanId.hex(),
                parentSpanId = span.parentSpanId.hex().ifEmpty { null },
                component = component,
                startEpochNanos = span.startTimeUnixNano,
                durationNanos = span.endTimeUnixNano - span.startTimeUnixNano,
                method = method,
                route = attrs.string("http.route"),
                path = attrs.string("url.path") ?: "",
                status = attrs.long("http.response.status_code")?.toInt() ?: 0,
                requestHeaders = headers(attrs.string(StackAttributes.HTTP_REQUEST_HEADERS)),
                responseHeaders = headers(attrs.string(StackAttributes.HTTP_RESPONSE_HEADERS)),
                requestBody = attrs.string(StackAttributes.HTTP_REQUEST_BODY),
                responseBody = attrs.string(StackAttributes.HTTP_RESPONSE_BODY),
                requestBodyTruncated = attrs.boolean(StackAttributes.HTTP_REQUEST_BODY_TRUNCATED),
                responseBodyTruncated = attrs.boolean(StackAttributes.HTTP_RESPONSE_BODY_TRUNCATED),
            )
        }
    }

    private fun isDb(attrs: Map<String, AnyValue>) = dbSystem(attrs) != null

    private fun dbSystem(attrs: Map<String, AnyValue>) = attrs.string("db.system.name") ?: attrs.string("db.system")

    private fun dbDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val dbSystem = dbSystem(attrs) ?: return null
        // A blank statement is the connection pool validating an idle connection, not a step of the scenario.
        val statement = (attrs.string("db.query.text") ?: attrs.string("db.statement"))?.takeIf { it.isNotBlank() } ?: return null
        return EventDraft { runId ->
            DbClientEvent(
                eventId = eventId(span),
                runId = runId,
                traceId = span.traceId.hex(),
                spanId = span.spanId.hex(),
                parentSpanId = span.parentSpanId.hex().ifEmpty { null },
                component = component,
                startEpochNanos = span.startTimeUnixNano,
                durationNanos = span.endTimeUnixNano - span.startTimeUnixNano,
                dbSystem = dbSystem,
                dbName = attrs.string("db.namespace") ?: attrs.string("db.name"),
                statement = statement,
                parameters = parameters(attrs.string(StackAttributes.DB_PARAMETERS)),
                rowCount = attrs.long(StackAttributes.DB_ROW_COUNT),
            )
        }
    }

    private fun clientDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val method = attrs.string("http.request.method") ?: return null
        return EventDraft { runId ->
            HttpClientEvent(
                eventId = eventId(span),
                runId = runId,
                traceId = span.traceId.hex(),
                spanId = span.spanId.hex(),
                parentSpanId = span.parentSpanId.hex().ifEmpty { null },
                component = component,
                startEpochNanos = span.startTimeUnixNano,
                durationNanos = span.endTimeUnixNano - span.startTimeUnixNano,
                method = method,
                url = attrs.string("url.full") ?: "",
                serverAddress = attrs.string("server.address"),
                serverPort = attrs.long("server.port")?.toInt(),
                // The browser's fetch instrumentation writes status 0 for a fetch that got no response: there is none.
                status = attrs.long("http.response.status_code")?.toInt()?.takeIf { it != 0 },
                errorType = attrs.string("error.type"),
            )
        }
    }

    private fun methodDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val className = attrs.string(StackAttributes.METHOD_CLASS) ?: return null
        return EventDraft { runId ->
            MethodEvent(
                eventId = eventId(span),
                runId = runId,
                traceId = span.traceId.hex(),
                spanId = span.spanId.hex(),
                parentSpanId = span.parentSpanId.hex().ifEmpty { null },
                component = component,
                startEpochNanos = span.startTimeUnixNano,
                durationNanos = span.endTimeUnixNano - span.startTimeUnixNano,
                className = className,
                methodName = attrs.string(StackAttributes.METHOD_NAME) ?: "",
                parameterNames = strings(attrs.string(StackAttributes.METHOD_PARAM_NAMES)),
                arguments = strings(attrs.string(StackAttributes.METHOD_ARGS)),
                returnValue = attrs.string(StackAttributes.METHOD_RETURN),
                exceptionType = attrs.string(StackAttributes.METHOD_EXCEPTION_TYPE),
                exceptionMessage = attrs.string(StackAttributes.METHOD_EXCEPTION_MESSAGE),
                thread = attrs.string(StackAttributes.METHOD_THREAD),
            )
        }
    }

    private fun navigationDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val kind = when (attrs.string(StackAttributes.UI_NAVIGATION_KIND)) {
            "load" -> UiNavigationKind.LOAD
            "route" -> UiNavigationKind.ROUTE
            else -> return null
        }
        return EventDraft { runId ->
            UiNavigationEvent(
                eventId = eventId(span),
                runId = runId,
                traceId = span.traceId.hex(),
                spanId = span.spanId.hex(),
                parentSpanId = span.parentSpanId.hex().ifEmpty { null },
                component = component,
                startEpochNanos = span.startTimeUnixNano,
                durationNanos = span.endTimeUnixNano - span.startTimeUnixNano,
                url = attrs.string(StackAttributes.UI_URL) ?: "",
                route = attrs.string(StackAttributes.UI_ROUTE) ?: "",
                kind = kind,
            )
        }
    }

    private fun actionDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val event = when (attrs.string(StackAttributes.UI_ACTION_EVENT)) {
            "click" -> UiActionKind.CLICK
            "submit" -> UiActionKind.SUBMIT
            else -> return null
        }
        return EventDraft { runId ->
            UiActionEvent(
                eventId = eventId(span),
                runId = runId,
                traceId = span.traceId.hex(),
                spanId = span.spanId.hex(),
                parentSpanId = span.parentSpanId.hex().ifEmpty { null },
                component = component,
                startEpochNanos = span.startTimeUnixNano,
                durationNanos = span.endTimeUnixNano - span.startTimeUnixNano,
                event = event,
                target = attrs.string(StackAttributes.UI_ACTION_TARGET) ?: "",
                route = attrs.string(StackAttributes.UI_ROUTE) ?: "",
            )
        }
    }

    private fun headers(raw: String?): Map<String, List<String>> = decode(raw, HEADERS_TYPE) ?: emptyMap()

    private fun parameters(raw: String?): List<String?> = decode(raw, PARAMETERS_TYPE) ?: emptyList()

    private fun strings(raw: String?): List<String> = decode(raw, STRINGS_TYPE) ?: emptyList()

    /** Malformed JSON in a capture attribute must not lose the whole span, so it decodes to nothing. */
    private fun <T> decode(raw: String?, type: TypeReference<T>): T? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.readValue(raw, type)
        } catch (_: JacksonException) {
            null
        }
    }

    private fun eventId(span: Span) = "${span.traceId.hex()}-${span.spanId.hex()}"

    private fun ByteString.hex(): String = toByteArray().joinToString("") { "%02x".format(it) }

    private fun Map<String, AnyValue>.string(key: String): String? =
        this[key]?.takeIf { it.hasStringValue() }?.stringValue?.ifEmpty { null }

    private fun Map<String, AnyValue>.long(key: String): Long? = this[key]?.takeIf { it.hasIntValue() }?.intValue

    private fun Map<String, AnyValue>.boolean(key: String): Boolean = this[key]?.takeIf { it.hasBoolValue() }?.boolValue ?: false

    private companion object {
        const val UNKNOWN_SERVICE = "unknown-service"
        val HEADERS_TYPE = object : TypeReference<Map<String, List<String>>>() {}
        val PARAMETERS_TYPE = object : TypeReference<List<String?>>() {}
        val STRINGS_TYPE = object : TypeReference<List<String>>() {}
    }
}
