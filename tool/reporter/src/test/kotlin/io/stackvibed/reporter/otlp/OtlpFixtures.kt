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
    const val CLIENT_SPAN_ID = "a1b2c3d4e5f60718"
    const val INVENTORY_SERVER_SPAN_ID = "d4c3b2a190817263"
    const val INVENTORY_DB_SPAN_ID = "5e4d3c2b1a090817"

    const val SERVER_START_NANOS = 1_760_000_000_000_000_000L
    const val SERVER_END_NANOS = SERVER_START_NANOS + 42_000_000L
    const val DB_START_NANOS = SERVER_START_NANOS + 10_000_000L
    const val DB_END_NANOS = DB_START_NANOS + 5_000_000L
    const val CLIENT_START_NANOS = SERVER_START_NANOS + 2_000_000L
    const val CLIENT_END_NANOS = CLIENT_START_NANOS + 28_000_000L

    const val REQUEST_BODY = """{"customerId":"c-1","items":[{"sku":"A-1","quantity":2}]}"""
    const val RESPONSE_BODY = """{"id":1,"status":"CREATED"}"""
    const val INSERT_STATEMENT = "insert into orders (customer_id, status, total, created_at) values (?, ?, ?, ?)"
    const val RESERVATION_URL = "http://inventory-service:8081/reservations"
    const val RESERVE_STATEMENT = "update stock set reserved = reserved + ? where sku = ?"

    fun createOrderRun(scenario: String = "create-order", run: String = "run-1"): ExportTraceServiceRequest =
        request("orders-service", listOf(serverSpan(scenario, run), internalSpan(scenario, run), dbSpan(scenario, run, oldSemconv = false)))

    /** Same run, but the database span uses the pre-1.33 semantic convention names. */
    fun createOrderRunOldDbSemconv(scenario: String = "create-order", run: String = "run-1"): ExportTraceServiceRequest =
        request("orders-service", listOf(serverSpan(scenario, run), internalSpan(scenario, run), dbSpan(scenario, run, oldSemconv = true)))

    /**
     * One run seen by two services, delivered as the two OTLP requests each service's agent would send: orders-service
     * (server span and the client span of its reservation call) and inventory-service (the callee's server span and its update).
     */
    fun createOrderReservesRun(scenario: String = "create-order", run: String = "run-2"): List<ExportTraceServiceRequest> = listOf(
        request("orders-service", listOf(serverSpan(scenario, run), clientSpan(scenario, run))),
        request("inventory-service", listOf(inventoryServerSpan(scenario, run), inventoryDbSpan(scenario, run))),
    )

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

    /** The reservation call as the agent records it: parent is the orders server span, the callee's server span is its child. */
    fun clientSpan(scenario: String? = "create-order", run: String? = "run-2"): Span =
        span(CLIENT_SPAN_ID, parent = SERVER_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_CLIENT, name = "POST", CLIENT_START_NANOS, CLIENT_END_NANOS)
            .addAllAttributes(tags(scenario, run))
            .addAttributes(str("http.request.method", "POST"))
            .addAttributes(str("url.full", RESERVATION_URL))
            .addAttributes(str("server.address", "inventory-service"))
            .addAttributes(long("server.port", 8081))
            .addAttributes(long("http.response.status_code", 201))
            .build()

    private fun inventoryServerSpan(scenario: String?, run: String?): Span =
        span(INVENTORY_SERVER_SPAN_ID, parent = CLIENT_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_SERVER, name = "POST /reservations", SERVER_START_NANOS + 4_000_000L, SERVER_START_NANOS + 28_000_000L)
            .addAllAttributes(tags(scenario, run))
            .addAttributes(str("http.request.method", "POST"))
            .addAttributes(str("http.route", "/reservations"))
            .addAttributes(str("url.path", "/reservations"))
            .addAttributes(long("http.response.status_code", 201))
            .addAttributes(str("stack.http.request.body", """{"items":[{"sku":"sku-1","quantity":2}]}"""))
            .addAttributes(str("stack.http.response.body", """{"reserved":true}"""))
            .build()

    private fun inventoryDbSpan(scenario: String?, run: String?): Span =
        span(INVENTORY_DB_SPAN_ID, parent = INVENTORY_SERVER_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_CLIENT, name = "UPDATE stock", SERVER_START_NANOS + 8_000_000L, SERVER_START_NANOS + 12_000_000L)
            .addAllAttributes(tags(scenario, run))
            .addAttributes(str("db.system.name", "postgresql"))
            .addAttributes(str("db.namespace", "inventory"))
            .addAttributes(str("db.query.text", RESERVE_STATEMENT))
            .addAttributes(str("stack.db.parameters", """["2","sku-1"]"""))
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
