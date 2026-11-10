package io.stackvibed.viewer.registry

import io.stackvibed.model.view.AddWorkspaceRequest
import java.nio.file.Path

/**
 * The list of workspaces this viewer knows (spec §2-§5), kept in a file outside every repository.
 * Refusals are [IllegalArgumentException]s carrying the reason; unknown names are [UnknownWorkspaceException]s.
 */
interface WorkspaceRegistry {
    /** The entries in the order they were added. */
    fun list(): List<WorkspaceEntry>

    /** The name of the workspace opened last, if it is still listed. */
    fun lastOpened(): String?

    /** Adds the folder of [request] under the rules of spec §4; an empty or missing folder becomes a new project root. */
    fun add(request: AddWorkspaceRequest): WorkspaceEntry

    /** Changes only the name of an entry. */
    fun rename(name: String, newName: String): WorkspaceEntry

    /** Takes the entry off the list; no file is deleted. */
    fun remove(name: String)

    /** The entry with its store and recordings, created on first use and kept until renamed or removed. */
    fun open(name: String): OpenWorkspace

    /** Makes [name] the last opened workspace; the file is only written when that changes. */
    fun markOpened(name: String)

    /** Lists the `--workspace` folder given at startup if it is not listed yet and makes it the last opened (spec §5). */
    fun registerStartup(workspace: Path, recordings: Path?): WorkspaceEntry
}
