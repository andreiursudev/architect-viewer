package io.stackvibed.reporter.api

import io.stackvibed.model.RecordingKind
import io.stackvibed.model.ScreenshotRecord
import io.stackvibed.reporter.runs.RunAssignment
import io.stackvibed.reporter.store.RecordingStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Screenshots of a tagged run's ui steps, uploaded by the test harness (`StackBrowser`) once the steps settled. */
@RestController
class ScreenshotController(private val store: RecordingStore) {
    @PostMapping("/api/runs/{runId}/screenshots", consumes = ["image/png"])
    fun upload(
        @PathVariable runId: String,
        @RequestParam scenarioId: String,
        @RequestParam(defaultValue = "") spanIds: List<String>,
        @RequestBody png: ByteArray,
    ): ResponseEntity<Any> {
        val error = when {
            !RUN_ID.matches(runId) -> "runId must be a UUID"
            !SLUG.matches(scenarioId) -> "scenarioId must match ${SLUG.pattern}"
            spanIds.isEmpty() || spanIds.size > MAX_SPAN_IDS -> "spanIds must list 1 to $MAX_SPAN_IDS span ids"
            spanIds.any { !ScreenshotRecord.ID.matches(it) } -> "each span id must match ${ScreenshotRecord.ID.pattern}"
            png.size > MAX_BYTES -> "a screenshot may be at most $MAX_BYTES bytes"
            !isPng(png) -> "the body must be a PNG"
            else -> null
        }
        if (error != null) return ResponseEntity.badRequest().body(mapOf("error" to error))
        val record = store.addScreenshot(RunAssignment(runId, scenarioId, RecordingKind.E2E), spanIds, png)
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to record.id))
    }

    private fun isPng(bytes: ByteArray) =
        bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private companion object {
        val RUN_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val SLUG = Regex("^[a-z0-9][a-z0-9-]*$")
        const val MAX_SPAN_IDS = 20
        const val MAX_BYTES = 5 * 1024 * 1024
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
