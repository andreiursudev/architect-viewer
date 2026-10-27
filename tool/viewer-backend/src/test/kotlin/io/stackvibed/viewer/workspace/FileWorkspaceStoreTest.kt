package io.stackvibed.viewer.workspace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.stackvibed.model.ComponentType
import io.stackvibed.model.workspace.BoardLayout
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ConnectionKind
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.model.workspace.NodePosition
import io.stackvibed.model.workspace.Viewport
import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.workspace.internal.FileWorkspaceStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileWorkspaceStoreTest {
    @TempDir
    lateinit var tmp: Path

    private fun workspaceDir(name: String = "orders-sandbox"): Path = tmp.resolve(name)

    private fun store(dir: Path = workspaceDir()): FileWorkspaceStore =
        FileWorkspaceStore(ViewerPaths(dir, tmp.resolve("recordings")))

    private val ordersService = ComponentDefinition("orders-service", ComponentType.SERVICE, "Orders Service", listOf("core"))

    @Test
    fun `empty workspace loads with defaults`() {
        val dir = workspaceDir().also { it.createDirectories() }

        val loaded = store(dir).load()

        loaded.config.project.name shouldBe "orders-sandbox"
        loaded.config.aliases shouldBe emptyMap()
        loaded.components.shouldBeEmpty()
        loaded.connections.shouldBeEmpty()
        loaded.scenarios.shouldBeEmpty()
        loaded.problems.shouldBeEmpty()
        loaded.notedTargets shouldBe emptySet()
        dir.listDirectoryEntries().shouldBeEmpty()
    }

    @Test
    fun `workspace directory that does not exist yet loads with defaults and stays absent`() {
        val dir = workspaceDir()

        store(dir).load().config.project.name shouldBe "orders-sandbox"

        dir.exists() shouldBe false
    }

    @Test
    fun `workspace yaml is read`() {
        val dir = workspaceDir().also { it.createDirectories() }
        dir.resolve("workspace.yaml").writeText("project:\n  name: Orders\naliases:\n  orders: orders-service\n")

        val config = store(dir).load().config

        config.project.name shouldBe "Orders"
        config.aliases shouldBe mapOf("orders" to "orders-service")
    }

    @Test
    fun `saved component is loaded back`() {
        val store = store()

        store.saveComponent(ordersService)
        store.saveComponent(ComponentDefinition("orders-db", ComponentType.DATABASE, "Orders DB"))

        val loaded = store.load()
        loaded.components shouldBe listOf(
            ComponentDefinition("orders-db", ComponentType.DATABASE, "Orders DB"),
            ordersService,
        )
        loaded.problems.shouldBeEmpty()
        workspaceDir().resolve("components/orders-service.yaml").readText() shouldContain "type: service"
    }

    @Test
    fun `saving a component again replaces it and leaves no temp files`() {
        val store = store()
        store.saveComponent(ordersService)

        store.saveComponent(ordersService.copy(displayName = "Orders"))

        store.load().components.map { it.displayName } shouldBe listOf("Orders")
        workspaceDir().resolve("components").listDirectoryEntries().map { it.fileName.toString() } shouldBe
            listOf("orders-service.yaml")
    }

    @Test
    fun `connection file name is from--to`() {
        val store = store()
        val connection = ConnectionDefinition(
            from = "e2e-client",
            to = "orders-service",
            kind = ConnectionKind.HTTP,
            label = "POST /orders",
            state = ConnectionState.ACCEPTED,
            notes = "entry point",
        )

        store.saveConnection(connection)

        val file = workspaceDir().resolve("connections/e2e-client--orders-service.yaml")
        file.exists() shouldBe true
        val text = file.readText()
        text shouldContain "state: accepted"
        text.lines().none { it.startsWith("id:") } shouldBe true
        store.load().connections shouldBe listOf(connection)
    }

    @Test
    fun `connection without label or notes round-trips`() {
        val store = store()
        val connection = ConnectionDefinition("orders-service", "orders-db", ConnectionKind.DB, null, ConnectionState.MANUAL)

        store.saveConnection(connection)

        store.load().connections shouldBe listOf(connection)
    }

    @Test
    fun `discovered state is never persisted`() {
        val store = store()
        val discovered = ConnectionDefinition("a", "b", ConnectionKind.HTTP, null, ConnectionState.DISCOVERED)

        shouldThrow<IllegalArgumentException> { store.saveConnection(discovered) }

        workspaceDir().exists() shouldBe false
    }

    @Test
    fun `scenarios are loaded`() {
        val dir = workspaceDir()
        dir.resolve("scenarios").createDirectories()
        dir.resolve("scenarios/place-order.yaml").writeText("id: place-order\ndescription: Customer places an order\n")
        dir.resolve("scenarios/bare.yaml").writeText("id: bare\n")

        val loaded = store(dir).load()

        loaded.scenarios.map { it.id to it.description } shouldBe
            listOf("bare" to null, "place-order" to "Customer places an order")
        loaded.problems.shouldBeEmpty()
    }

    @Test
    fun `malformedFileIsReportedNotFatal`() {
        val store = store()
        store.saveComponent(ordersService)
        workspaceDir().resolve("components/broken.yaml").writeText(":::")

        val loaded = store.load()

        loaded.problems shouldHaveSize 1
        loaded.problems[0].file shouldBe "components/broken.yaml"
        loaded.problems[0].message.isNotBlank() shouldBe true
        loaded.components shouldBe listOf(ordersService)
    }

    @Test
    fun `malformed workspace yaml is reported and defaults are used`() {
        val dir = workspaceDir().also { it.createDirectories() }
        dir.resolve("workspace.yaml").writeText("project: [unclosed")

        val loaded = store(dir).load()

        loaded.config.project.name shouldBe "orders-sandbox"
        loaded.problems.map { it.file } shouldBe listOf("workspace.yaml")
    }

    @Test
    fun `files that disagree with their name or are not slugs are reported`() {
        val dir = workspaceDir()
        dir.resolve("components").createDirectories()
        dir.resolve("connections").createDirectories()
        dir.resolve("components/orders-service.yaml").writeText("id: something-else\ntype: service\ndisplayName: X\n")
        dir.resolve("components/Bad Name.yaml").writeText("id: bad\ntype: service\ndisplayName: X\n")
        dir.resolve("connections/a--b.yaml").writeText("from: a\nto: c\nkind: http\nstate: manual\n")
        dir.resolve("components/empty.yaml").writeText("")

        val loaded = store(dir).load()

        loaded.components.shouldBeEmpty()
        loaded.connections.shouldBeEmpty()
        loaded.problems.map { it.file }.sorted() shouldBe listOf(
            "components/Bad Name.yaml",
            "components/empty.yaml",
            "components/orders-service.yaml",
            "connections/a--b.yaml",
        )
    }

    @Test
    fun `a stored discovered connection is reported rather than shown`() {
        val dir = workspaceDir()
        dir.resolve("connections").createDirectories()
        dir.resolve("connections/a--b.yaml").writeText("from: a\nto: b\nkind: http\nstate: discovered\n")

        val loaded = store(dir).load()

        loaded.connections.shouldBeEmpty()
        loaded.problems.map { it.file } shouldBe listOf("connections/a--b.yaml")
    }

    @Test
    fun `notes round-trip and are listed`() {
        val store = store()

        store.readNote("orders-service") shouldBe null
        store.writeNote("orders-service", "# Hi")

        store.readNote("orders-service") shouldBe "# Hi"
        store.load().notedTargets shouldBe setOf("orders-service")
        workspaceDir().resolve("notes/orders-service.md").readText() shouldBe "# Hi"
    }

    @Test
    fun `notes may target connections`() {
        val store = store()

        store.writeNote("e2e-client--orders-service", "entry")

        store.load().notedTargets shouldBe setOf("e2e-client--orders-service")
    }

    @Test
    fun `layout json has sorted keys`() {
        val store = store()
        val layout = BoardLayout(
            nodes = linkedMapOf("b" to NodePosition(2.0, 20.0), "a" to NodePosition(1.0, 10.0)),
            viewport = Viewport(5.0, 6.0, 1.5),
        )

        store.writeLayout("main", layout)

        val text = workspaceDir().resolve("boards/main.layout.json").readText()
        val a = text.indexOf("\"a\"")
        val b = text.indexOf("\"b\"")
        (a in 0 until b) shouldBe true
        text shouldContain "\n  "
        store.readLayout("main") shouldBe layout
    }

    @Test
    fun `missing layout reads as null and a malformed one is reported`() {
        val dir = workspaceDir()
        val store = store(dir)
        store.readLayout("main") shouldBe null

        dir.resolve("boards").createDirectories()
        dir.resolve("boards/main.layout.json").writeText("{ nope")

        store.readLayout("main") shouldBe null
        store.load().problems.map { it.file } shouldBe listOf("boards/main.layout.json")
    }

    @Test
    fun `non-slug id is rejected`() {
        val store = store()

        shouldThrow<IllegalArgumentException> { store.saveComponent(ordersService.copy(id = "Orders Service")) }
        shouldThrow<IllegalArgumentException> { store.saveComponent(ordersService.copy(id = "../escape")) }
        shouldThrow<IllegalArgumentException> {
            store.saveConnection(ConnectionDefinition("A B", "b", ConnectionKind.HTTP, null, ConnectionState.MANUAL))
        }
        shouldThrow<IllegalArgumentException> { store.writeNote("../x", "x") }
        shouldThrow<IllegalArgumentException> { store.readNote("Not A Slug") }
        shouldThrow<IllegalArgumentException> { store.writeLayout("a/b", BoardLayout(emptyMap(), null)) }
        shouldThrow<IllegalArgumentException> { store.readLayout("") }

        workspaceDir().exists() shouldBe false
    }

    @Test
    fun `relative workspace path still yields the directory name as project name`() {
        val relative = Path.of("build", "tmp", "orders-sandbox-relative")
        Files.createDirectories(relative)
        try {
            FileWorkspaceStore(ViewerPaths(relative, tmp)).load().config.project.name shouldBe "orders-sandbox-relative"
        } finally {
            Files.deleteIfExists(relative)
        }
    }
}
