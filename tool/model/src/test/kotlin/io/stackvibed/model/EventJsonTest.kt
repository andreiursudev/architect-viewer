package io.stackvibed.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    fun `events carry schema version 1`() {
        sampleHttpEvent.schemaVersion shouldBe 1
        mapper.writeValueAsString(sampleDbEvent) shouldContain "\"schemaVersion\":1"
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
        manifest.schemaVersion shouldBe 1
        mapper.readValue<RecordingManifest>(mapper.writeValueAsString(manifest)) shouldBe manifest
    }
}
