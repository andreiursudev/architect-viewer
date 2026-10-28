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
        val process = ProcessBuilder(listOf(script.toString()) + args)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "launcher did not exit" }
        return Result(process.exitValue(), output)
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
        val fakeHome = createTempDirectory("old java home")
        val bin = Files.createDirectory(fakeHome.resolve("bin"))
        val fakeJava = bin.resolve("java")
        fakeJava.writeText("#!/bin/sh\necho 'openjdk version \"17.0.9\" 2023-10-17' >&2\n")
        Files.setPosixFilePermissions(fakeJava, PosixFilePermissions.fromString("rwxr-xr-x"))

        val result = run(mapOf("JAVA_HOME" to fakeHome.toString()), "--workspace=$fakeHome")

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "Java 25 or newer is required"
    }

    @Test
    fun `launcher reports a JAVA_HOME without a java executable`() {
        val result = run(mapOf("JAVA_HOME" to "/nonexistent/java home"))

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "Java 25 or newer is required"
    }
}
