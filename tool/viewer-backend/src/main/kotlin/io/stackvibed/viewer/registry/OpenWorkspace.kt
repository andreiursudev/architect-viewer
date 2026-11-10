package io.stackvibed.viewer.registry

import io.stackvibed.viewer.recordings.RecordingRepository
import io.stackvibed.viewer.workspace.WorkspaceStore

/** A listed workspace with the store and the recordings repository opened on its folders. */
class OpenWorkspace(val entry: WorkspaceEntry, val store: WorkspaceStore, val recordings: RecordingRepository)
