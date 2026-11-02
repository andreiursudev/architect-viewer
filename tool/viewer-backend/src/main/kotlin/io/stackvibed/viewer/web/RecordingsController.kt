package io.stackvibed.viewer.web

import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.view.Flow
import io.stackvibed.viewer.recordings.RecordingRepository
import io.stackvibed.viewer.topology.TopologyService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
class RecordingsController(private val recordings: RecordingRepository, private val topology: TopologyService) {
    /** The recorded runs of a scenario, newest first (an unknown scenario simply has none). */
    @GetMapping("/api/scenarios/{id}/runs")
    fun runs(@PathVariable id: String): List<RecordingManifest> =
        recordings.manifests().filter { it.scenarioId == id }.sortedByDescending { it.startedAt }

    @GetMapping("/api/runs/{runId}/flow")
    fun flow(@PathVariable runId: String): Flow = topology.flow(runId)

    /** A screenshot of a run's ui steps; stored once and never changed, so the browser may keep it. */
    @GetMapping("/api/runs/{runId}/screenshots/{id}")
    fun screenshot(@PathVariable runId: String, @PathVariable id: String): ResponseEntity<Resource> {
        val file = recordings.screenshot(runId, id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .body(FileSystemResource(file))
    }
}
