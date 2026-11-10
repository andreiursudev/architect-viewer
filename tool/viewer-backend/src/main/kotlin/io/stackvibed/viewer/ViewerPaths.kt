package io.stackvibed.viewer

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * Where the viewer reads and writes: top-level properties, so the CLI is
 * `--workspace=<path> [--recordings=<path>] [--workspaces-file=<path>]`. Without `--workspace` the viewer serves the
 * list in [workspacesFile] as it is (spec §5).
 */
@ConfigurationProperties
data class ViewerPaths(
    val workspace: Path? = null,
    val recordings: Path? = null,
    val workspacesFile: Path = Path.of(System.getProperty("user.home"), ".stack-viewer", "workspaces.yaml"),
)
