package io.stackvibed.reporter.otlp

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import tools.jackson.databind.json.JsonMapper

/** Deterministic OTLP payloads shared by the mapper tests and the reporter golden tests. */
object OtlpFixtures {
    const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
    const val SERVER_SPAN_ID = "b7ad6b7169203331"
    const val INTERNAL_SPAN_ID = "c1d2e3f405162738"
    const val DB_SPAN_ID = "00f067aa0ba902b7"
    const val CLIENT_SPAN_ID = "a1b2c3d4e5f60718"
    const val INVENTORY_SERVER_SPAN_ID = "d4c3b2a190817263"
    const val INVENTORY_DB_SPAN_ID = "5e4d3c2b1a090817"
    const val CONTROLLER_SPAN_ID = "1a2b3c4d5e6f7081"
    const val SERVICE_SPAN_ID = "2b3c4d5e6f708192"
    const val RESERVE_SPAN_ID = "3c4d5e6f708192a3"
    const val REPOSITORY_SPAN_ID = "4d5e6f708192a3b4"

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

    const val SANDBOX_PACKAGE = "io.stackvibed.sandbox.orders"
    const val NEW_ORDER_JSON = """{"customerId":"c-1","sku":"sku-1","quantity":2}"""
    const val ORDER_JSON =
        """{"id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","customerId":"c-1","sku":"sku-1","quantity":2,"status":"CREATED","createdAt":"2026-10-27T10:00:00.000123Z"}"""
    const val SANDBOX_INSERT_STATEMENT = "insert into orders (id, customer_id, sku, quantity, status, created_at) values (?, ?, ?, ?, ?, ?)"
    const val HANDLER_THREAD = "http-nio-8080-exec-1"

    private val jsonStrings = JsonMapper.builder().build()

    /** A JSON array of strings, the way the agent encodes `stack.method.args` and `stack.method.param_names`. */
    fun jsonArray(vararg values: String): String = jsonStrings.writeValueAsString(values.toList())

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

    /**
     * The same scenario as the sandbox runs it with method capture on: orders-service's server span, then
     * OrdersController.create, OrderService.create, InventoryClient.reserve (with the reservation call under it) and
     * OrderRepository.insert (with the insert under it), listed in call order.
     */
    fun createOrderWithMethodsRun(scenario: String = "create-order", run: String = "run-3"): ExportTraceServiceRequest = request(
        "orders-service",
        listOf(
            serverSpan(scenario, run, requestBody = NEW_ORDER_JSON, responseBody = ORDER_JSON),
            methodSpan(
                CONTROLLER_SPAN_ID, SERVER_SPAN_ID, scenario, run, "OrdersController", "create",
                SERVER_START_NANOS + 1_000_000L, SERVER_END_NANOS - 1_000_000L,
                paramNames = listOf("newOrder"), args = listOf(NEW_ORDER_JSON),
                returnValue = """{"statusCode":"CREATED","headers":{"Location":["/orders/7c9e6679-7425-40de-944b-e07fc1f90ae7"]},"body":$ORDER_JSON}""",
            ),
            methodSpan(
                SERVICE_SPAN_ID, CONTROLLER_SPAN_ID, scenario, run, "OrderService", "create",
                SERVER_START_NANOS + 2_000_000L, SERVER_END_NANOS - 2_000_000L,
                paramNames = listOf("newOrder"), args = listOf(NEW_ORDER_JSON), returnValue = ORDER_JSON,
            ),
            methodSpan(
                RESERVE_SPAN_ID, SERVICE_SPAN_ID, scenario, run, "InventoryClient", "reserve",
                SERVER_START_NANOS + 3_000_000L, SERVER_START_NANOS + 32_000_000L,
                paramNames = listOf("sku", "quantity"), args = listOf("\"sku-1\"", "2"), returnValue = null,
            ),
            span(CLIENT_SPAN_ID, parent = RESERVE_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_CLIENT, name = "POST", SERVER_START_NANOS + 4_000_000L, SERVER_START_NANOS + 31_000_000L)
                .addAllAttributes(tags(scenario, run))
                .addAttributes(str("http.request.method", "POST"))
                .addAttributes(str("url.full", RESERVATION_URL))
                .addAttributes(str("server.address", "inventory-service"))
                .addAttributes(long("server.port", 8081))
                .addAttributes(long("http.response.status_code", 201))
                .build(),
            methodSpan(
                REPOSITORY_SPAN_ID, SERVICE_SPAN_ID, scenario, run, "OrderRepository", "insert",
                SERVER_START_NANOS + 33_000_000L, SERVER_START_NANOS + 39_000_000L,
                paramNames = listOf("order"), args = listOf(ORDER_JSON), returnValue = null,
            ),
            span(DB_SPAN_ID, parent = REPOSITORY_SPAN_ID, kind = Span.SpanKind.SPAN_KIND_CLIENT, name = "INSERT orders", SERVER_START_NANOS + 34_000_000L, SERVER_START_NANOS + 38_000_000L)
                .addAllAttributes(tags(scenario, run))
                .addAttributes(str("db.system.name", "postgresql"))
                .addAttributes(str("db.namespace", "orders"))
                .addAttributes(str("db.query.text", SANDBOX_INSERT_STATEMENT))
                .addAttributes(str("stack.db.parameters", jsonArray("7c9e6679-7425-40de-944b-e07fc1f90ae7", "c-1", "sku-1", "2", "CREATED", "2026-10-27T10:00:00.000123Z")))
                .addAttributes(long("stack.db.row_count", 1))
                .build(),
        ),
    )

