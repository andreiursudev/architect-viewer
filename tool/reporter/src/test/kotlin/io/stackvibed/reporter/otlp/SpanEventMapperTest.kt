package io.stackvibed.reporter.otlp

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.proto.trace.v1.Span
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.MethodEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.UiActionEvent
import io.stackvibed.model.UiActionKind
import io.stackvibed.model.UiNavigationEvent
import io.stackvibed.model.UiNavigationKind
import org.junit.jupiter.api.Test

class SpanEventMapperTest {
    private val mapper = SpanEventMapper()

    private fun events(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest): List<RecordingEvent> =
        mapper.map(request).map { it.toEvent.withRun("r") }

    private fun http(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest) =
        events(request).filterIsInstance<HttpServerEvent>().single()

    private fun db(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest) =
        events(request).filterIsInstance<DbClientEvent>().single()

    private fun client(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest) =
        events(request).filterIsInstance<HttpClientEvent>().single()

    private fun method(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest) =
        events(request).filterIsInstance<MethodEvent>().single()

    private fun methodRequest(span: Span) = OtlpFixtures.request("orders-service", listOf(span))

    @Test fun `server span becomes http event`() {
        val event = http(OtlpFixtures.createOrderRun())
        event.component shouldBe "orders-service"
        event.method shouldBe "POST"
        event.route shouldBe "/orders"
        event.path shouldBe "/orders"
        event.status shouldBe 201
        event.requestBody shouldContain "\"customerId\""
        event.responseBody shouldBe OtlpFixtures.RESPONSE_BODY
        event.requestBodyTruncated shouldBe false
        event.responseBodyTruncated shouldBe false
        event.requestHeaders shouldBe mapOf("content-type" to listOf("application/json"), "x-multi" to listOf("a", "b"))
        event.responseHeaders shouldBe mapOf("content-type" to listOf("application/json"))
        event.eventId shouldBe "${OtlpFixtures.TRACE_ID}-${OtlpFixtures.SERVER_SPAN_ID}"
        event.traceId shouldBe OtlpFixtures.TRACE_ID
        event.spanId shouldBe OtlpFixtures.SERVER_SPAN_ID
        event.parentSpanId shouldBe null
        event.runId shouldBe "r"
        event.startEpochNanos shouldBe OtlpFixtures.SERVER_START_NANOS
        event.durationNanos shouldBe 42_000_000L
    }

    @Test fun `db client span becomes db event with parameters`() {
        val event = db(OtlpFixtures.createOrderRun())
        event.component shouldBe "orders-service"
        event.dbSystem shouldBe "postgresql"
        event.dbName shouldBe "orders"
        event.statement shouldBe OtlpFixtures.INSERT_STATEMENT
        event.parameters shouldHaveSize 4
        event.parameters shouldBe listOf("c-1", "CREATED", "19.98", "2026-10-27T10:00:00Z")
        event.rowCount shouldBe 1L
        event.eventId shouldBe "${OtlpFixtures.TRACE_ID}-${OtlpFixtures.DB_SPAN_ID}"
        event.parentSpanId shouldBe OtlpFixtures.INTERNAL_SPAN_ID
        event.durationNanos shouldBe 5_000_000L
    }

    @Test fun `client span becomes http client event`() {
        val event = client(OtlpFixtures.request("orders-service", listOf(OtlpFixtures.clientSpan())))
        event.component shouldBe "orders-service"
        event.method shouldBe "POST"
        event.url shouldBe OtlpFixtures.RESERVATION_URL
        event.serverAddress shouldBe "inventory-service"
        event.serverPort shouldBe 8081
        event.status shouldBe 201
        event.errorType shouldBe null
        event.eventId shouldBe "${OtlpFixtures.TRACE_ID}-${OtlpFixtures.CLIENT_SPAN_ID}"
        event.spanId shouldBe OtlpFixtures.CLIENT_SPAN_ID
        event.parentSpanId shouldBe OtlpFixtures.SERVER_SPAN_ID
        event.runId shouldBe "r"
        event.startEpochNanos shouldBe OtlpFixtures.CLIENT_START_NANOS
        event.durationNanos shouldBe 28_000_000L
    }

    @Test fun `client span without a response has null status and keeps its error type`() {
        val failed = OtlpFixtures.span("3333333333333333", OtlpFixtures.SERVER_SPAN_ID, Span.SpanKind.SPAN_KIND_CLIENT, "POST", 1, 2)
            .addAttributes(OtlpFixtures.str("http.request.method", "POST"))
            .addAttributes(OtlpFixtures.str("url.full", OtlpFixtures.RESERVATION_URL))
            .addAttributes(OtlpFixtures.str("error.type", "java.net.ConnectException"))
            .build()
        val event = client(OtlpFixtures.request("orders-service", listOf(failed)))
        event.status shouldBe null
        event.errorType shouldBe "java.net.ConnectException"
        event.serverAddress shouldBe null
        event.serverPort shouldBe null
    }

