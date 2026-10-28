package io.stackvibed.reporter.otlp

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.trace.v1.Span
import io.stackvibed.agent.contract.StackAttributes
import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.RecordingEvent
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
 * Maps OTLP spans to recording events; spans that are neither an HTTP server nor a DB client span are dropped, and so are
 * DB client spans without a statement.
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
                        Span.SpanKind.SPAN_KIND_CLIENT -> dbDraft(span, attrs, component)
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

    private fun dbDraft(span: Span, attrs: Map<String, AnyValue>, component: String): EventDraft? {
        val dbSystem = attrs.string("db.system.name") ?: attrs.string("db.system") ?: return null
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

    private fun headers(raw: String?): Map<String, List<String>> = decode(raw, HEADERS_TYPE) ?: emptyMap()

    private fun parameters(raw: String?): List<String?> = decode(raw, PARAMETERS_TYPE) ?: emptyList()

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
    }
}
