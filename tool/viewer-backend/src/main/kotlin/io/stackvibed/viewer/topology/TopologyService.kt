package io.stackvibed.viewer.topology

import io.stackvibed.model.ComponentIds
import io.stackvibed.model.ComponentType
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.view.BoardComponent
import io.stackvibed.model.view.BoardConnection
import io.stackvibed.model.view.Flow
import io.stackvibed.model.view.FlowStep
import io.stackvibed.model.view.ScenarioSummary
import io.stackvibed.model.view.WorkspaceView
import io.stackvibed.model.workspace.ConnectionKind
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.viewer.recordings.RecordingRepository
import io.stackvibed.viewer.workspace.LoadedWorkspace
import io.stackvibed.viewer.workspace.WorkspaceStore
import org.springframework.stereotype.Service

/**
 * Joins what people curated (the workspace) with what the recordings prove (spec D24):
 * evidence without a connection file is `DISCOVERED`; an `ACCEPTED` connection without evidence is `unverified`;
 * `MANUAL` connections are never unverified; `HIDDEN` ones are returned as they are.
 * Every endpoint of a non-hidden curated connection is a component, so no curated connection dangles on the board.
 */
@Service
class TopologyService(
    private val workspace: WorkspaceStore,
    private val recordings: RecordingRepository,
    private val flowBuilder: FlowBuilder,
) {
    fun workspaceView(): WorkspaceView {
        val loaded = workspace.load()
        val manifests = recordings.manifests()
        val aliases = loaded.config.aliases

        val evidence = Evidence.select(manifests, recordings.referenceManifests())
        val observed = Observed()
        evidence.forEach { observe(it, aliases, observed) }

        return WorkspaceView(
            project = loaded.config.project,
            components = components(loaded, observed),
            connections = connections(loaded, observed),
            scenarios = scenarios(loaded, manifests),
            problems = loaded.problems,
        )
    }

    /** @throws NoSuchElementException when no recording has this run id */
    fun flow(runId: String): Flow {
        val manifest = (recordings.manifests() + recordings.referenceManifests()).firstOrNull { it.runId == runId }
            ?: throw NoSuchElementException("No recording with run id '$runId'")
        return flowBuilder.build(manifest, recordings.events(runId), workspace.load().config.aliases)
    }

    /** Components and connections the evidence runs went through, by id, in first-seen order. */
    private class Observed {
        val componentTypes = linkedMapOf<String, ComponentType>()
        val connections = linkedMapOf<String, ObservedConnection>()
    }

    /** [labels]: the distinct step labels of an HTTP connection (its routes), in first-seen order; none for a database. */
    private class ObservedConnection(val from: String, val to: String, val kind: ConnectionKind) {
        val labels = linkedSetOf<String>()
        val label: String? get() = labels.joinToString(", ").ifEmpty { null }
    }

    private fun observe(manifest: RecordingManifest, aliases: Map<String, String>, into: Observed) {
        val flow = flowBuilder.build(manifest, recordings.events(manifest.runId), aliases)
        flow.steps.forEach { step -> observe(step, into) }
        manifest.componentsSeen.forEach { into.componentTypes.putIfAbsent(aliases[it] ?: it, ComponentType.SERVICE) }
    }

    private fun observe(step: FlowStep, into: Observed) {
        val (fromType, toType) = when (step.kind) {
            ConnectionKind.HTTP -> ComponentType.CLIENT to ComponentType.SERVICE
            ConnectionKind.DB -> ComponentType.SERVICE to ComponentType.DATABASE
        }
        // The e2e client is a client wherever it shows up, and a database is not downgraded to a service.
        into.componentTypes.putIfAbsent(step.from, if (step.from == ComponentIds.E2E_CLIENT) ComponentType.CLIENT else fromType)
        into.componentTypes.merge(step.to, toType) { known, new -> if (new == ComponentType.DATABASE) new else known }
        val connection = into.connections.getOrPut(step.connectionId) { ObservedConnection(step.from, step.to, step.kind) }
        // A database call's label is its SQL, which says nothing about the connection as a whole.
        if (step.kind == ConnectionKind.HTTP) connection.labels += step.label
    }

    private fun components(loaded: LoadedWorkspace, observed: Observed): List<BoardComponent> {
        val curated = loaded.components.map {
            BoardComponent(it.id, it.type, it.displayName, discovered = false, hasNotes = it.id in loaded.notedTargets)
        }
        val known = curated.map { it.id }.toSet()
        val types = LinkedHashMap(observed.componentTypes)
        curatedEndpoints(loaded).forEach { (id, type) -> types.putIfAbsent(id, type) }
        val discovered = types
            .filterKeys { it !in known }
            .toSortedMap()
            .map { (id, type) -> BoardComponent(id, type, id, discovered = true, hasNotes = id in loaded.notedTargets) }
        return curated + discovered
    }

    /**
     * The endpoints of the non-hidden curated connections, typed from the connections alone: the e2e client is a
     * client, the target of a database connection a database, anything else a service.
     */
    private fun curatedEndpoints(loaded: LoadedWorkspace): Map<String, ComponentType> {
        val types = linkedMapOf<String, ComponentType>()
        fun add(id: String, type: ComponentType) {
            val inferred = if (id == ComponentIds.E2E_CLIENT) ComponentType.CLIENT else type
            types.merge(id, inferred) { known, new -> if (new == ComponentType.DATABASE) new else known }
        }
        loaded.connections.filter { it.state != ConnectionState.HIDDEN }.forEach {
            add(it.from, ComponentType.SERVICE)
            add(it.to, if (it.kind == ConnectionKind.DB) ComponentType.DATABASE else ComponentType.SERVICE)
        }
        return types
    }

    private fun connections(loaded: LoadedWorkspace, observed: Observed): List<BoardConnection> {
        val curated = loaded.connections.map {
            BoardConnection(
                id = it.id, from = it.from, to = it.to, kind = it.kind, label = it.label, state = it.state,
                unverified = it.state == ConnectionState.ACCEPTED && it.id !in observed.connections,
            )
        }
        val known = curated.map { it.id }.toSet()
        val discovered = observed.connections
            .filterKeys { it !in known }
            .toSortedMap()
            .map { (id, c) ->
                BoardConnection(id, c.from, c.to, c.kind, c.label, ConnectionState.DISCOVERED, unverified = false)
            }
        return curated + discovered
    }

    private fun scenarios(loaded: LoadedWorkspace, manifests: List<RecordingManifest>): List<ScenarioSummary> {
        val runsByScenario = manifests.filter { it.scenarioId != null }.groupBy { it.scenarioId!! }
        fun summary(id: String, description: String?): ScenarioSummary {
            val runs = runsByScenario[id].orEmpty()
            return ScenarioSummary(id, description, runs.maxByOrNull { it.startedAt }?.runId, runs.size)
        }

        val curated = loaded.scenarios.map { summary(it.id, it.description) }
        val known = curated.map { it.id }.toSet()
        val recorded = runsByScenario.keys.filter { it !in known }.sorted().map { summary(it, null) }
        return curated + recorded
    }
}