    @Test fun `tags come from stack attributes`() {
        mapper.map(OtlpFixtures.createOrderRun()).map { it.tags }.distinct() shouldBe listOf(SpanTags("create-order", "run-1"))
        mapper.map(OtlpFixtures.createOrderRun("other", "run-9")).map { it.tags }.distinct() shouldBe listOf(SpanTags("other", "run-9"))
    }

    @Test fun `missing stack attributes give null tags`() {
        val request = OtlpFixtures.request("orders-service", listOf(OtlpFixtures.serverSpan(scenario = null, run = null)))
        mapper.map(request).single().tags shouldBe SpanTags(null, null)
    }

    @Test fun `old db semconv attribute names are accepted`() {
        val event = db(OtlpFixtures.createOrderRunOldDbSemconv())
        event.dbSystem shouldBe "postgresql"
        event.dbName shouldBe "orders"
        event.statement shouldBe OtlpFixtures.INSERT_STATEMENT
    }

    @Test fun `internal span with method attributes becomes method event`() {
        val span = OtlpFixtures.methodSpan(
            OtlpFixtures.SERVICE_SPAN_ID, OtlpFixtures.CONTROLLER_SPAN_ID, "create-order", "run-1", "OrderService", "create",
            OtlpFixtures.SERVER_START_NANOS + 2_000_000L, OtlpFixtures.SERVER_START_NANOS + 40_000_000L,
            paramNames = listOf("newOrder", "dryRun"), args = listOf(OtlpFixtures.NEW_ORDER_JSON, "false"), returnValue = OtlpFixtures.ORDER_JSON,
        )
        val event = method(methodRequest(span))
        event.component shouldBe "orders-service"
        event.className shouldBe "io.stackvibed.sandbox.orders.OrderService"
        event.methodName shouldBe "create"
        event.parameterNames shouldBe listOf("newOrder", "dryRun")
        event.arguments shouldBe listOf(OtlpFixtures.NEW_ORDER_JSON, "false")
        event.returnValue shouldBe OtlpFixtures.ORDER_JSON
        event.exceptionType shouldBe null
        event.exceptionMessage shouldBe null
        event.thread shouldBe OtlpFixtures.HANDLER_THREAD
        event.eventId shouldBe "${OtlpFixtures.TRACE_ID}-${OtlpFixtures.SERVICE_SPAN_ID}"
        event.traceId shouldBe OtlpFixtures.TRACE_ID
        event.spanId shouldBe OtlpFixtures.SERVICE_SPAN_ID
        event.parentSpanId shouldBe OtlpFixtures.CONTROLLER_SPAN_ID
        event.runId shouldBe "r"
        event.startEpochNanos shouldBe OtlpFixtures.SERVER_START_NANOS + 2_000_000L
        event.durationNanos shouldBe 38_000_000L
    }

    @Test fun `method span that threw has exception and no return`() {
        val span = OtlpFixtures.methodSpan(
            OtlpFixtures.RESERVE_SPAN_ID, OtlpFixtures.SERVICE_SPAN_ID, "create-order", "run-1", "InventoryClient", "reserve",
            1, 2, paramNames = listOf("sku", "quantity"), args = listOf("\"sku-1\"", "2"), returnValue = null,
            exceptionType = "org.springframework.web.server.ResponseStatusException",
            exceptionMessage = "409 CONFLICT \"not enough stock for sku-1\"",
        )
        val event = method(methodRequest(span))
        event.returnValue shouldBe null
        event.exceptionType shouldBe "org.springframework.web.server.ResponseStatusException"
        event.exceptionMessage shouldBe "409 CONFLICT \"not enough stock for sku-1\""
        event.arguments shouldBe listOf("\"sku-1\"", "2")
    }

    @Test fun `method span without parameter names or with malformed json maps to empty lists`() {
        val noNames = OtlpFixtures.methodSpan(
            "1111111111111111", null, null, null, "Clock", "now", 1, 2, paramNames = null, args = emptyList(), returnValue = "\"x\"", thread = null,
        )
        method(methodRequest(noNames)).apply {
            parameterNames shouldBe emptyList()
            arguments shouldBe emptyList()
            returnValue shouldBe "\"x\""
            thread shouldBe null
        }

        val malformed = OtlpFixtures.span("2222222222222222", null, Span.SpanKind.SPAN_KIND_INTERNAL, "X.y", 1, 2)
            .addAttributes(OtlpFixtures.str("stack.method.class", "a.X"))
            .addAttributes(OtlpFixtures.str("stack.method.name", "y"))
            .addAttributes(OtlpFixtures.str("stack.method.param_names", "[\"a\""))
            .addAttributes(OtlpFixtures.str("stack.method.args", "not json"))
            .build()
        method(methodRequest(malformed)).apply {
            className shouldBe "a.X"
            parameterNames shouldBe emptyList()
            arguments shouldBe emptyList()
        }
    }

