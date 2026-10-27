package io.stackvibed.viewer.workspace

import io.stackvibed.model.workspace.BoardLayout
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition

/**
 * The workspace module's public API: the committed `stack/` folder (spec §8).
 * Ids and board names must be slugs (`^[a-z0-9][a-z0-9-]*$`), otherwise [IllegalArgumentException].
 */
interface WorkspaceStore {
    /** Reads the whole workspace; a missing folder or file is fine, a malformed file becomes a problem. */
    fun load(): LoadedWorkspace

    fun saveComponent(def: ComponentDefinition)

    /** `DISCOVERED` connections live only in memory, so saving one is rejected. */
    fun saveConnection(def: ConnectionDefinition)

    fun readNote(targetId: String): String?

    fun writeNote(targetId: String, markdown: String)

    fun readLayout(board: String): BoardLayout?

    fun writeLayout(board: String, layout: BoardLayout)
}
