package io.stackvibed.viewer.topology

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.stackvibed.model.ComponentType
import io.stackvibed.model.view.BoardConnection
import io.stackvibed.model.view.ScenarioSummary
import io.stackvibed.model.view.WorkspaceView
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ConnectionKind
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.viewer.Fixtures
import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.recordings.internal.FileRecordingRepository
import io.stackvibed.viewer.workspace.internal.FileWorkspaceStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class TopologyServiceTest {
    @TempDir
    lateinit var tmp: Path

    private val workspaceDir get() = tmp.resolve("stack")
    private val recordingsDir get() = tmp.resolve("recordings")

    private val paths get() = ViewerPaths(workspaceDir, recordingsDir)
    private val store get() = FileWorkspaceStore(paths)
    private fun service() = TopologyService(store, FileRecordingRepository(paths), FlowBuilder())

    private val dbConnection = "orders-service--postgresql-orders"
    private val httpConnection = "e2e-client--orders-service"

    private fun WorkspaceView.connection(id: String): BoardConnection = connections.single { it.id == id }

    private fun definition(from: String, to: String, kind: ConnectionKind, state: ConnectionState) =
        ConnectionDefinition(from, to, kind, null, state)

    @Test
    fun `unaccepted evidence is discovered`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "orders-service" to ComponentType.SERVICE,
            "postgresql-orders" to ComponentType.DATABASE,
        )
        view.components.all { it.discovered && it.displayName == it.id && !it.hasNotes } shouldBe true
        view.connections.map { it.id to it.state } shouldContainExactlyInAnyOrder listOf(
            httpConnection to ConnectionState.DISCOVERED,
            dbConnection to ConnectionState.DISCOVERED,
        )
        view.connections.none { it.unverified } shouldBe true
        view.connection(httpConnection).kind shouldBe ConnectionKind.HTTP
        view.connection(httpConnection).label shouldBe "POST /orders"
        view.connection(dbConnection).kind shouldBe ConnectionKind.DB
        view.project.name shouldBe "stack"
    }

    @Test
    fun `caller of a service hop is a service`() {
        Fixtures.copyRun(Fixtures.OUT_OF_STOCK_4, recordingsDir)

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "orders-service" to ComponentType.SERVICE,
            "inventory-service" to ComponentType.SERVICE,
            "postgresql-inventory" to ComponentType.DATABASE,
        )
        view.connection("orders-service--inventory-service").state shouldBe ConnectionState.DISCOVERED
        view.connection("orders-service--inventory-service").kind shouldBe ConnectionKind.HTTP
        view.connection("orders-service--inventory-service").label shouldBe "POST /reservations"
    }

    @Test
    fun `callee of an unrecorded host is a service`() {
        val call = """{"type":"http.client","schemaVersion":2,"eventId":"t-c","runId":"run-9","traceId":"t","spanId":"c","parentSpanId":null,"component":"orders-service","startEpochNanos":1,"durationNanos":1,"method":"POST","url":"http://billing:8082/charges","serverAddress":"billing","serverPort":8082,"status":null,"errorType":"java.net.ConnectException"}"""
        recordingsDir.resolve("run-9").createDirectories()
        recordingsDir.resolve("run-9/manifest.json").writeText(
            """{"schemaVersion":2,"runId":"run-9","scenarioId":"charge","kind":"e2e","startedAt":"2026-10-28T10:00:00Z","lastEventAt":null,"stoppedAt":null,"reporterVersion":"0.1.0-test","componentsSeen":["orders-service"]}""",
        )
        recordingsDir.resolve("run-9/events.jsonl").writeText(call + "\n")

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "orders-service" to ComponentType.SERVICE,
            "billing" to ComponentType.SERVICE,
        )
        view.connection("orders-service--billing").label shouldBe "POST /charges"
    }

    @Test
    fun `a discovered http connection is labelled with every route of its evidence, in first-seen order`() {
        Fixtures.copyAll(recordingsDir)

        val view = service().workspaceView()

        // evidence, oldest first: run-3 (GET /orders/{id}), run-4 and run-5 (POST /orders)
        view.connection(httpConnection).label shouldBe "GET /orders/{id}, POST /orders"
    }

    @Test
    fun `browser run discovers frontend and its connections`() {
        Fixtures.copyRun(Fixtures.PLACE_ORDER_IN_BROWSER_6, recordingsDir)

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "frontend" to ComponentType.FRONTEND,
            "orders-service" to ComponentType.SERVICE,
            "inventory-service" to ComponentType.SERVICE,
            "postgresql-orders" to ComponentType.DATABASE,
            "postgresql-inventory" to ComponentType.DATABASE,
        )
        view.connection("e2e-client--frontend").kind shouldBe ConnectionKind.UI
        view.connection("e2e-client--frontend").label shouldBe "/, /order, /orders/{id}"
        view.connection("frontend--inventory-service").kind shouldBe ConnectionKind.HTTP
        view.connection("frontend--inventory-service").label shouldBe "GET /stock"
        view.connection("frontend--orders-service").label shouldBe "POST /orders, GET /orders/{id}"
        view.connections.none { it.id == httpConnection } shouldBe true
    }

    @Test
    fun `frontend stays frontend whatever is observed first`() {
        val fetch = """{"type":"http.client","schemaVersion":4,"eventId":"t-c","runId":"run-9","traceId":"t","spanId":"c","parentSpanId":null,"component":"frontend","startEpochNanos":1,"durationNanos":1,"method":"GET","url":"http://localhost:3000/orders","serverAddress":"localhost","serverPort":3000,"status":null,"errorType":"TypeError"}"""
        val load = """{"type":"ui.navigation","schemaVersion":4,"eventId":"t-n","runId":"run-9","traceId":"t","spanId":"n","parentSpanId":null,"component":"frontend","startEpochNanos":5,"durationNanos":1,"url":"http://localhost:3000/","route":"/","kind":"load"}"""
        recordingsDir.resolve("run-9").createDirectories()
        recordingsDir.resolve("run-9/manifest.json").writeText(
            """{"schemaVersion":4,"runId":"run-9","scenarioId":"browse","kind":"e2e","startedAt":"2026-10-28T10:00:00Z","lastEventAt":null,"stoppedAt":null,"reporterVersion":"0.1.0-test","componentsSeen":["frontend"]}""",
        )
        recordingsDir.resolve("run-9/events.jsonl").writeText(fetch + "\n" + load + "\n")

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "frontend" to ComponentType.FRONTEND,
            "orders" to ComponentType.SERVICE,
        )
    }

    @Test
    fun `the target of a curated ui connection is a frontend`() {
        store.saveConnection(definition("e2e-client", "web", ConnectionKind.UI, ConnectionState.MANUAL))
        store.saveConnection(definition("web", "orders-service", ConnectionKind.HTTP, ConnectionState.MANUAL))

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "web" to ComponentType.FRONTEND,
            "orders-service" to ComponentType.SERVICE,
        )
    }

    @Test
    fun `frontend seen only in componentsSeen with a curated ui connection is a frontend`() {
        recordingsDir.resolve("run-9").createDirectories()
        recordingsDir.resolve("run-9/manifest.json").writeText(
            """{"schemaVersion":4,"runId":"run-9","scenarioId":"browse","kind":"e2e","startedAt":"2026-10-28T10:00:00Z","lastEventAt":null,"stoppedAt":null,"reporterVersion":"0.1.0-test","componentsSeen":["web"]}""",
        )
        recordingsDir.resolve("run-9/events.jsonl").writeText("")
        store.saveConnection(definition("e2e-client", "web", ConnectionKind.UI, ConnectionState.MANUAL))

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "web" to ComponentType.FRONTEND,
        )
    }

    @Test
    fun `method events add no components or connections`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_METHODS_5, recordingsDir)
        val withoutMethodsDir = tmp.resolve("without-methods")
        val events = Fixtures.copyRun(Fixtures.CREATE_ORDER_METHODS_5, withoutMethodsDir).resolve("events.jsonl")
        val lines = events.readLines()
        events.writeText(lines.filterNot { "\"type\":\"method\"" in it }.joinToString("\n", postfix = "\n"))
        val withoutMethods = TopologyService(store, FileRecordingRepository(ViewerPaths(workspaceDir, withoutMethodsDir)), FlowBuilder())

        val with = service().workspaceView()
        val without = withoutMethods.workspaceView()

        events.readLines().size shouldBe lines.size - 6
        with.components shouldBe without.components
        with.connections shouldBe without.connections
        with.connections.map { it.id } shouldContainExactlyInAnyOrder listOf(
            httpConnection, dbConnection, "orders-service--inventory-service", "inventory-service--postgresql-inventory",
        )
    }

    @Test
    fun `a route seen in several evidence runs is listed once`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, workspaceDir.resolve("reference-recordings"))
        Fixtures.copyRun(Fixtures.CREATE_ORDER_2, recordingsDir)

        val view = service().workspaceView()

        view.connection(httpConnection).label shouldBe "POST /orders"
        view.connection(dbConnection).label shouldBe null
    }

    @Test
    fun `curated connections keep their stored label`() {
        Fixtures.copyAll(recordingsDir)
        store.saveConnection(ConnectionDefinition("e2e-client", "orders-service", ConnectionKind.HTTP, "Orders API", ConnectionState.ACCEPTED))
        store.saveConnection(ConnectionDefinition("orders-service", "billing", ConnectionKind.HTTP, "Invoices", ConnectionState.MANUAL))

        val view = service().workspaceView()

        view.connection(httpConnection).label shouldBe "Orders API"
        view.connection("orders-service--billing").label shouldBe "Invoices"
    }

    @Test
    fun `endpoints of curated connections are on the board even without recordings`() {
        store.saveConnection(definition("e2e-client", "orders-service", ConnectionKind.HTTP, ConnectionState.ACCEPTED))
        store.saveConnection(definition("orders-service", "postgresql-orders", ConnectionKind.DB, ConnectionState.MANUAL))
        store.saveConnection(definition("ghost", "phantom", ConnectionKind.HTTP, ConnectionState.HIDDEN))

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "e2e-client" to ComponentType.CLIENT,
            "orders-service" to ComponentType.SERVICE,
            "postgresql-orders" to ComponentType.DATABASE,
        )
        view.components.all { it.discovered && it.displayName == it.id } shouldBe true
        view.connection(httpConnection).unverified shouldBe true
    }

    @Test
    fun `curated and observed component types win over types inferred from connections`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)
        store.saveComponent(ComponentDefinition("billing", ComponentType.DATABASE, "Billing"))
        store.saveConnection(definition("orders-service", "billing", ConnectionKind.HTTP, ConnectionState.MANUAL))
        store.saveConnection(definition("postgresql-orders", "audit", ConnectionKind.HTTP, ConnectionState.MANUAL))

        val components = service().workspaceView().components.associateBy { it.id }

        components.getValue("billing").type shouldBe ComponentType.DATABASE
        components.getValue("billing").discovered shouldBe false
        components.getValue("postgresql-orders").type shouldBe ComponentType.DATABASE
        components.getValue("audit").type shouldBe ComponentType.SERVICE
        components.getValue("audit").discovered shouldBe true
    }

    @Test
    fun `empty workspace and no recordings is an empty view`() {
        val view = service().workspaceView()

        view.components shouldBe emptyList()
        view.connections shouldBe emptyList()
        view.scenarios shouldBe emptyList()
        view.problems shouldBe emptyList()
    }

    @Test
    fun `evidence is latest run per scenario`() {
        // without run-5, whose create-order run inserts again
        listOf(Fixtures.CREATE_ORDER_1, Fixtures.CREATE_ORDER_2, Fixtures.GET_ORDER_3, Fixtures.OUT_OF_STOCK_4)
            .forEach { Fixtures.copyRun(it, recordingsDir) }
        store.saveConnection(definition("orders-service", "postgresql-orders", ConnectionKind.DB, ConnectionState.ACCEPTED))
        store.saveConnection(definition("e2e-client", "orders-service", ConnectionKind.HTTP, ConnectionState.ACCEPTED))

        val view = service().workspaceView()

        // run-1 (with the DB call) is no longer the latest create-order run, and get-order never touched the DB
        view.connection(dbConnection).state shouldBe ConnectionState.ACCEPTED
        view.connection(dbConnection).unverified shouldBe true
        view.connection(httpConnection).unverified shouldBe false
    }

    @Test
    fun `an accepted connection stays verified while the latest run shows it`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)
        store.saveConnection(definition("orders-service", "postgresql-orders", ConnectionKind.DB, ConnectionState.ACCEPTED))

        service().workspaceView().connection(dbConnection).unverified shouldBe false
    }

    @Test
    fun `reference recordings always count as evidence`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, workspaceDir.resolve("reference-recordings"))
        Fixtures.copyRun(Fixtures.CREATE_ORDER_2, recordingsDir)
        store.saveConnection(definition("orders-service", "postgresql-orders", ConnectionKind.DB, ConnectionState.ACCEPTED))

        service().workspaceView().connection(dbConnection).unverified shouldBe false
    }

    @Test
    fun `manual connection is never unverified`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_2, recordingsDir)
        store.saveConnection(definition("orders-service", "queue", ConnectionKind.HTTP, ConnectionState.MANUAL))
        store.saveConnection(definition("orders-service", "postgresql-orders", ConnectionKind.DB, ConnectionState.MANUAL))

        val view = service().workspaceView()

        view.connection("orders-service--queue").unverified shouldBe false
        view.connection(dbConnection).unverified shouldBe false
        view.connection(dbConnection).state shouldBe ConnectionState.MANUAL
    }

    @Test
    fun `hidden connection is returned as it is and not rediscovered`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)
        store.saveConnection(definition("orders-service", "postgresql-orders", ConnectionKind.DB, ConnectionState.HIDDEN))

        val view = service().workspaceView()

        view.connections.map { it.id }.shouldContainExactlyInAnyOrder(dbConnection, httpConnection)
        view.connection(dbConnection).state shouldBe ConnectionState.HIDDEN
        view.connection(dbConnection).unverified shouldBe false
    }

    @Test
    fun `accepted connection without any recording is unverified`() {
        store.saveConnection(definition("a", "b", ConnectionKind.HTTP, ConnectionState.ACCEPTED))

        service().workspaceView().connection("a--b").unverified shouldBe true
    }

    @Test
    fun `curated components win over discovered ones`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)
        store.saveComponent(ComponentDefinition("orders-service", ComponentType.SERVICE, "Orders Service"))
        store.saveComponent(ComponentDefinition("legacy", ComponentType.SERVICE, "Legacy"))
        store.writeNote("orders-service", "# notes")

        val components = service().workspaceView().components.associateBy { it.id }

        components.keys.toList() shouldContainExactly listOf("legacy", "orders-service", "e2e-client", "postgresql-orders")
        components.getValue("orders-service").discovered shouldBe false
        components.getValue("orders-service").displayName shouldBe "Orders Service"
        components.getValue("orders-service").hasNotes shouldBe true
        components.getValue("legacy").discovered shouldBe false
        components.getValue("e2e-client").discovered shouldBe true
    }

    @Test
    fun `aliases map recorded components onto curated ones`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)
        workspaceDir.createDirectories()
        workspaceDir.resolve("workspace.yaml")
            .writeText("project:\n  name: Orders\naliases:\n  postgresql-orders: orders-db\n")
        store.saveComponent(ComponentDefinition("orders-db", ComponentType.DATABASE, "Orders DB"))

        val view = service().workspaceView()

        view.components.map { it.id } shouldContainExactlyInAnyOrder listOf("orders-db", "e2e-client", "orders-service")
        view.connections.map { it.id } shouldContainExactlyInAnyOrder listOf(httpConnection, "orders-service--orders-db")
        view.connection("orders-service--orders-db").state shouldBe ConnectionState.DISCOVERED
        view.project.name shouldBe "Orders"
    }

    @Test
    fun `scenarios include ids seen only in recordings`() {
        Fixtures.copyAll(recordingsDir)
        workspaceDir.resolve("scenarios").createDirectories()
        workspaceDir.resolve("scenarios/cancel-order.yaml").writeText("id: cancel-order\ndescription: Cancel it\n")
        workspaceDir.resolve("scenarios/create-order.yaml").writeText("id: create-order\ndescription: Create it\n")

        service().workspaceView().scenarios shouldBe listOf(
            ScenarioSummary("cancel-order", "Cancel it", null, 0),
            ScenarioSummary("create-order", "Create it", "run-5", 3),
            ScenarioSummary("create-order-out-of-stock", null, "run-4", 1),
            ScenarioSummary("get-order", null, "run-3", 1),
            ScenarioSummary("place-order-in-browser", null, "run-6", 1),
        )
    }

    @Test
    fun `workspace problems are passed through`() {
        workspaceDir.resolve("components").createDirectories()
        workspaceDir.resolve("components/broken.yaml").writeText(":::")

        service().workspaceView().problems.map { it.file } shouldBe listOf("components/broken.yaml")
    }

    @Test
    fun `flow of a run uses the workspace aliases`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordingsDir)
        workspaceDir.createDirectories()
        workspaceDir.resolve("workspace.yaml").writeText("project:\n  name: Orders\naliases:\n  postgresql-orders: orders-db\n")

        val flow = service().flow("run-1")

        flow.scenarioId shouldBe "create-order"
        flow.steps.map { it.connectionId } shouldBe listOf(httpConnection, "orders-service--orders-db")
    }

    @Test
    fun `flow of an unknown run is reported`() {
        shouldThrow<NoSuchElementException> { service().flow("nope") }
    }

    private fun kafkaRun() {
        val common = """"runId":"run-k","traceId":"t","startEpochNanos":1,"durationNanos":1,"partition":0,"offset":7,"key":"42","headers":{},"value":"{}","valueTruncated":false,"valueBytes":2,"errorType":null,"topic":"order-placed""""
        val produce = """{"type":"kafka.produce","schemaVersion":5,"eventId":"p","spanId":"p","parentSpanId":null,"component":"orders-service",$common}"""
        val consume = """{"type":"kafka.consume","schemaVersion":5,"eventId":"c","spanId":"c","parentSpanId":"p","component":"shipping-service","consumerGroup":"shipping",$common}"""
        recordingsDir.resolve("run-k").createDirectories()
        recordingsDir.resolve("run-k/manifest.json").writeText(
            """{"schemaVersion":5,"runId":"run-k","scenarioId":"ship-order","kind":"e2e","startedAt":"2026-11-03T10:00:00Z","lastEventAt":null,"stoppedAt":null,"reporterVersion":"0.1.0-test","componentsSeen":["orders-service","shipping-service"]}""",
        )
        recordingsDir.resolve("run-k/events.jsonl").writeText("$produce\n$consume\n")
    }

    @Test
    fun `a run with produce and consume discovers kafka connections through the topic`() {
        kafkaRun()

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "orders-service" to ComponentType.SERVICE,
            "topic-order-placed" to ComponentType.TOPIC,
            "shipping-service" to ComponentType.SERVICE,
        )
        view.components.associate { it.id to it.displayName } shouldBe mapOf(
            "orders-service" to "orders-service",
            "shipping-service" to "shipping-service",
            "topic-order-placed" to "order-placed",
        )
        view.connections.map { Triple(it.id, it.kind, it.label) } shouldContainExactlyInAnyOrder listOf(
            Triple("orders-service--topic-order-placed", ConnectionKind.KAFKA, null),
            Triple("topic-order-placed--shipping-service", ConnectionKind.KAFKA, null),
        )
        view.connections.all { it.state == ConnectionState.DISCOVERED } shouldBe true
    }

    @Test
    fun `a curated topic keeps its curated display name`() {
        kafkaRun()
        store.saveComponent(ComponentDefinition("topic-order-placed", ComponentType.TOPIC, "Orders placed"))

        val topic = service().workspaceView().components.single { it.id == "topic-order-placed" }

        topic.displayName shouldBe "Orders placed"
        topic.discovered shouldBe false
    }

    @Test
    fun `the topic end of a curated kafka connection is a topic`() {
        store.saveConnection(definition("topic-order-placed", "shipping-service", ConnectionKind.KAFKA, ConnectionState.ACCEPTED))
        store.saveConnection(definition("orders-service", "topic-order-created", ConnectionKind.KAFKA, ConnectionState.MANUAL))

        val view = service().workspaceView()

        view.components.map { it.id to it.type } shouldContainExactlyInAnyOrder listOf(
            "topic-order-placed" to ComponentType.TOPIC,
            "shipping-service" to ComponentType.SERVICE,
            "orders-service" to ComponentType.SERVICE,
            "topic-order-created" to ComponentType.TOPIC,
        )
    }
}