    @Test fun `internal span without method attributes is dropped`() {
        val plain = OtlpFixtures.span("1111111111111111", null, Span.SpanKind.SPAN_KIND_INTERNAL, "Something.happens", 1, 2).build()
        val nameOnly = OtlpFixtures.span("2222222222222222", null, Span.SpanKind.SPAN_KIND_INTERNAL, "Other.thing", 1, 2)
            .addAttributes(OtlpFixtures.str("stack.method.name", "thing"))
            .build()
        events(OtlpFixtures.request("orders-service", listOf(plain, nameOnly))) shouldBe emptyList()
    }

    @Test fun `internal and unknown spans are dropped`() {
        val request = OtlpFixtures.createOrderRun()
        request.resourceSpansList.single().scopeSpansList.single().spansList.map { it.kind } shouldBe
            listOf(Span.SpanKind.SPAN_KIND_SERVER, Span.SpanKind.SPAN_KIND_INTERNAL, Span.SpanKind.SPAN_KIND_CLIENT)
        val mapped = events(request)
        mapped shouldHaveSize 2
        mapped.map { it.spanId } shouldBe listOf(OtlpFixtures.SERVER_SPAN_ID, OtlpFixtures.DB_SPAN_ID)
    }

    @Test fun `server span without http method and client span with neither db system nor http method are dropped`() {
        val plainServer = OtlpFixtures.span("1111111111111111", null, Span.SpanKind.SPAN_KIND_SERVER, "x", 1, 2).build()
        val plainClient = OtlpFixtures.span("2222222222222222", null, Span.SpanKind.SPAN_KIND_CLIENT, "publish", 1, 2).build()
        events(OtlpFixtures.request("orders-service", listOf(plainServer, plainClient))) shouldBe emptyList()
    }

    @Test fun `db client span with a missing or blank statement is dropped`() {
        // The connection pool validates idle connections with an empty query; that is not a step of the scenario.
        val spans = listOf(null, "", "   ").mapIndexed { i, statement ->
            OtlpFixtures.span("${i + 1}".repeat(16), null, Span.SpanKind.SPAN_KIND_CLIENT, "postgresql", 1, 2)
                .addAttributes(OtlpFixtures.str("db.system.name", "postgresql"))
                .apply { if (statement != null) addAttributes(OtlpFixtures.str("db.query.text", statement)) }
                .build()
        }
        events(OtlpFixtures.request("orders-service", spans)) shouldBe emptyList()
    }

    @Test fun `missing headers and parameters map to empty collections and update-less row count to null`() {
        val server = OtlpFixtures.span("1111111111111111", null, Span.SpanKind.SPAN_KIND_SERVER, "GET /ping", 1, 11)
            .addAttributes(OtlpFixtures.str("http.request.method", "GET"))
            .addAttributes(OtlpFixtures.str("url.path", "/ping"))
            .addAttributes(OtlpFixtures.long("http.response.status_code", 200))
            .build()
        val client = OtlpFixtures.span("2222222222222222", "1111111111111111", Span.SpanKind.SPAN_KIND_CLIENT, "SELECT", 2, 5)
            .addAttributes(OtlpFixtures.str("db.system.name", "postgresql"))
            .addAttributes(OtlpFixtures.str("db.query.text", "select 1"))
            .build()
        val request = OtlpFixtures.request("orders-service", listOf(server, client))
        val httpEvent = http(request)
        httpEvent.requestHeaders shouldBe emptyMap()
        httpEvent.responseHeaders shouldBe emptyMap()
        httpEvent.requestBody shouldBe null
        httpEvent.responseBody shouldBe null
        httpEvent.route shouldBe null
        val dbEvent = db(request)
        dbEvent.parameters shouldBe emptyList()
        dbEvent.rowCount shouldBe null
        dbEvent.dbName shouldBe null
    }

    @Test fun `missing or blank service name maps to unknown-service`() {
        for (name in listOf(null, "", "   ", "!!!")) {
            http(OtlpFixtures.request(name, listOf(OtlpFixtures.serverSpan()))).component shouldBe "unknown-service"
        }
    }

    @Test fun `component id is the slug of the service name`() {
        http(OtlpFixtures.request("Orders Service", listOf(OtlpFixtures.serverSpan()))).component shouldBe "orders-service"
    }

