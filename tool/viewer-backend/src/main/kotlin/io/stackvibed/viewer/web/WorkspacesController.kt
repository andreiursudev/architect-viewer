package io.stackvibed.viewer.web

import io.stackvibed.model.view.AddWorkspaceRequest
import io.stackvibed.model.view.RenameWorkspaceRequest
import io.stackvibed.model.view.WorkspaceEntryView
import io.stackvibed.model.view.WorkspaceListView
import io.stackvibed.viewer.registry.UnknownWorkspaceException
import io.stackvibed.viewer.registry.WorkspaceEntry
import io.stackvibed.viewer.registry.WorkspaceRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** The list of workspaces the viewer serves (spec §6.2). Refusals are 400 with the reason, unknown names 404. */
@RestController
class WorkspacesController(private val registry: WorkspaceRegistry) {
    /** An entry removed (or renamed) between reading the list and opening it is left out rather than failing the list. */
    @GetMapping("/api/workspaces")
    fun list(): WorkspaceListView = WorkspaceListView(
        registry.list().mapNotNull { entry ->
            try {
                view(entry)
            } catch (_: UnknownWorkspaceException) {
                null
            }
        },
        registry.lastOpened(),
    )

    @PostMapping("/api/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@RequestBody request: AddWorkspaceRequest): WorkspaceEntryView = view(registry.add(request))

    @PatchMapping("/api/workspaces/{ws}")
    fun rename(@PathVariable ws: String, @RequestBody request: RenameWorkspaceRequest): WorkspaceEntryView =
        view(registry.rename(ws, request.name))

    @DeleteMapping("/api/workspaces/{ws}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable ws: String) = registry.remove(ws)

    /** The project name is the workspace's own (its folder's name when `workspace.yaml` is missing or broken). */
    private fun view(entry: WorkspaceEntry) = WorkspaceEntryView(
        name = entry.name,
        workspace = entry.workspace.toString(),
        recordings = entry.recordings.toString(),
        projectName = registry.open(entry.name).store.load().config.project.name,
    )
}
