package io.stackvibed.viewer.workspace

import io.stackvibed.model.view.WorkspaceProblem
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ScenarioDefinition
import io.stackvibed.model.workspace.WorkspaceConfig

/** Everything found in the workspace folder; unreadable files are listed in [problems] instead of failing the load. */
data class LoadedWorkspace(
    val config: WorkspaceConfig,
    val components: List<ComponentDefinition>,
    val connections: List<ConnectionDefinition>,
    val scenarios: List<ScenarioDefinition>,
    val problems: List<WorkspaceProblem>,
    /** Ids (component or connection) that have a `notes/<id>.md`. */
    val notedTargets: Set<String>,
)
