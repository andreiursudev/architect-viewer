package io.stackvibed.viewer.web

import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.view.Flow
import io.stackvibed.viewer.recordings.RecordingRepository
import io.stackvibed.viewer.topology.TopologyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class RecordingsController(private val recordings: RecordingRepository, private val topology: TopologyService) {
    /** The recorded runs of a scenario, newest first (an unknown scenario simply has none). */
    @GetMapping("/api/scenarios/{id}/runs")
    fun runs(@PathVariable id: String): List<RecordingManifest> =
        recordings.manifests().filter { it.scenarioId == id }.sortedByDescending { it.startedAt }

    @GetMapping("/api/runs/{runId}/flow")
    fun flow(@PathVariable runId: String): Flow = topology.flow(runId)
}
