package io.stackvibed.viewer.topology

import io.stackvibed.model.RecordingManifest

/** What the topology is verified against (spec D24): the latest run of each scenario plus every reference recording. */
object Evidence {
    fun select(manifests: List<RecordingManifest>, references: List<RecordingManifest>): List<RecordingManifest> {
        val latestPerScenario = manifests
            .filter { it.scenarioId != null }
            .groupBy { it.scenarioId }
            .values
            .map { runs -> runs.maxBy { it.startedAt } }
            .sortedBy { it.startedAt }
        return (latestPerScenario + references).distinctBy { it.runId }
    }
}
