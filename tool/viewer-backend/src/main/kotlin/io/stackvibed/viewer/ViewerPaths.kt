package io.stackvibed.viewer

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/** Where the viewer reads and writes: top-level properties, so the CLI is `--workspace=<path> --recordings=<path>`. */
@ConfigurationProperties
data class ViewerPaths(
    val workspace: Path = Path.of("./stack"),
    val recordings: Path = Path.of("./recordings"),
)
