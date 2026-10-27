package io.stackvibed.reporter.otlp

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span

/** Deterministic OTLP payloads shared by the mapper tests and the reporter golden tests. */
object OtlpFixtures {
    const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
    const val SERVER_SPAN_ID = "b7ad6b7169203331"
    const val INTERNAL_SPAN_ID = "c1d2e3f405162738"
    const val DB_SPAN_ID = "00f067aa0ba902b7"

    const val SERVER_START_NANOS = 1_760_000_000_000_000_000L
    const val SERVER_END_NANOS = SERVER_START_NANOS + 42_000_000L
    const val DB_START_NANOS = SERVER_START_NANOS + 10_000_000L
    const val DB_END_NANOS = DB_START_NANOS + 5_000_000L

    const val REQUEST_BODY = """{"customerId":"c-1","items":[{"sku":"A-1","quantity":2}]}"""
    const val RESPONSE_BODY = """{"id":1,"status":"CREATED"}"""
    const val INSERT_STATEMENT = "insert into orders (customer_id, status, total, created_at) values (?, ?, ?, ?)"

    fun createOrderRun(scenario: String = "create-order", run: String = "run-1"): ExportTraceServiceRequest =
        request("orders-service", listOf(serverSpan(scenario, run), internalSpan(scenario, run), dbSpan(scenario, run, oldSemconv = false)))

    /** Same run, but the database span uses the pre-1.33 semantic convention names. */
    fun createOrderRunOldDbSemconv(scenario: String = "create-order", run: String = "run-1"): ExportTraceServiceRequest =
        request("orders-service", listOf(serverSpan(scenario, run), internalSpan(scenario, run), dbSpan(scenario, run, oldSemconv = true)))

    fun request(serviceName: String?, spans: List<Span>): ExportTraceServiceRequest {
        val resource = Resource.newBuilder()
        if (serviceName != null) resource.addAttributes(str("service.name", serviceName))
        return ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(
                ResourceSpans.newBuilder()
                    .setResource(resource)
                    .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(spans)),
            )
            .build()
    }

    fun serverSpan(scenario: String? = "create-order", run: String? = "run-1"): Span =
        span(SERVER_SPAN_ID, parent = null, kind = Span.SpanKind.SPAN_KIND_SERVER, name = "POST /orders", SERVER_START_NANOS, SERVER_END_NANOS)
            .addAllAttributes(tags(scenario, run))
            .addAttributes(str("http.request.method", "POST"))
            .addAttributes(str("http.route", "/orders"))
            .addAttributes(str("url.path", "/orders"))
            .addAttributes(long("http.response.status_code", 201))
            .addAttributes(str("stack.http.request.headers", """{"content-type":["application/json"],"x-multi":["a","b"]}"""))
            .addAttributes(str("stack.http.response.headers", """{"content-type":["application/json"]}"""))
            .addAttributes(str("stack.http.request.body", REQUEST_BODY))
            .addAttributes(str("stack.http.response.body", RESPONSE_BODY))
            .addAttributes(bool("stack.http.request.body_truncated", false))
            .addAttributes(bool("stack.http.response.body_truncated", false))
            .build()

    private fun internalSpan(scenario: String?, run: String?): Span =
        span(INTERNAL_SPAN_ID, parent = SERVER_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_INTERNAL, name = "OrderController.create", SERVER_START_NANOS + 1_000_000L, SERVER_END_NANOS - 1_000_000L)
            .addAllAttributes(tags(scenario, run))
            .build()

    fun dbSpan(scenario: String? = "create-order", run: String? = "run-1", oldSemconv: Boolean = false): Span =
        span(DB_SPAN_ID, parent = INTERNAL_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_CLIENT, name = "INSERT orders", DB_START_NANOS, DB_END_NANOS)
            .addAllAttributes(tags(scenario, run))
            .apply {
                if (oldSemconv) {
                    addAttributes(str("db.system", "postgresql"))
                    addAttributes(str("db.name", "orders"))
                    addAttributes(str("db.statement", INSERT_STATEMENT))
                } else {
                    addAttributes(str("db.system.name", "postgresql"))
                    addAttributes(str("db.namespace", "orders"))
                    addAttributes(str("db.query.text", INSERT_STATEMENT))
                }
            }
            .addAttributes(str("stack.db.parameters", """["c-1","CREATED","19.98","2026-10-27T10:00:00Z"]"""))
            .addAttributes(long("stack.db.row_count", 1))
            .build()

    fun span(spanId: String, parent: String?, kind: Span.SpanKind, name: String, startNanos: Long, endNanos: Long): Span.Builder =
        Span.newBuilder()
            .setTraceId(hex(TRACE_ID))
            .setSpanId(hex(spanId))
            .apply { if (parent != null) setParentSpanId(hex(parent)) }
            .setKind(kind)
            .setName(name)
            .setStartTimeUnixNano(startNanos)
            .setEndTimeUnixNano(endNanos)

    fun tags(scenario: String?, run: String?): List<KeyValue> =
        listOfNotNull(scenario?.let { str("stack.scenario", it) }, run?.let { str("stack.run", it) })

    fun str(key: String, value: String): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    fun long(key: String, value: Long): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build()

    fun bool(key: String, value: Boolean): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setBoolValue(value)).build()

    private fun hex(value: String): ByteString = ByteString.copyFrom(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}
