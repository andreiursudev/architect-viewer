package io.stackvibed.model

import java.time.Instant

data class RecordingManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val runId: String,
    val scenarioId: String?,
    val kind: RecordingKind,
    val startedAt: Instant,
    val lastEventAt: Instant?,
    val stoppedAt: Instant?,
    val reporterVersion: String,
    val componentsSeen: List<String>,
)
