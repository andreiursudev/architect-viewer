package io.stackvibed.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.stackvibed.model.view.FlowStepKind
import io.stackvibed.model.workspace.ConnectionKind
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.time.Instant

class EventJsonTest {
    private val mapper = jacksonMapperBuilder().build()

    @Test
    fun `http event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleHttpEvent)
        json shouldContain "\"type\":\"http.server\""
        mapper.readValue<RecordingEvent>(json) shouldBe sampleHttpEvent
    }

    @Test
    fun `db event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleDbEvent)
        json shouldContain "\"type\":\"db.client\""
        mapper.readValue<RecordingEvent>(json) shouldBe sampleDbEvent
    }

    @Test
    fun `httpClientEventRoundTrips`() {
        val json = mapper.writeValueAsString(sampleClientEvent)
        json shouldContain "\"type\":\"http.client\""
        json shouldContain "\"schemaVersion\":$SCHEMA_VERSION"
        mapper.readValue<RecordingEvent>(json) shouldBe sampleClientEvent
    }

    @Test
    fun `method event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleMethodEvent)
        json shouldContain "\"type\":\"method\""
        json shouldContain "\"schemaVersion\":$SCHEMA_VERSION"
        mapper.readValue<RecordingEvent>(json) shouldBe sampleMethodEvent
    }

    @Test
    fun `ui navigation event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleUiNavigationEvent)
        json shouldContain "\"type\":\"ui.navigation\""
        json shouldContain "\"kind\":\"route\""
        json shouldContain "\"schemaVersion\":$SCHEMA_VERSION"
        mapper.readValue<RecordingEvent>(json) shouldBe sampleUiNavigationEvent
    }

    @Test
    fun `ui action event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleUiActionEvent)
        json shouldContain "\"type\":\"ui.action\""
        json shouldContain "\"event\":\"submit\""
        json shouldContain "\"schemaVersion\":$SCHEMA_VERSION"
        mapper.readValue<RecordingEvent>(json) shouldBe sampleUiActionEvent
    }

    @Test
    fun `kafka produce event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleKafkaProduceEvent)
        json shouldContain "\"type\":\"kafka.produce\""
        json shouldContain "\"schemaVersion\":5"
        mapper.readValue<RecordingEvent>(json) shouldBe sampleKafkaProduceEvent
    }

    @Test
    fun `kafka consume event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleKafkaConsumeEvent)
        json shouldContain "\"type\":\"kafka.consume\""
        json shouldContain "\"consumerGroup\":\"shipping\""
        mapper.readValue<RecordingEvent>(json) shouldBe sampleKafkaConsumeEvent
    }

    @Test
    fun `version 4 lines still read`() {
        val line = """{"type":"http.server","schemaVersion":4,"eventId":"0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331","runId":"run-4","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"b7ad6b7169203331","parentSpanId":null,"component":"orders-service","startEpochNanos":1760000000000000000,"durationNanos":42000000,"method":"POST","route":"/orders","path":"/orders","status":201,"requestHeaders":{},"responseHeaders":{},"requestBody":null,"responseBody":null,"requestBodyTruncated":false,"responseBodyTruncated":false}"""
        val event = mapper.readValue<RecordingEvent>(line)
        event.schemaVersion shouldBe 4
        event.shouldBeInstanceOf<HttpServerEvent>()
    }

    @Test
    fun `kafka kinds and topic type serialise lowercase`() {
        mapper.writeValueAsString(ComponentType.TOPIC) shouldBe "\"topic\""
        mapper.writeValueAsString(FlowStepKind.KAFKA) shouldBe "\"kafka\""
        mapper.writeValueAsString(ConnectionKind.KAFKA) shouldBe "\"kafka\""
    }

    @Test
    fun `version 3 lines still read`() {
        // A literal method line from the reporter golden file recorded before schema version 4.
        val line = """{"type":"method","schemaVersion":3,"eventId":"0af7651916cd43dd8448eb211c80319c-1a2b3c4d5e6f7081","runId":"run-3","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"1a2b3c4d5e6f7081","parentSpanId":"b7ad6b7169203331","component":"orders-service","startEpochNanos":1760000000001000000,"durationNanos":40000000,"className":"io.stackvibed.sandbox.orders.OrdersController","methodName":"create","parameterNames":["newOrder"],"arguments":["{\"customerId\":\"c-1\",\"sku\":\"sku-1\",\"quantity\":2}"],"returnValue":null,"exceptionType":null,"exceptionMessage":null,"thread":"http-nio-8080-exec-1"}"""
        val event = mapper.readValue<RecordingEvent>(line)
        event.schemaVersion shouldBe 3
        event.shouldBeInstanceOf<MethodEvent>()
    }

    @Test
    fun `frontend type and ui kinds serialise lowercase`() {
        mapper.writeValueAsString(ComponentType.FRONTEND) shouldBe "\"frontend\""
        mapper.writeValueAsString(FlowStepKind.UI) shouldBe "\"ui\""
        mapper.writeValueAsString(ConnectionKind.UI) shouldBe "\"ui\""
        mapper.writeValueAsString(UiNavigationKind.LOAD) shouldBe "\"load\""
        mapper.writeValueAsString(UiActionKind.CLICK) shouldBe "\"click\""
    }

    @Test
    fun `version 2 lines still read`() {
        // A literal http.client line from the reporter golden file recorded before schema version 3.
        val line = """{"type":"http.client","schemaVersion":2,"eventId":"0af7651916cd43dd8448eb211c80319c-a1b2c3d4e5f60718","runId":"run-2","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"a1b2c3d4e5f60718","parentSpanId":"b7ad6b7169203331","component":"orders-service","startEpochNanos":1760000000002000000,"durationNanos":28000000,"method":"POST","url":"http://inventory-service:8081/reservations","serverAddress":"inventory-service","serverPort":8081,"status":201,"errorType":null}"""
        val event = mapper.readValue<RecordingEvent>(line)
        event.schemaVersion shouldBe 2
        event.shouldBeInstanceOf<HttpClientEvent>()
    }

    @Test
    fun `flow step kind serialises lowercase`() {
        mapper.writeValueAsString(FlowStepKind.METHOD) shouldBe "\"method\""
        mapper.writeValueAsString(FlowStepKind.HTTP) shouldBe "\"http\""
    }

    @Test
    fun `version1LinesStillRead`() {
        // The literal step-1 http.server line from the reporter golden file: old recordings must keep loading.
        val line = """{"type":"http.server","schemaVersion":1,"eventId":"0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331","runId":"run-1","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"b7ad6b7169203331","parentSpanId":null,"component":"orders-service","startEpochNanos":1760000000000000000,"durationNanos":42000000,"method":"POST","route":"/orders","path":"/orders","status":201,"requestHeaders":{"content-type":["application/json"],"x-multi":["a","b"]},"responseHeaders":{"content-type":["application/json"]},"requestBody":"{\"customerId\":\"c-1\",\"items\":[{\"sku\":\"A-1\",\"quantity\":2}]}","responseBody":"{\"id\":1,\"status\":\"CREATED\"}","requestBodyTruncated":false,"responseBodyTruncated":false}"""
        val event = mapper.readValue<RecordingEvent>(line)
        event.schemaVersion shouldBe 1
        event.shouldBeInstanceOf<HttpServerEvent>()
    }

    @Test
    fun `events carry the current schema version`() {
        sampleHttpEvent.schemaVersion shouldBe SCHEMA_VERSION
        mapper.writeValueAsString(sampleDbEvent) shouldContain "\"schemaVersion\":$SCHEMA_VERSION"
    }

    @Test
    fun `enums serialise lowercase`() {
        mapper.writeValueAsString(RecordingKind.E2E) shouldBe "\"e2e\""
        mapper.writeValueAsString(ComponentType.DATABASE) shouldBe "\"database\""
        mapper.readValue<RecordingKind>("\"manual\"") shouldBe RecordingKind.MANUAL
    }

    @Test
    fun `manifest round-trips`() {
        val manifest = RecordingManifest(
            runId = "run-1",
            scenarioId = "create-order",
            kind = RecordingKind.E2E,
            startedAt = Instant.parse("2026-10-27T10:00:00Z"),
            lastEventAt = null,
            stoppedAt = null,
            reporterVersion = "0.1.0-SNAPSHOT",
            componentsSeen = listOf("orders-service", "postgresql-orders"),
        )
        manifest.schemaVersion shouldBe SCHEMA_VERSION
        mapper.readValue<RecordingManifest>(mapper.writeValueAsString(manifest)) shouldBe manifest
    }
}
