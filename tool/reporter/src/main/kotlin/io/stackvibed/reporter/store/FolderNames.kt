package io.stackvibed.reporter.store

import io.stackvibed.model.ComponentIds
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Recording folder names: sortable UTC start, scenario slug and a run id prefix, restricted to `[A-Za-z0-9_-]`. */
object FolderNames {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)
    private const val RUN_PREFIX_LENGTH = 8

    fun of(startedAt: Instant, scenarioId: String?, runId: String): String {
        val scenario = scenarioId?.let(ComponentIds::slug)?.ifEmpty { null } ?: "manual"
        val run = ComponentIds.slug(runId).take(RUN_PREFIX_LENGTH).trim('-').ifEmpty { "run" }
        return "${STAMP.format(startedAt)}_${scenario}_$run"
    }
}
