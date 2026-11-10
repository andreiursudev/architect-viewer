package io.stackvibed.viewer.web

import io.stackvibed.model.view.WorkspaceView
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.viewer.registry.WorkspaceRegistry
import io.stackvibed.viewer.topology.TopologyService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class WorkspaceController(private val topology: TopologyService, private val registry: WorkspaceRegistry) {
    /**
     * The board of a workspace; reading it also makes it the last opened one. Failing to record that (the list file
     * cannot be written) is logged and does not fail the board.
     */
    @GetMapping("/api/w/{ws}/workspace")
    fun workspace(@PathVariable ws: String): WorkspaceView {
        val open = registry.open(ws)
        try {
            registry.markOpened(ws)
        } catch (e: Exception) {
            log.warn("Could not record '{}' as the last opened workspace: {}", ws, e.toString())
        }
        return topology.workspaceView(open)
    }

    /** Accepts a discovered component or renames a curated one. */
    @PutMapping("/api/w/{ws}/components/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun putComponent(@PathVariable ws: String, @PathVariable id: String, @RequestBody def: ComponentDefinition) {
        val open = registry.open(ws)
        require(def.id == id) { "component id '${def.id}' does not match '$id'" }
        open.store.saveComponent(def)
    }

    @PutMapping("/api/w/{ws}/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun putConnection(@PathVariable ws: String, @PathVariable id: String, @RequestBody def: ConnectionDefinition) {
        val open = registry.open(ws)
        require(def.state != ConnectionState.DISCOVERED) { "a connection cannot be saved as discovered" }
        require(def.id == id) { "connection id '$id' does not match '${def.id}'" }
        open.store.saveConnection(def)
    }

    private companion object {
        val log = LoggerFactory.getLogger(WorkspaceController::class.java)
    }
}
