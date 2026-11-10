package io.stackvibed.viewer.registry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.stackvibed.model.view.AddWorkspaceRequest
import io.stackvibed.viewer.registry.internal.FileWorkspaceRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class FileWorkspaceRegistryTest {
    @TempDir
    lateinit var tmp: Path

    private val listFile get() = tmp.resolve("home/workspaces.yaml")

    private fun registry() = FileWorkspaceRegistry(listFile)

    /** A project root `tmp/<folder>` with `stack/workspace.yaml` naming the project [projectName]. */
    private fun projectRoot(folder: String, projectName: String): Path {
        val root = tmp.resolve(folder)
        root.resolve("stack").createDirectories()
        root.resolve("stack/workspace.yaml").writeText("project:\n  name: \"$projectName\"\n")
        return root
    }

    @Test
    fun `adding a project root uses its stack and recordings folders`() {
        val root = projectRoot("sam", "Sam")

        val entry = registry().add(AddWorkspaceRequest(root.toString()))

        entry shouldBe WorkspaceEntry("sam", root.resolve("stack"), root.resolve("recordings"))
    }

    @Test
    fun `adding a stack folder takes recordings next to it`() {
        val root = projectRoot("sam", "Sam")

        val entry = registry().add(AddWorkspaceRequest(root.resolve("stack").toString()))

        entry.recordings shouldBe root.resolve("recordings")
    }

    @Test
    fun `adding an empty folder creates stack workspace yaml with the name as project name`() {
        val root = tmp.resolve("fresh")

        val entry = registry().add(AddWorkspaceRequest(root.toString(), name = "my-stack"))

        entry.name shouldBe "my-stack"
        root.resolve("stack/workspace.yaml").readText() shouldContain "name: my-stack"
        root.resolve("recordings").exists() shouldBe false
    }

    @Test
    fun `the name defaults to the slug of project name`() {
        registry().add(AddWorkspaceRequest(projectRoot("folder", "Sam Project").toString())).name shouldBe "sam-project"
    }

    @Test
    fun `a project name that slugs to nothing falls back to the folder name then to workspace`() {
        registry().add(AddWorkspaceRequest(projectRoot("Sam_Stack", "!!!").toString())).name shouldBe "sam-stack"
        registry().add(AddWorkspaceRequest(projectRoot("___", "!!!").toString())).name shouldBe "workspace"
    }

    @Test
    fun `a given name and recordings path win over the defaults`() {
        val root = projectRoot("sam", "Sam")
        val recordings = tmp.resolve("elsewhere")

        val entry = registry().add(AddWorkspaceRequest(root.toString(), "mine", recordings.toString()))

        entry shouldBe WorkspaceEntry("mine", root.resolve("stack"), recordings)
    }

    @Test
    fun `a name that is not a slug is refused`() {
        val root = projectRoot("sam", "Sam")

        shouldThrow<IllegalArgumentException> { registry().add(AddWorkspaceRequest(root.toString(), "Not A Slug")) }
            .message shouldContain "Not A Slug"
    }

    @Test
    fun `a taken name is refused`() {
        val registry = registry()
        registry.add(AddWorkspaceRequest(projectRoot("a", "A").toString(), "same"))

        shouldThrow<IllegalArgumentException> { registry.add(AddWorkspaceRequest(projectRoot("b", "B").toString(), "same")) }
            .message shouldContain "same"
    }

    @Test
    fun `the same folder written differently is refused`() {
        val root = projectRoot("sam", "Sam")
        val registry = registry()
        registry.add(AddWorkspaceRequest("$root/stack/"))

        shouldThrow<IllegalArgumentException> { registry.add(AddWorkspaceRequest("$root/./stack", "other")) }
        registry.list().size shouldBe 1
    }

    @Test
    fun `a recordings folder that does not exist is accepted`() {
        val root = projectRoot("sam", "Sam")

        registry().add(AddWorkspaceRequest(root.toString(), recordings = tmp.resolve("nope").toString()))
            .recordings shouldBe tmp.resolve("nope")
    }

    @Test
    fun `rename changes only the name`() {
        val root = projectRoot("sam", "Sam")
        val registry = registry()
        val added = registry.add(AddWorkspaceRequest(root.toString()))
        registry.markOpened("sam")

        val renamed = registry.rename("sam", "sam-2")

        renamed shouldBe added.copy(name = "sam-2")
        registry.list() shouldBe listOf(renamed)
        registry.lastOpened() shouldBe "sam-2"
        shouldThrow<UnknownWorkspaceException> { registry.open("sam") }
    }

    @Test
    fun `rename refuses a bad or taken name`() {
        val registry = registry()
        registry.add(AddWorkspaceRequest(projectRoot("a", "A").toString()))
        registry.add(AddWorkspaceRequest(projectRoot("b", "B").toString()))

        shouldThrow<IllegalArgumentException> { registry.rename("a", "b") }
        shouldThrow<IllegalArgumentException> { registry.rename("a", "No Slug") }
    }

    @Test
    fun `remove leaves every file in place`() {
        val root = projectRoot("sam", "Sam")
        val registry = registry()
        registry.add(AddWorkspaceRequest(root.toString()))
        registry.markOpened("sam")

        registry.remove("sam")

        registry.list().shouldBeEmpty()
        registry.lastOpened() shouldBe null
        root.resolve("stack/workspace.yaml").exists() shouldBe true
    }

    @Test
    fun `unknown names throw`() {
        val registry = registry()

        shouldThrow<UnknownWorkspaceException> { registry.open("nope") }.message shouldBe "No workspace named 'nope'"
        shouldThrow<UnknownWorkspaceException> { registry.rename("nope", "x") }
        shouldThrow<UnknownWorkspaceException> { registry.remove("nope") }
        shouldThrow<UnknownWorkspaceException> { registry.markOpened("nope") }
    }

    @Test
    fun `open returns the same instance until renamed or removed`() {
        val registry = registry()
        registry.add(AddWorkspaceRequest(projectRoot("sam", "Sam").toString()))

        val first = registry.open("sam")
        registry.open("sam") shouldBeSameInstanceAs first
        first.entry.name shouldBe "sam"

        registry.rename("sam", "sam-2")
        val second = registry.open("sam-2")
        (second === first) shouldBe false
        second.entry.name shouldBe "sam-2"

        registry.remove("sam-2")
        shouldThrow<UnknownWorkspaceException> { registry.open("sam-2") }
    }

    @Test
    fun `markOpened persists lastOpened and does not rewrite an unchanged file`() {
        val registry = registry()
        registry.add(AddWorkspaceRequest(projectRoot("a", "A").toString()))
        registry.add(AddWorkspaceRequest(projectRoot("b", "B").toString()))

        registry.markOpened("b")
        registry().lastOpened() shouldBe "b"

        val old = FileTime.fromMillis(1_000_000_000_000)
        listFile.setLastModifiedTime(old)
        registry.markOpened("b")
        listFile.getLastModifiedTime() shouldBe old
    }

    @Test
    fun `registerStartup adds a new folder and makes it lastOpened`() {
        val root = projectRoot("sam", "Sam")
        val registry = registry()

        val entry = registry.registerStartup(root.resolve("stack"), null)

        entry shouldBe WorkspaceEntry("sam", root.resolve("stack"), root.resolve("recordings"))
        registry.list() shouldBe listOf(entry)
        registry.lastOpened() shouldBe "sam"
    }

    @Test
    fun `registerStartup reuses a listed folder`() {
        val root = projectRoot("sam", "Sam")
        val registry = registry()
        val added = registry.add(AddWorkspaceRequest(root.toString(), "mine"))
        registry.add(AddWorkspaceRequest(projectRoot("b", "B").toString()))

        registry.registerStartup(root.resolve("stack"), null) shouldBe added

        registry.list().size shouldBe 2
        registry.lastOpened() shouldBe "mine"
    }

    @Test
    fun `registerStartup with recordings updates a listed folder's recordings`() {
        val root = projectRoot("sam", "Sam")
        val registry = registry()
        registry.add(AddWorkspaceRequest(root.toString()))
        registry.add(AddWorkspaceRequest(projectRoot("b", "B").toString()))
        val before = registry.open("sam")
        val custom = tmp.resolve("custom-rec")

        val entry = registry.registerStartup(root.resolve("stack"), custom)

        entry shouldBe WorkspaceEntry("sam", root.resolve("stack"), custom)
        registry.lastOpened() shouldBe "sam"
        FileWorkspaceRegistry(listFile).list().first().recordings shouldBe custom
        val after = registry.open("sam")
        (after === before) shouldBe false
        after.entry.recordings shouldBe custom
    }

    @Test
    fun `registerStartup appends -2 on a name clash`() {
        val registry = registry()
        registry.add(AddWorkspaceRequest(projectRoot("one", "Sam").toString()))

        registry.registerStartup(projectRoot("two", "Sam").resolve("stack"), null).name shouldBe "sam-2"
        registry.registerStartup(projectRoot("three", "Sam").resolve("stack"), null).name shouldBe "sam-3"
    }

    @Test
    fun `registerStartup without recordings takes the listed ones, else next to the workspace`() {
        val root = projectRoot("sam", "Sam")
        val custom = tmp.resolve("custom-rec")
        val registry = registry()
        registry.add(AddWorkspaceRequest(root.toString(), recordings = custom.toString()))

        registry.registerStartup(root.resolve("stack"), null).recordings shouldBe custom

        val other = projectRoot("other", "Other")
        registry.registerStartup(other.resolve("stack"), null).recordings shouldBe other.resolve("recordings")

        val third = projectRoot("third", "Third")
        registry.registerStartup(third.resolve("stack"), custom).recordings shouldBe custom
    }

    @Test
    fun `a new registry instance reads what the previous one wrote`() {
        val root = projectRoot("sam", "Sam")
        val first = registry()
        val entry = first.add(AddWorkspaceRequest(root.toString()))
        first.markOpened("sam")

        val second = registry()

        second.list() shouldBe listOf(entry)
        second.lastOpened() shouldBe "sam"
        Files.exists(listFile) shouldBe true
    }
}
