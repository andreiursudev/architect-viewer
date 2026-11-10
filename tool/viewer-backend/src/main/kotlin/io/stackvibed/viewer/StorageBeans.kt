package io.stackvibed.viewer

import io.stackvibed.viewer.recordings.RecordingRepositories
import io.stackvibed.viewer.recordings.RecordingRepository
import io.stackvibed.viewer.workspace.WorkspaceStore
import io.stackvibed.viewer.workspace.WorkspaceStores
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Temporary: builds the single store and repository from [ViewerPaths] until workspaces are resolved per request. */
@Configuration
class StorageBeans {
    @Bean
    fun workspaceStore(paths: ViewerPaths): WorkspaceStore = WorkspaceStores.open(paths.workspace)

    @Bean
    fun recordingRepository(paths: ViewerPaths): RecordingRepository =
        RecordingRepositories.open(paths.recordings, paths.workspace)
}
