package io.stackvibed.model.view

import com.fasterxml.jackson.annotation.JsonProperty
import io.stackvibed.model.ComponentType
import io.stackvibed.model.MethodEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.workspace.ConnectionKind
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.model.workspace.ProjectInfo

data class BoardComponent(
    val id: String,
    val type: ComponentType,
    val displayName: String,
    val discovered: Boolean,
    val hasNotes: Boolean,
)

data class BoardConnection(
    val id: String,
    val from: String,
    val to: String,
    val kind: ConnectionKind,
    val label: String?,
    val state: ConnectionState,
    val unverified: Boolean,
)

data class ScenarioSummary(
    val id: String,
    val description: String?,
    val latestRunId: String?,
    val runCount: Int,
)

data class WorkspaceProblem(val file: String, val message: String)

data class WorkspaceView(
    val project: ProjectInfo,
    val components: List<BoardComponent>,
    val connections: List<BoardConnection>,
    val scenarios: List<ScenarioSummary>,
    val problems: List<WorkspaceProblem>,
)

enum class FlowStepKind {
    @JsonProperty("http") HTTP,
    @JsonProperty("db") DB,
    @JsonProperty("method") METHOD,
    @JsonProperty("ui") UI,
    @JsonProperty("kafka") KAFKA,
}

data class FlowStep(
    val stepId: String,
    val parentStepId: String?,
    val from: String,
    val to: String,
    /** Fully qualified class at the calling end; null when it is not a recorded class. */
    val fromUnit: String?,
    /** Fully qualified class at the called end; null when it is not a recorded class. */
    val toUnit: String?,
    /** Null for method steps: they add no connection to the board. */
    val connectionId: String?,
    val kind: FlowStepKind,
    val label: String,
    val startEpochNanos: Long,
    val durationNanos: Long,
    val status: Int?,
    val event: RecordingEvent,
    /** On an http step: the method its request landed on. */
    val handler: MethodEvent?,
    /** On a ui step: the id of the screenshot of its result; null on other steps and when none was taken. */
    val screenshot: String? = null,
    /** On a consume step: the step id of the produce that sent its record; null on other steps and when the produce was not recorded. */
    val causedBy: String? = null,
)

data class Flow(
    val runId: String,
    val scenarioId: String?,
    val steps: List<FlowStep>,
)
