package io.stackvibed.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * The fixture app running in its own JVM under the OpenTelemetry Java agent plus our extension,
 * exporting to `http://localhost:<receiverPort>`. Its console output goes to `stack.agentTestLogDir`.
 */
class ForkedAgentApp private constructor(private val process: Process, port: Int) : AutoCloseable {

    val baseUrl: String = "http://127.0.0.1:$port"

    override fun close() {
        process.destroy()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
        }
    }

    companion object {
        private val PORT_LINE = Regex("""Tomcat started on port\D*(\d+)""")

        fun start(receiverPort: Int): ForkedAgentApp {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val command = listOf(
                java,
                "-javaagent:${requiredProperty("stack.otelAgentJar")}",
                "-Dotel.javaagent.extensions=${requiredProperty("stack.extensionJar")}",
                "-Dotel.exporter.otlp.endpoint=http://localhost:$receiverPort",
                "-Dotel.service.name=fixture-app",
                "-jar", requiredProperty("stack.fixtureJar"),
                "--server.port=0",
                "--server.address=127.0.0.1",
            )
            val logDir = Files.createDirectories(Path.of(requiredProperty("stack.agentTestLogDir")))
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val logFile = logDir.resolve("fixture-app-${process.pid()}.log")
            val port = CompletableFuture<Int>()
            thread(isDaemon = true, name = "fixture-app-output") {
                Files.newBufferedWriter(logFile).use { log ->
                    process.inputReader().forEachLine { line ->
                        log.write(line)
                        log.newLine()
                        log.flush()
                        PORT_LINE.find(line)?.let { port.complete(it.groupValues[1].toInt()) }
                    }
                }
                port.completeExceptionally(IllegalStateException("fixture app exited before listening; see $logFile"))
            }
            try {
                return ForkedAgentApp(process, port.get(90, TimeUnit.SECONDS))
            } catch (e: TimeoutException) {
                process.destroyForcibly()
                throw IllegalStateException("fixture app did not start within 90s; see $logFile", e)
            }
        }

        private fun requiredProperty(name: String): String =
            requireNotNull(System.getProperty(name)) { "system property $name is not set (run via Gradle agentTest)" }
    }
}
