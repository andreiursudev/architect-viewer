package io.stackvibed.model.workspace

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.stackvibed.model.ComponentType

data class WorkspaceConfig(
    val project: ProjectInfo,
    val aliases: Map<String, String> = emptyMap(),
)

data class ProjectInfo(val name: String)

data class ComponentDefinition(
    val id: String,
    val type: ComponentType,
    val displayName: String,
    val tags: List<String> = emptyList(),
)

enum class ConnectionKind {
    @JsonProperty("http") HTTP,
    @JsonProperty("db") DB,
}

enum class ConnectionState {
    @JsonProperty("discovered") DISCOVERED,
    @JsonProperty("accepted") ACCEPTED,
    @JsonProperty("manual") MANUAL,
    @JsonProperty("hidden") HIDDEN,
}

data class ConnectionDefinition(
    val from: String,
    val to: String,
    val kind: ConnectionKind,
    val label: String?,
    val state: ConnectionState,
    val notes: String? = null,
) {
    /** Derived from the endpoints (it is the workspace file name), never stored in the file itself. */
    @get:JsonIgnore
    val id: String get() = "$from--$to"
}

data class ScenarioDefinition(
    val id: String,
    val description: String?,
)

data class BoardLayout(
    val nodes: Map<String, NodePosition>,
    val viewport: Viewport?,
)

data class NodePosition(val x: Double, val y: Double)

data class Viewport(val x: Double, val y: Double, val zoom: Double)
