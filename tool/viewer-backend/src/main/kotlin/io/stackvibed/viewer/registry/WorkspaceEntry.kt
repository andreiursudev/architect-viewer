package io.stackvibed.viewer.registry

import java.nio.file.Path

/** One workspace in the list: its URL [name] (a slug), the `stack/` folder and the recordings folder (spec §2). */
data class WorkspaceEntry(val name: String, val workspace: Path, val recordings: Path)
