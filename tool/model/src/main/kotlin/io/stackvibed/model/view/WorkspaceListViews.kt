package io.stackvibed.model.view

/** One registered workspace: its name in the registry, the folders it points at, and the project name shown for it. */
data class WorkspaceEntryView(
    val name: String,
    val workspace: String,
    val recordings: String,
    val projectName: String,
)

/** The registered workspaces plus the name of the one opened last, if any. */
data class WorkspaceListView(
    val workspaces: List<WorkspaceEntryView>,
    val lastOpened: String?,
)

/** Registers a workspace folder; name and recordings folder are derived when omitted. */
data class AddWorkspaceRequest(
    val folder: String,
    val name: String? = null,
    val recordings: String? = null,
)

/** Renames a registered workspace. */
data class RenameWorkspaceRequest(val name: String)
