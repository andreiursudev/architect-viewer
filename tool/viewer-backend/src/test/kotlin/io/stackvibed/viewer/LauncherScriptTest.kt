package io.stackvibed.viewer

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/** Runs the real `stack-viewer` launcher from the assembled distribution (`viewerDist`). */
class LauncherScriptTest {
    private val script: Path = Path.of(System.getProperty("stack.viewer.dist")).resolve("stack-viewer")

    private class Result(val exitCode: Int, val output: String)

    private fun run(env: Map<String, String> = emptyMap(), vararg args: String): Result {
        val log = Files.createTempFile("launcher", ".log")
        val process = ProcessBuilder(listOf(script.toString()) + args)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply { environment().putAll(env) }
            .start()
        try {
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            val output = Files.readString(log)
            check(finished) { "launcher did not exit within 120s; output so far:\n$output" }
            return Result(process.exitValue(), output)
        } finally {
            process.destroyForcibly().waitFor()
            Files.deleteIfExists(log)
        }
    }

    private fun fakeJavaHome(body: String): Path {
        val home = createTempDirectory("fake java home")
        val java = Files.createDirectory(home.resolve("bin")).resolve("java")
        java.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
        return home
    }

    @Test
    fun `launcher starts the viewer`() {
        val workspace = createTempDirectory("ws with space")
        val result = run(
            emptyMap(),
            "--workspace=$workspace",
            "--server.port=0",
            "--stack.viewer.exit-after-startup=true",
        )
        result.exitCode shouldBe 0
        result.output shouldContain "Stack viewer started on http://localhost:"
    }

    @Test
    fun `launcher refuses a Java older than 25 with a clear message`() {
        val fakeHome = fakeJavaHome("""echo 'openjdk version "17.0.9" 2023-10-17' >&2""")

        val result = run(mapOf("JAVA_HOME" to fakeHome.toString()), "--workspace=$fakeHome")

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "Java 25 or newer is required"
        result.output shouldContain "17.0.9"
    }

    @Test
    fun `launcher reports a JAVA_HOME without a java executable`() {
        val result = run(mapOf("JAVA_HOME" to "/nonexistent/java home"))

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "has no bin/java"
    }

    @Test
    fun `launcher accepts Java 25 when the JVM prints a Picked up line first`() {
        val fakeHome = fakeJavaHome(
            """
            if [ "${'$'}1" = "-version" ]; then
              echo 'Picked up JAVA_TOOL_OPTIONS: -Xmx1g' >&2
              echo 'openjdk version "25.0.1" 2025-10-21' >&2
              exit 0
            fi
            echo "fake java launched: ${'$'}*"
            """.trimIndent(),
        )

        val result = run(mapOf("JAVA_HOME" to fakeHome.toString()), "--workspace=$fakeHome")

        result.exitCode shouldBe 0
        result.output shouldContain "fake java launched: -jar"
    }
}
