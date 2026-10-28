package io.stackvibed.reporter.otlp

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.proto.trace.v1.Span
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.RecordingEvent
import org.junit.jupiter.api.Test

class SpanEventMapperTest {
    private val mapper = SpanEventMapper()

    private fun events(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest): List<RecordingEvent> =
        mapper.map(request).map { it.toEvent.withRun("r") }

    private fun http(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest) =
        events(request).filterIsInstance<HttpServerEvent>().single()

    private fun db(request: io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest) =
        events(request).filterIsInstance<DbClientEvent>().single()

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

    @Test fun `internal and unknown spans are dropped`() {
        val request = OtlpFixtures.createOrderRun()
        request.resourceSpansList.single().scopeSpansList.single().spansList.map { it.kind } shouldBe
            listOf(Span.SpanKind.SPAN_KIND_SERVER, Span.SpanKind.SPAN_KIND_INTERNAL, Span.SpanKind.SPAN_KIND_CLIENT)
        val mapped = events(request)
        mapped shouldHaveSize 2
        mapped.map { it.spanId } shouldBe listOf(OtlpFixtures.SERVER_SPAN_ID, OtlpFixtures.DB_SPAN_ID)
    }

    @Test fun `server span without http method and client span without db system are dropped`() {
        val plainServer = OtlpFixtures.span("1111111111111111", null, Span.SpanKind.SPAN_KIND_SERVER, "x", 1, 2).build()
        val plainClient = OtlpFixtures.span("2222222222222222", null, Span.SpanKind.SPAN_KIND_CLIENT, "GET", 1, 2)
            .addAttributes(OtlpFixtures.str("http.request.method", "GET")).build()
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
}
