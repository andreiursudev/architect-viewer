package io.stackvibed.viewer.web

import io.kotest.matchers.shouldBe
import io.stackvibed.model.view.AddWorkspaceRequest
import io.stackvibed.viewer.registry.OpenWorkspace
import io.stackvibed.viewer.registry.UnknownWorkspaceException
import io.stackvibed.viewer.registry.WorkspaceRegistry
import io.stackvibed.viewer.registry.internal.FileWorkspaceRegistry
import io.stackvibed.viewer.topology.FlowBuilder
import io.stackvibed.viewer.topology.TopologyService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The read endpoints keep answering when the registry fails around them. */
class RegistryFailuresTest {
    @TempDir
    lateinit var tmp: Path

    /** A registry listing `a` and `b`, each a project root under [tmp]. */
    private fun registry(): WorkspaceRegistry {
        val registry = FileWorkspaceRegistry(tmp.resolve("home/workspaces.yaml"))
        for (name in listOf("a", "b")) {
            val root = tmp.resolve(name)
            root.resolve("stack").createDirectories()
            root.resolve("stack/workspace.yaml").writeText("project: {name: $name}\n")
            registry.add(AddWorkspaceRequest(root.toString()))
        }
        return registry
    }

    @Test
    fun `the board loads when the list file cannot be written`() {
        // as when Windows denies replacing workspaces.yaml (an antivirus or an editor holding it)
        val registry = object : WorkspaceRegistry by registry() {
            override fun markOpened(name: String) = throw AccessDeniedException("workspaces.yaml")
        }
        val controller = WorkspaceController(TopologyService(FlowBuilder()), registry)

        controller.workspace("b").project.name shouldBe "b"
    }

    @Test
    fun `the list leaves out a workspace removed while it is being read`() {
        val real = registry()
        // `a` is in the snapshot the list endpoint maps, but removed before it is opened
        val registry = object : WorkspaceRegistry by real {
            override fun open(name: String): OpenWorkspace = if (name == "a") throw UnknownWorkspaceException(name) else real.open(name)
        }

        WorkspacesController(registry).list().workspaces.map { it.name } shouldBe listOf("b")
    }
}
