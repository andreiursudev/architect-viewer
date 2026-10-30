package io.stackvibed.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.stackvibed.model.view.FlowStepKind
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
        json shouldContain "\"schemaVersion\":3"
        mapper.readValue<RecordingEvent>(json) shouldBe sampleMethodEvent
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
