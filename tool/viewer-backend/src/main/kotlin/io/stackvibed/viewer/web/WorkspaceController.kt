package io.stackvibed.viewer.web

import io.stackvibed.model.view.WorkspaceView
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.viewer.topology.TopologyService
import io.stackvibed.viewer.workspace.WorkspaceStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class WorkspaceController(private val topology: TopologyService, private val workspace: WorkspaceStore) {
    @GetMapping("/api/workspace")
    fun workspace(): WorkspaceView = topology.workspaceView()

    /** Accepts a discovered component or renames a curated one. */
    @PutMapping("/api/components/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun putComponent(@PathVariable id: String, @RequestBody def: ComponentDefinition) {
        require(def.id == id) { "component id '${def.id}' does not match '$id'" }
        workspace.saveComponent(def)
    }

    @PutMapping("/api/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun putConnection(@PathVariable id: String, @RequestBody def: ConnectionDefinition) {
        require(def.state != ConnectionState.DISCOVERED) { "a connection cannot be saved as discovered" }
        require(def.id == id) { "connection id '$id' does not match '${def.id}'" }
        workspace.saveConnection(def)
    }
}
