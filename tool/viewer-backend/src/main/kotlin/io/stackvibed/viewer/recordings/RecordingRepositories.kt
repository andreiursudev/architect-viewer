package io.stackvibed.viewer.recordings

import io.stackvibed.viewer.recordings.internal.FileRecordingRepository
import java.nio.file.Path

/** Opens the [RecordingRepository] over a recordings folder and the workspace whose reference recordings it also serves. */
object RecordingRepositories {
    fun open(recordings: Path, workspace: Path): RecordingRepository = FileRecordingRepository(recordings, workspace)
}
