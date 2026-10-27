package io.stackvibed.model.view

import io.stackvibed.model.ComponentType
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

data class FlowStep(
    val stepId: String,
    val parentStepId: String?,
    val from: String,
    val to: String,
    val connectionId: String,
    val kind: ConnectionKind,
    val label: String,
    val startEpochNanos: Long,
    val durationNanos: Long,
    val status: Int?,
    val event: RecordingEvent,
)

data class Flow(
    val runId: String,
    val scenarioId: String?,
    val steps: List<FlowStep>,
)
