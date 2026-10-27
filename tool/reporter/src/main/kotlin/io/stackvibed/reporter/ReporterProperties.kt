package io.stackvibed.reporter

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/** `stack.reporter.*`: where recordings are written and which version stamps their manifests. */
@ConfigurationProperties("stack.reporter")
data class ReporterProperties(
    val recordingsDir: Path = Path.of("./recordings"),
    val version: String = "dev",
)
