package io.stackvibed.viewer.registry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.stackvibed.viewer.registry.internal.FolderKind
import io.stackvibed.viewer.registry.internal.classify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FolderClassifierTest {
    @TempDir
    lateinit var tmp: Path

    private val refusal = "Expected a project folder with `stack/workspace.yaml`, a `stack/` folder, or an empty folder"

    @Test
    fun `a folder with stack workspace yaml is a project root`() {
        tmp.resolve("stack").createDirectories()
        tmp.resolve("stack/workspace.yaml").writeText("project:\n  name: x\n")

        classify(tmp.toString()) shouldBe FolderKind.ProjectRoot(tmp)
    }

    @Test
    fun `a folder with workspace yaml is a stack folder`() {
        tmp.resolve("workspace.yaml").writeText("project:\n  name: x\n")

        classify(tmp.toString()) shouldBe FolderKind.StackFolder(tmp)
    }

    @Test
    fun `an empty folder and a missing folder are new roots`() {
        classify(tmp.toString()) shouldBe FolderKind.NewRoot(tmp)
        classify(tmp.resolve("later").toString()) shouldBe FolderKind.NewRoot(tmp.resolve("later"))
    }

    @Test
    fun `a folder with other files is refused`() {
        tmp.resolve("notes.txt").writeText("hello")

        shouldThrow<IllegalArgumentException> { classify(tmp.toString()) }.message shouldBe refusal
    }

    @Test
    fun `a file is refused with the folder message`() {
        val file = tmp.resolve("notes.txt").also { it.writeText("hello") }

        shouldThrow<IllegalArgumentException> { classify(file.toString()) }.message shouldBe refusal
    }

    @Test
    fun `surrounding whitespace and one pair of double quotes are stripped`() {
        tmp.resolve("stack").createDirectories()
        tmp.resolve("stack/workspace.yaml").writeText("project:\n  name: x\n")

        classify("  \"$tmp\"  ") shouldBe FolderKind.ProjectRoot(tmp)
    }

    @Test
    fun `a relative path is refused`() {
        shouldThrow<IllegalArgumentException> { classify("sam/stack") }.message shouldBe
            "Use a full path, such as D:\\projects\\sam or /Users/me/sam"
    }
}
