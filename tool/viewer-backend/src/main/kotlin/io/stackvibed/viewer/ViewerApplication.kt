package io.stackvibed.viewer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * The stack viewer backend: a Spring Modulith application whose modules are the direct sub-packages
 * (`workspace`, `recordings`, `topology`, `web`). `internal` sub-packages are private to their module.
 */
@SpringBootApplication
@EnableConfigurationProperties(ViewerPaths::class)
class ViewerApplication

fun main(args: Array<String>) {
    runApplication<ViewerApplication>(*args)
}
