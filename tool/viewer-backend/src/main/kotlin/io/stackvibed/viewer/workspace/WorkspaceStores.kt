package io.stackvibed.viewer.workspace

import io.stackvibed.viewer.workspace.internal.FileWorkspaceStore
import java.nio.file.Path

/** Opens the [WorkspaceStore] of a workspace folder. */
object WorkspaceStores {
    fun open(root: Path): WorkspaceStore = FileWorkspaceStore(root)
}
