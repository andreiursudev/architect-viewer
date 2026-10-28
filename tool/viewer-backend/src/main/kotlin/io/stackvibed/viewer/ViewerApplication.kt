package io.stackvibed.viewer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/**
 * The stack viewer backend: a Spring Modulith application whose modules are the direct sub-packages
 * (`workspace`, `recordings`, `topology`, `web`). `internal` sub-packages are private to their module.
 */
@SpringBootApplication
@EnableConfigurationProperties(ViewerPaths::class)
class ViewerApplication

/**
 * Announces the URL once the server is listening. With `stack.viewer.exit-after-startup=true` it then exits,
 * which the launcher test uses to check a real start without keeping a server running.
 */
@Component
class StartupAnnouncer(
    @Value("\${stack.viewer.exit-after-startup:false}") private val exitAfterStartup: Boolean,
) {
    private val log = LoggerFactory.getLogger(StartupAnnouncer::class.java)

    @EventListener
    fun onReady(event: ApplicationReadyEvent) {
        // Mock web environments in tests have no server to announce.
        val port = (event.applicationContext as? WebServerApplicationContext)?.webServer?.port ?: return
        log.info("Stack viewer started on http://localhost:{}", port)
        if (exitAfterStartup) exitProcess(SpringApplication.exit(event.applicationContext))
    }
}

fun main(args: Array<String>) {
    runApplication<ViewerApplication>(*args)
}
