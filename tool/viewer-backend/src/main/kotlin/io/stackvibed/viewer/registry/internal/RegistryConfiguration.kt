package io.stackvibed.viewer.registry.internal

import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.registry.WorkspaceRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Declares the registry on the list file and applies the `--workspace` given at startup (spec §5). */
@Configuration
class RegistryConfiguration {
    @Bean
    fun workspaceRegistry(paths: ViewerPaths): WorkspaceRegistry {
        val registry = FileWorkspaceRegistry(paths.workspacesFile)
        paths.workspace?.let { registry.registerStartup(it, paths.recordings) }
        return registry
    }
}
