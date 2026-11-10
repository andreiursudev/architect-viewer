package io.stackvibed.viewer.registry

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.stackvibed.viewer.registry.internal.WorkspaceListFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WorkspaceListFileTest {
    @TempDir
    lateinit var tmp: Path

    private val file get() = tmp.resolve("workspaces.yaml")

    @Test
    fun `entries and lastOpened survive a round trip`() {
        val entries = listOf(
            WorkspaceEntry("sandbox", tmp.resolve("a/stack"), tmp.resolve("a/recordings")),
            WorkspaceEntry("sam", tmp.resolve("b/stack"), tmp.resolve("b/recordings")),
        )
        WorkspaceListFile(file).write(entries, "sam")

        WorkspaceListFile(file).read() shouldBe (entries to "sam")
    }

    @Test
    fun `paths are written with forward slashes`() {
        WorkspaceListFile(file).write(listOf(WorkspaceEntry("a", tmp.resolve("x/stack"), tmp.resolve("x/rec"))), null)

        file.readText().contains("\\") shouldBe false
    }

    @Test
    fun `a missing file is an empty list`() {
        val (entries, lastOpened) = WorkspaceListFile(file).read()

        entries.shouldBeEmpty()
        lastOpened shouldBe null
    }

    @Test
    fun `a broken file reads as empty and is kept as workspaces yaml broken on the next write`() {
        file.writeText("workspaces: [unclosed")
        val list = WorkspaceListFile(file)

        list.read() shouldBe (emptyList<WorkspaceEntry>() to null)
        list.write(listOf(WorkspaceEntry("a", tmp.resolve("x/stack"), tmp.resolve("x/rec"))), "a")

        tmp.resolve("workspaces.yaml.broken").readText() shouldBe "workspaces: [unclosed"
        WorkspaceListFile(file).read().second shouldBe "a"
        tmp.resolve("workspaces.yaml.broken").exists() shouldBe true
    }
}