    @Test fun `browser navigation and action spans map to ui events`() {
        val ui = events(OtlpFixtures.browserPlaceOrderRun()).filter { it is UiNavigationEvent || it is UiActionEvent }
        ui.map { it.spanId } shouldBe listOf(
            OtlpFixtures.BROWSER_LOAD_SPAN_ID,
            OtlpFixtures.BROWSER_CLICK_SPAN_ID,
            OtlpFixtures.BROWSER_ORDER_ROUTE_SPAN_ID,
            OtlpFixtures.BROWSER_SUBMIT_SPAN_ID,
            OtlpFixtures.BROWSER_ORDER_PAGE_ROUTE_SPAN_ID,
        )

        val load = ui[0] as UiNavigationEvent
        load.component shouldBe "frontend"
        load.kind shouldBe UiNavigationKind.LOAD
        load.url shouldBe "http://localhost:3000/"
        load.route shouldBe "/"
        load.eventId shouldBe "${OtlpFixtures.TRACE_ID}-${OtlpFixtures.BROWSER_LOAD_SPAN_ID}"
        load.parentSpanId shouldBe null
        load.runId shouldBe "r"
        load.startEpochNanos shouldBe OtlpFixtures.BROWSER_START_NANOS
        load.durationNanos shouldBe 120_000_000L

        val click = ui[1] as UiActionEvent
        click.component shouldBe "frontend"
        click.event shouldBe UiActionKind.CLICK
        click.target shouldBe "to-order"
        click.route shouldBe "/"
        click.durationNanos shouldBe 1_000_000L

        val route = ui[2] as UiNavigationEvent
        route.kind shouldBe UiNavigationKind.ROUTE
        route.url shouldBe "http://localhost:3000/order"
        route.route shouldBe "/order"

        val submit = ui[3] as UiActionEvent
        submit.event shouldBe UiActionKind.SUBMIT
        submit.target shouldBe "order-form"
        submit.route shouldBe "/order"

        (ui[4] as UiNavigationEvent).route shouldBe "/orders/${OtlpFixtures.ORDER_ID}"
    }

    @Test fun `ui spans without url route or target map to empty strings`() {
        val navigation = OtlpFixtures.navigationSpan("1111111111111111", null, null, "load", url = null, route = null, 1, 2)
        val action = OtlpFixtures.actionSpan("2222222222222222", null, null, "click", target = null, route = null, 3, 4)
        val mapped = events(OtlpFixtures.request("frontend", listOf(navigation, action)))
        (mapped[0] as UiNavigationEvent).apply {
            url shouldBe ""
            route shouldBe ""
        }
        (mapped[1] as UiActionEvent).apply {
            target shouldBe ""
            route shouldBe ""
        }
    }

    @Test fun `browser fetch maps to http client event of the frontend`() {
        val fetches = events(OtlpFixtures.browserPlaceOrderRun()).filterIsInstance<HttpClientEvent>()
        fetches.map { it.spanId } shouldBe listOf(
            OtlpFixtures.BROWSER_STOCK_FETCH_SPAN_ID,
            OtlpFixtures.BROWSER_POST_FETCH_SPAN_ID,
            OtlpFixtures.BROWSER_GET_FETCH_SPAN_ID,
        )
        fetches.forEach { it.component shouldBe "frontend" }
        fetches.map { it.method to it.url } shouldBe listOf(
            "GET" to "http://localhost:3000/stock",
            "POST" to "http://localhost:3000/orders",
            "GET" to "http://localhost:3000/orders/${OtlpFixtures.ORDER_ID}",
        )
        fetches.map { it.status } shouldBe listOf(200, 201, 200)
        fetches[0].serverAddress shouldBe "localhost"
        fetches[0].serverPort shouldBe 3000
    }

    @Test fun `unknown web spans are dropped`() {
        val request = OtlpFixtures.browserPlaceOrderRun()
        request.resourceSpansList.single().scopeSpansList.single().spansList.map { it.name }.contains("documentFetch") shouldBe true
        val mapped = events(request)
        mapped shouldHaveSize 8
        mapped.map { it.spanId } shouldNotContain OtlpFixtures.BROWSER_DOCUMENT_FETCH_SPAN_ID
    }

    @Test fun `ui span with an unknown kind is dropped`() {
        val spans = listOf(
            OtlpFixtures.navigationSpan("1111111111111111", "s", "r", "reload", "http://localhost:3000/", "/", 1, 2),
            OtlpFixtures.navigationSpan("2222222222222222", "s", "r", kind = null, "http://localhost:3000/", "/", 1, 2),
            OtlpFixtures.actionSpan("3333333333333333", "s", "r", "hover", "to-order", "/", 1, 2),
            OtlpFixtures.actionSpan("4444444444444444", "s", "r", event = null, "to-order", "/", 1, 2),
        )
        events(OtlpFixtures.request("frontend", spans)) shouldBe emptyList()
    }
}
