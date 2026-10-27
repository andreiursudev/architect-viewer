package io.stackvibed.viewer.topology

import io.kotest.matchers.shouldBe
import io.stackvibed.model.RecordingKind
import io.stackvibed.model.RecordingManifest
import org.junit.jupiter.api.Test
import java.time.Instant

class EvidenceTest {
    private fun manifest(runId: String, scenario: String?, startedAt: String) = RecordingManifest(
        runId = runId,
        scenarioId = scenario,
        kind = RecordingKind.E2E,
        startedAt = Instant.parse(startedAt),
        lastEventAt = null,
        stoppedAt = null,
        reporterVersion = "test",
        componentsSeen = emptyList(),
    )

    @Test
    fun `latest run per scenario`() {
        val old = manifest("old", "a", "2026-01-01T00:00:00Z")
        val new = manifest("new", "a", "2026-01-02T00:00:00Z")
        val other = manifest("other", "b", "2026-01-01T00:00:00Z")

        Evidence.select(listOf(new, old, other), emptyList()).map { it.runId } shouldBe listOf("other", "new")
    }

    @Test
    fun `runs without a scenario are not evidence`() {
        Evidence.select(listOf(manifest("manual", null, "2026-01-01T00:00:00Z")), emptyList()) shouldBe emptyList()
    }

    @Test
    fun `all references are added once`() {
        val latest = manifest("r1", "a", "2026-01-02T00:00:00Z")
        val reference = manifest("r0", "a", "2026-01-01T00:00:00Z")
        val unscoped = manifest("r9", null, "2026-01-01T00:00:00Z")

        Evidence.select(listOf(latest), listOf(reference, latest, unscoped)).map { it.runId } shouldBe listOf("r1", "r0", "r9")
    }
}