    /**
     * An INTERNAL span as the agent writes it for a method call: [paramNames] null leaves the attribute out (no names in the
     * class file), [returnValue] null leaves it out (void, or the method threw), and an exception sets both exception attributes.
     */
    fun methodSpan(
        spanId: String,
        parent: String?,
        scenario: String?,
        run: String?,
        simpleClass: String,
        method: String,
        startNanos: Long,
        endNanos: Long,
        paramNames: List<String>?,
        args: List<String>,
        returnValue: String?,
        exceptionType: String? = null,
        exceptionMessage: String? = null,
        thread: String? = HANDLER_THREAD,
    ): Span =
        span(spanId, parent, Span.SpanKind.SPAN_KIND_INTERNAL, "$simpleClass.$method", startNanos, endNanos)
            .addAllAttributes(tags(scenario, run))
            .addAttributes(str("stack.method.class", "$SANDBOX_PACKAGE.$simpleClass"))
            .addAttributes(str("stack.method.name", method))
            .apply {
                if (paramNames != null) addAttributes(str("stack.method.param_names", jsonArray(*paramNames.toTypedArray())))
                addAttributes(str("stack.method.args", jsonArray(*args.toTypedArray())))
                if (returnValue != null) addAttributes(str("stack.method.return", returnValue))
                if (exceptionType != null) addAttributes(str("stack.method.exception.type", exceptionType))
                if (exceptionMessage != null) addAttributes(str("stack.method.exception.message", exceptionMessage))
                if (thread != null) addAttributes(str("stack.method.thread", thread))
            }
            .build()

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

    fun serverSpan(
        scenario: String? = "create-order",
        run: String? = "run-1",
        requestBody: String = REQUEST_BODY,
        responseBody: String = RESPONSE_BODY,
    ): Span =
        span(SERVER_SPAN_ID, parent = null, kind = Span.SpanKind.SPAN_KIND_SERVER, name = "POST /orders", SERVER_START_NANOS, SERVER_END_NANOS)
            .addAllAttributes(tags(scenario, run))
            .addAttributes(str("http.request.method", "POST"))
            .addAttributes(str("http.route", "/orders"))
            .addAttributes(str("url.path", "/orders"))
            .addAttributes(long("http.response.status_code", 201))
            .addAttributes(str("stack.http.request.headers", """{"content-type":["application/json"],"x-multi":["a","b"]}"""))
            .addAttributes(str("stack.http.response.headers", """{"content-type":["application/json"]}"""))
            .addAttributes(str("stack.http.request.body", requestBody))
            .addAttributes(str("stack.http.response.body", responseBody))
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
