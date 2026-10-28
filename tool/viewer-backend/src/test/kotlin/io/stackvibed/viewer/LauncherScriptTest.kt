package io.stackvibed.viewer

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/**
 * Runs the real launcher from the assembled distribution (`viewerDist`): `stack-viewer` (sh), or `stack-viewer.cmd`
 * on Windows.
 */
class LauncherScriptTest {
    private val windows = OS.WINDOWS.isCurrentOs
    private val script: Path = Path.of(System.getProperty("stack.viewer.dist"))
        .resolve(if (windows) "stack-viewer.cmd" else "stack-viewer")

    private class Result(val exitCode: Int, val output: String)

    /**
     * Runs [command] (the launcher by absolute path unless given) from [dir] (the test's working directory if null).
     * A null value in [env] removes that variable. `STACK_VIEWER_JAVA_HOME` is removed unless [env] sets it, so a
     * developer's own setting cannot take precedence over the Java a test picks.
     */
    private fun run(
        env: Map<String, String?> = emptyMap(),
        vararg args: String,
        command: List<String> = listOf(script.toString()),
        dir: Path? = null,
    ): Result {
        val log = Files.createTempFile("launcher", ".log")
        val process = ProcessBuilder(command + args)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply {
                (mapOf("STACK_VIEWER_JAVA_HOME" to null) + env).forEach { (name, value) ->
                    if (value == null) environment().remove(name) else environment()[name] = value
                }
            }
            .apply { if (dir != null) directory(dir.toFile()) }
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

    /**
     * A fake `java` running [posix] (sh) or [windows] (cmd), and the environment that makes the launcher pick it:
     * `JAVA_HOME` on POSIX; on Windows, where the launcher only takes a real `java.exe` from `JAVA_HOME`, a
     * `java.cmd` first on `PATH`.
     */
    private fun fakeJava(posix: String, windows: String): Map<String, String?> {
        val home = createTempDirectory("fake java home")
        val bin = Files.createDirectory(home.resolve("bin"))
        if (this.windows) {
            bin.resolve("java.cmd").writeText("@echo off\r\n${windows.lines().joinToString("\r\n")}\r\n")
            return mapOf("JAVA_HOME" to null, "PATH" to "$bin${File.pathSeparator}${System.getenv("PATH")}")
        }
        val java = bin.resolve("java")
        java.writeText("#!/bin/sh\n$posix\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
        return mapOf("JAVA_HOME" to home.toString())
    }

    /**
     * A fake Java 25 that prints [pickedUp] (if any) before its version and echoes what it was launched with (on
     * Windows only its first two arguments, unquoted: `-jar <jar>`).
     */
    private fun fakeJava25(pickedUp: String? = null): Map<String, String?> = fakeJava(
        posix = """
            if [ "${'$'}1" = "-version" ]; then
              ${if (pickedUp != null) "echo '$pickedUp' >&2" else ":"}
              echo 'openjdk version "25.0.1" 2025-10-21' >&2
              exit 0
            fi
            echo "fake java launched: ${'$'}*"
        """.trimIndent(),
        windows = """
            if "%~1"=="-version" (
              ${if (pickedUp != null) "echo $pickedUp 1>&2" else "rem"}
              echo openjdk version "25.0.1" 2025-10-21 1>&2
              exit /b 0
            )
            echo fake java launched: %~1 %~2
        """.trimIndent(),
    )

    @Test
    fun `launcher starts the viewer`() {
        val workspace = createTempDirectory("ws with space")
        val result = run(
            // The JVM running this test is a Java 25+ one; the machine's default Java may be older.
            mapOf("JAVA_HOME" to System.getProperty("java.home")),
            "--workspace=$workspace",
            "--server.port=0",
            "--stack.viewer.exit-after-startup=true",
        )
        result.exitCode shouldBe 0
        result.output shouldContain "Stack viewer started on http://localhost:"
    }

    @Test
    fun `STACK_VIEWER_JAVA_HOME takes precedence over JAVA_HOME`() {
        val result = run(
            mapOf(
                "STACK_VIEWER_JAVA_HOME" to System.getProperty("java.home"),
                "JAVA_HOME" to createTempDirectory("java home without java").toString(),
            ),
            "--workspace=${createTempDirectory("ws")}",
            "--server.port=0",
            "--stack.viewer.exit-after-startup=true",
        )
        result.exitCode shouldBe 0
        result.output shouldContain "Stack viewer started on http://localhost:"
    }

    @Test
    fun `launcher reports a STACK_VIEWER_JAVA_HOME without a java executable`() {
        // Parentheses as in "Program Files (x86)": the cmd launcher must not end a ( ) block on them.
        val home = createTempDirectory("viewer java (x86) without java")
        val result = run(mapOf("STACK_VIEWER_JAVA_HOME" to home.toString(), "JAVA_HOME" to System.getProperty("java.home")))

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "STACK_VIEWER_JAVA_HOME ($home) has no bin"
    }

    @Test
    fun `launcher refuses a Java older than 25 with a clear message`() {
        val env = fakeJava(
            posix = """echo 'openjdk version "17.0.9" 2023-10-17' >&2""",
            windows = """echo openjdk version "17.0.9" 2023-10-17 1>&2""",
        )

        val result = run(env, "--workspace=${createTempDirectory("ws")}")

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "Java 25 or newer is required"
        result.output shouldContain "17.0.9"
    }

    @Test
    fun `launcher reports a JAVA_HOME without a java executable`() {
        val result = run(mapOf("JAVA_HOME" to createTempDirectory("java home without java").toString()))

        (result.exitCode != 0) shouldBe true
        result.output shouldContain "has no bin"
    }

    @Test
    fun `launcher started by a relative path finds its jar`() {
        val relative = "${script.parent.fileName}${File.separator}${script.fileName}"
        // ProcessBuilder resolves a relative program against this JVM's directory on Windows, so cmd starts it there.
        val command = if (windows) listOf("cmd", "/c", relative) else listOf(relative)

        val result = run(fakeJava25(), "--workspace=x", command = command, dir = script.parent.parent)

        result.exitCode shouldBe 0
        result.output shouldContain "fake java launched: -jar ${script.parent.toRealPath().resolve("stack-viewer.jar")}"
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "CDPATH is a POSIX shell variable")
    fun `launcher started by a relative path finds its jar even with CDPATH set`() {
        // A decoy with the same relative path: `cd stack-viewer` would go there (and print it) if CDPATH applied.
        val decoy = createTempDirectory("cdpath decoy")
        Files.createDirectory(decoy.resolve(script.parent.fileName.toString()))
        val relative = "${script.parent.fileName}/${script.fileName}"

        val result = run(
            fakeJava25() + ("CDPATH" to decoy.toString()),
            "--workspace=x",
            command = listOf(relative),
            dir = script.parent.parent,
        )

        result.exitCode shouldBe 0
        result.output shouldContain "fake java launched: -jar ${script.parent.toRealPath().resolve("stack-viewer.jar")}"
    }

    @Test
    fun `launcher accepts Java 25 when the JVM prints a Picked up line first`() {
        val result = run(fakeJava25(pickedUp = "Picked up JAVA_TOOL_OPTIONS: -Xmx1g"), "--workspace=x")

        result.exitCode shouldBe 0
        result.output shouldContain "fake java launched: -jar"
    }
}
