package io.stackvibed.viewer.topology

import io.kotest.matchers.shouldBe
import io.stackvibed.model.ComponentIds
import io.kotest.matchers.types.shouldBeInstanceOf
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.MethodEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingKind
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.ScreenshotRecord
import io.stackvibed.model.UiActionEvent
import io.stackvibed.model.UiActionKind
import io.stackvibed.model.UiNavigationEvent
import io.stackvibed.model.UiNavigationKind
import io.stackvibed.model.view.FlowStep
import io.stackvibed.model.view.FlowStepKind
import io.stackvibed.viewer.Fixtures
import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.recordings.internal.FileRecordingRepository
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class FlowBuilderTest {
    private val builder = FlowBuilder()
    private val repository = FileRecordingRepository(ViewerPaths(Path.of("unused"), Fixtures.recordings))

    private val manifest get() = repository.manifests().first { it.runId == "run-1" }
    private val events get() = repository.events("run-1")

    @Test
    fun `create order flow has http then nested db step`() {
        val flow = builder.build(manifest, events, emptyMap())

        flow.runId shouldBe "run-1"
        flow.scenarioId shouldBe "create-order"
        flow.steps.map { it.connectionId } shouldBe listOf("e2e-client--orders-service", "orders-service--postgresql-orders")
        flow.steps[0].label shouldBe "POST /orders"
        flow.steps[0].status shouldBe 201
        flow.steps[0].kind shouldBe FlowStepKind.HTTP
        flow.steps[0].from shouldBe ComponentIds.E2E_CLIENT
        flow.steps[0].parentStepId shouldBe null
        flow.steps[1].parentStepId shouldBe flow.steps[0].stepId
        flow.steps[1].kind shouldBe FlowStepKind.DB
        flow.steps[1].status shouldBe null
        flow.steps[1].label shouldBe "insert into orders (customer_id, status, total, created_at) values (?, ?, ?, ?)".take(60)
        flow.steps.map { it.stepId } shouldBe events.map { it.eventId }
        flow.steps[0].event shouldBe events[0]
    }

    @Test
    fun `aliases rename components`() {
        val flow = builder.build(manifest, events, mapOf("postgresql-orders" to "orders-db", "orders-service" to "orders"))

        flow.steps[0].to shouldBe "orders"
        flow.steps[1].from shouldBe "orders"
        flow.steps[1].to shouldBe "orders-db"
        flow.steps[1].connectionId shouldBe "orders--orders-db"
    }

    @Test
    fun `route is preferred over path and falls back to it`() {
        val withRoute = http("a", "orders-service", 0, 100, route = "/orders/{id}", path = "/orders/7")
        val withoutRoute = http("b", "orders-service", 200, 100, route = null, path = "/health")

        val flow = builder.build(emptyManifest(), listOf(withRoute, withoutRoute), emptyMap())

        flow.steps.map { it.label } shouldBe listOf("GET /orders/{id}", "GET /health")
    }

    @Test
    fun `statement is collapsed to single spaces and cut to 60 characters`() {
        val statement = "select  *\n  from\torders   where customer_id = ? and status = ? and total > ? order by created_at"
        val flow = builder.build(emptyManifest(), listOf(db("d", "orders-service", 1, 1, statement)), emptyMap())

        val label = flow.steps.single().label
        label shouldBe "select * from orders where customer_id = ? and status = ? and total > ? order by created_at".take(60)
        label.length shouldBe 60
    }

    @Test
    fun `steps are sorted by start time`() {
        val later = http("later", "orders-service", 500, 10)
        val earlier = http("earlier", "orders-service", 100, 10)

        builder.build(emptyManifest(), listOf(later, earlier), emptyMap()).steps.map { it.stepId } shouldBe listOf("earlier", "later")
    }

    @Test
    fun `db step nests under the innermost enclosing http step of the same component and trace`() {
        val outer = http("outer", "orders-service", 0, 1000)
        val inner = http("inner", "orders-service", 100, 300)
        val db = db("db", "orders-service", 150, 50)
        val otherTrace = http("other-trace", "orders-service", 0, 1000, trace = "t2")
        val otherComponent = http("other-comp", "billing-service", 0, 1000)
        val orphan = db("orphan", "orders-service", 2000, 10)

        val steps = builder.build(emptyManifest(), listOf(outer, inner, db, otherTrace, otherComponent, orphan), emptyMap())
            .steps.associateBy { it.stepId }

        steps.getValue("db").parentStepId shouldBe "inner"
        steps.getValue("orphan").parentStepId shouldBe null
        steps.getValue("inner").parentStepId shouldBe null
    }

    @Test
    fun `called server is one hop from its caller`() {
        val orders = http("orders", "orders-service", 0, 1000)
        val call = client("call", "orders-service", 100, 500, url = "http://inventory-service:8081/reservations", parent = "orders")
        val inventory = http("inventory", "inventory-service", 150, 400, route = "/reservations", path = "/reservations", parent = "call", method = "POST")
        val update = db("update", "inventory-service", 200, 100, "update stock set available = available - ? where sku = ?", dbName = "inventory")

        val flow = builder.build(emptyManifest(), listOf(orders, call, inventory, update), emptyMap())

        flow.steps.map { it.connectionId } shouldBe listOf(
            "e2e-client--orders-service", "orders-service--inventory-service", "inventory-service--postgresql-inventory",
        )
        val (ordersStep, hop, dbStep) = flow.steps
        ordersStep.parentStepId shouldBe null
        hop.parentStepId shouldBe ordersStep.stepId
        hop.stepId shouldBe "inventory"
        hop.kind shouldBe FlowStepKind.HTTP
        hop.label shouldBe "POST /reservations"
        hop.event.shouldBeInstanceOf<HttpServerEvent>()
        dbStep.parentStepId shouldBe hop.stepId
    }

    @Test
    fun `unreachable callee is a failed hop to the host`() {
        val orders = http("orders", "orders-service", 0, 1000)
        val call = client(
            "call", "orders-service", 100, 500, url = "http://inventory-service:8081/reservations", parent = "orders",
            serverAddress = "inventory-service", status = null, errorType = "java.net.ConnectException",
        )

        val flow = builder.build(emptyManifest(), listOf(orders, call), emptyMap())

        val hop = flow.steps.last()
        flow.steps.map { it.connectionId } shouldBe listOf("e2e-client--orders-service", "orders-service--inventory-service")
        hop.status shouldBe null
        hop.kind shouldBe FlowStepKind.HTTP
        hop.label shouldBe "POST /reservations"
        hop.parentStepId shouldBe "orders"
        hop.event.shouldBeInstanceOf<HttpClientEvent>()
    }

    @Test
    fun `host name becomes a slug component`() {
        val call = client("call", "orders-service", 0, 10, url = "http://Inventory-Service.internal/x", serverAddress = "Inventory-Service.internal")

        builder.build(emptyManifest(), listOf(call), emptyMap()).steps.single().to shouldBe "inventory-service-internal"
    }

    @Test
    fun `call without a host goes to unknown-host and an unparsable url is the label as it is`() {
        val call = client("call", "orders-service", 0, 10, url = "not a url", serverAddress = null)

        val step = builder.build(emptyManifest(), listOf(call), emptyMap()).steps.single()

        step.to shouldBe "unknown-host"
        step.label shouldBe "POST not a url"
    }

    @Test
    fun `a host that slugs to nothing goes to unknown-host like a call without a host`() {
        val call = client("call", "orders-service", 0, 10, url = "http://.../x", serverAddress = "...")

        val step = builder.build(emptyManifest(), listOf(call), emptyMap()).steps.single()

        step.to shouldBe "unknown-host"
        step.connectionId shouldBe "orders-service--unknown-host"
    }

    @Test
    fun `aliases apply to both ends of a hop`() {
        val call = client("call", "orders-service", 100, 500, url = "http://inventory-service:8081/reservations", parent = null)
        val inventory = http("inventory", "inventory-service", 150, 400, parent = "call")

        val flow = builder.build(emptyManifest(), listOf(call, inventory), mapOf("orders-service" to "orders", "inventory-service" to "stock"))

        flow.steps.single().connectionId shouldBe "orders--stock"
    }

    @Test
    fun `version 1 recording still builds a flow`() {
        val flow = builder.build(manifest, events, emptyMap())

        flow.steps.map { it.connectionId } shouldBe listOf("e2e-client--orders-service", "orders-service--postgresql-orders")
        flow.steps[1].parentStepId shouldBe flow.steps[0].stepId
    }

    @Test
    fun `out of stock fixture run is a hop with a nested database update`() {
        val manifest = repository.manifests().first { it.runId == "run-4" }
        val flow = builder.build(manifest, repository.events("run-4"), emptyMap())

        flow.steps.map { it.connectionId } shouldBe listOf(
            "e2e-client--orders-service", "orders-service--inventory-service", "inventory-service--postgresql-inventory",
        )
        flow.steps.map { it.status } shouldBe listOf(409, 409, null)
        flow.steps[1].parentStepId shouldBe flow.steps[0].stepId
        flow.steps[2].parentStepId shouldBe flow.steps[1].stepId
    }

    @Test
    fun `controller method is merged into its request as the handler`() {
        val request = http("req", "orders-service", 0, 100, route = "/orders", path = "/orders", method = "POST")
        val controller = method("ctl", "orders-service", 1, 98, "x.OrdersController", "create", parent = "req")

        val flow = builder.build(emptyManifest(), listOf(request, controller), emptyMap())

        val step = flow.steps.single()
        step.stepId shouldBe "req"
        step.fromUnit shouldBe null
        step.toUnit shouldBe "x.OrdersController"
        step.handler?.methodName shouldBe "create"
        flow.steps.none { it.event == controller } shouldBe true
    }

    @Test
    fun `methods become steps between classes nested by their owners`() {
        val request = http("req", "orders-service", 0, 1000, route = "/orders", path = "/orders", method = "POST")
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req")
        val service = method("svc", "orders-service", 20, 900, "x.OrderService", "create", parent = "ctl", args = listOf("""{"sku":"sku-1"}"""))
        val reserve = method("reserve", "orders-service", 30, 600, "x.InventoryClient", "reserve", parent = "svc", args = listOf("\"sku-1\"", "2"))
        val call = client("call", "orders-service", 40, 500, url = "http://inventory-service:8081/reservations", parent = "reserve")
        val inventory = http("inventory", "inventory-service", 50, 400, route = "/reservations", path = "/reservations", parent = "call", method = "POST")
        val reservations = method("res-ctl", "inventory-service", 60, 300, "y.ReservationsController", "reserve", parent = "inventory")

        val flow = builder.build(emptyManifest(), listOf(request, controller, service, reserve, call, inventory, reservations), emptyMap())

        flow.steps.map { it.stepId } shouldBe listOf("req", "svc", "reserve", "inventory")
        val steps = flow.steps.associateBy { it.stepId }
        steps.getValue("req").toUnit shouldBe "x.OrdersController"
        with(steps.getValue("svc")) {
            kind shouldBe FlowStepKind.METHOD
            from shouldBe "orders-service"
            to shouldBe "orders-service"
            fromUnit shouldBe "x.OrdersController"
            toUnit shouldBe "x.OrderService"
            parentStepId shouldBe "req"
            connectionId shouldBe null
            status shouldBe null
            handler shouldBe null
            label shouldBe "create"
            event shouldBe service
        }
        with(steps.getValue("reserve")) {
            fromUnit shouldBe "x.OrderService"
            toUnit shouldBe "x.InventoryClient"
            parentStepId shouldBe "svc"
            label shouldBe "reserve"
        }
        with(steps.getValue("inventory")) {
            kind shouldBe FlowStepKind.HTTP
            connectionId shouldBe "orders-service--inventory-service"
            fromUnit shouldBe "x.InventoryClient"
            toUnit shouldBe "y.ReservationsController"
            handler shouldBe reservations
            parentStepId shouldBe "reserve"
        }
    }

    @Test
    fun `db step hangs under the repository method`() {
        val request = http("req", "orders-service", 0, 1000)
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req")
        val service = method("svc", "orders-service", 20, 900, "x.OrderService", "create", parent = "ctl")
        val insert = method("insert", "orders-service", 30, 100, "x.OrderRepository", "insert", parent = "svc")
        val db = db("db", "orders-service", 40, 50, "insert into orders values (?)", parent = "insert")

        val steps = builder.build(emptyManifest(), listOf(request, controller, service, insert, db), emptyMap()).steps.associateBy { it.stepId }

        with(steps.getValue("db")) {
            kind shouldBe FlowStepKind.DB
            connectionId shouldBe "orders-service--postgresql-orders"
            fromUnit shouldBe "x.OrderRepository"
            toUnit shouldBe null
            parentStepId shouldBe "insert"
        }
        steps.getValue("insert").parentStepId shouldBe "svc"
    }

    @Test
    fun `owner falls back to time containment when the parent span is not recorded`() {
        val request = http("req", "orders-service", 0, 1000)
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req")
        val service = method("svc", "orders-service", 20, 900, "x.OrderService", "create", parent = "unrecorded-proxy")
        val db = db("db", "orders-service", 40, 50, parent = null)
        val otherTrace = method("other-trace", "orders-service", 0, 1000, "x.Elsewhere", "run", trace = "t2")
        val otherComponent = method("other-comp", "billing-service", 0, 1000, "x.Billing", "run")

        val steps = builder.build(emptyManifest(), listOf(request, controller, service, db, otherTrace, otherComponent), emptyMap())
            .steps.associateBy { it.stepId }

        steps.getValue("svc").fromUnit shouldBe "x.OrdersController"
        steps.getValue("svc").parentStepId shouldBe "req"
        steps.getValue("db").fromUnit shouldBe "x.OrderService"
        steps.getValue("db").parentStepId shouldBe "svc"
    }

    @Test
    fun `two methods of the same interval do not own each other`() {
        val request = http("req", "orders-service", 0, 1000)
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req")
        val first = method("first", "orders-service", 20, 900, "x.First", "run", parent = "unrecorded-1")
        val second = method("second", "orders-service", 20, 900, "x.Second", "run", parent = "unrecorded-2")

        val steps = builder.build(emptyManifest(), listOf(request, controller, first, second), emptyMap()).steps.associateBy { it.stepId }

        steps.getValue("first").parentStepId shouldBe "req"
        steps.getValue("second").parentStepId shouldBe "req"
        steps.getValue("first").fromUnit shouldBe "x.OrdersController"
    }

    @Test
    fun `self call is a step from a class to itself`() {
        val request = http("req", "orders-service", 0, 1000)
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req")
        val price = method("price", "orders-service", 20, 900, "x.CatalogService", "price", parent = "ctl")
        val discount = method("discount", "orders-service", 30, 100, "x.CatalogService", "discount", parent = "price", args = listOf("3"))

        val step = builder.build(emptyManifest(), listOf(request, controller, price, discount), emptyMap()).steps.single { it.stepId == "discount" }

        step.fromUnit shouldBe "x.CatalogService"
        step.toUnit shouldBe "x.CatalogService"
        step.parentStepId shouldBe "price"
        step.label shouldBe "discount"
    }

    @Test
    fun `an exception thrown through two methods marks both`() {
        val request = http("req", "orders-service", 0, 1000)
        val failure = "x.OutOfStockException"
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req", exceptionType = failure)
        val service = method("svc", "orders-service", 20, 900, "x.OrderService", "create", parent = "ctl", args = listOf("1"), exceptionType = failure)
        val reserve = method("reserve", "orders-service", 30, 600, "x.InventoryClient", "reserve", parent = "svc", args = listOf("2"), exceptionType = failure)

        val flow = builder.build(emptyManifest(), listOf(request, controller, service, reserve), emptyMap())

        val methodSteps = flow.steps.filter { it.kind == FlowStepKind.METHOD }
        methodSteps.map { it.stepId } shouldBe listOf("svc", "reserve")
        methodSteps.map { (it.event as MethodEvent).exceptionType } shouldBe listOf(failure, failure)
        methodSteps.map { it.label } shouldBe listOf("create", "reserve")
        methodSteps.map { it.status } shouldBe listOf(null, null)
        flow.steps.first().handler?.exceptionType shouldBe failure
    }

    @Test
    fun `a method outside any request adds no step`() {
        val job = method("job", "orders-service", 0, 1000, "x.CleanupJob", "run")
        val nested = method("nested", "orders-service", 10, 900, "x.OrderRepository", "purge", parent = "job")
        val db = db("db", "orders-service", 20, 50, "delete from orders", parent = "nested")

        val flow = builder.build(emptyManifest(), listOf(job, nested, db), emptyMap())

        flow.steps.map { it.stepId } shouldBe listOf("db")
        flow.steps.single().parentStepId shouldBe null
    }

    @Test
    fun `a later ownerless method in a request is a step from the handler`() {
        val request = http("req", "orders-service", 0, 1000)
        val controller = method("ctl", "orders-service", 10, 500, "x.OrdersController", "create", parent = "req")
        val audit = method("audit", "orders-service", 600, 100, "x.AuditLog", "write", parent = "req")

        val step = builder.build(emptyManifest(), listOf(request, controller, audit), emptyMap()).steps.single { it.stepId == "audit" }

        step.fromUnit shouldBe "x.OrdersController"
        step.toUnit shouldBe "x.AuditLog"
        step.parentStepId shouldBe "req"
    }

    @Test
    fun `method label is the method name without its arguments`() {
        val request = http("req", "orders-service", 0, 1000)
        val controller = method("ctl", "orders-service", 10, 980, "x.OrdersController", "create", parent = "req")
        val service = method("svc", "orders-service", 20, 900, "x.OrderService", "create", parent = "ctl", args = listOf("\"" + "a".repeat(100) + "\""))

        val label = builder.build(emptyManifest(), listOf(request, controller, service), emptyMap()).steps.single { it.stepId == "svc" }.label

        label shouldBe "create"
    }

    @Test
    fun `flow without method events is unchanged`() {
        val manifest = repository.manifests().first { it.runId == "run-4" }
        val events = repository.events("run-4")

        val flow = builder.build(manifest, events, emptyMap())

        flow.steps shouldBe listOf(
            step2(events[0], null, "e2e-client", "orders-service", FlowStepKind.HTTP, "POST /orders", 409),
            step2(events[2], events[0].eventId, "orders-service", "inventory-service", FlowStepKind.HTTP, "POST /reservations", 409),
            step2(
                events[3], events[2].eventId, "inventory-service", "postgresql-inventory", FlowStepKind.DB,
                "update stock set available = available - ? where sku = ? and available >= ?".take(60), null,
            ),
        )
        builder.build(this.manifest, this.events, emptyMap()).steps
            .all { it.fromUnit == null && it.toUnit == null && it.handler == null } shouldBe true
    }

    @Test
    fun `run with methods nests both services under their classes`() {
        val manifest = repository.manifests().first { it.runId == "run-5" }

        val steps = builder.build(manifest, repository.events("run-5"), emptyMap()).steps

        val o = "io.stackvibed.sandbox.orders."
        val i = "io.stackvibed.sandbox.inventory."
        steps.map { Triple(it.kind, it.fromUnit, it.toUnit) } shouldBe listOf(
            Triple(FlowStepKind.HTTP, null, o + "OrdersController"),
            Triple(FlowStepKind.METHOD, o + "OrdersController", o + "OrderService"),
            Triple(FlowStepKind.METHOD, o + "OrderService", o + "InventoryClient"),
            Triple(FlowStepKind.HTTP, o + "InventoryClient", i + "ReservationsController"),
            Triple(FlowStepKind.METHOD, i + "ReservationsController", i + "StockRepository"),
            Triple(FlowStepKind.DB, i + "StockRepository", null),
            Triple(FlowStepKind.METHOD, o + "OrderService", o + "OrderRepository"),
            Triple(FlowStepKind.DB, o + "OrderRepository", null),
        )
        val ids = steps.map { it.stepId }
        steps.map { it.parentStepId } shouldBe listOf(null, ids[0], ids[1], ids[2], ids[3], ids[4], ids[1], ids[6])
        steps.map { it.label }[2] shouldBe "reserve"
    }

    @Test
    fun `browser run starts with ui steps from the e2e client`() {
        val flow = browserRun()

        val first = flow.steps.first()
        first.label shouldBe "load /"
        first.kind shouldBe FlowStepKind.UI
        first.from shouldBe ComponentIds.E2E_CLIENT
        first.to shouldBe "frontend"
        first.connectionId shouldBe "e2e-client--frontend"
        first.parentStepId shouldBe null
        first.status shouldBe null
        first.event.shouldBeInstanceOf<UiNavigationEvent>()
        flow.steps.filter { it.kind == FlowStepKind.UI }.map { it.label } shouldBe
            listOf("load /", "click to-order", "→ /order", "submit order-form", "→ /orders/7c9e6679-7425-40de-944b-e07fc1f90ae7")
    }

    @Test
    fun `fetch callee nests under the latest ui step before it`() {
        val steps = browserRun().steps
        val load = steps.first { it.label == "load /" }
        val submit = steps.first { it.label == "submit order-form" }
        val route = steps.first { it.label.startsWith("→ /orders/") }

        val stock = steps.single { it.label == "GET /stock" }
        stock.from shouldBe "frontend"
        stock.to shouldBe "inventory-service"
        stock.status shouldBe 200
        stock.parentStepId shouldBe load.stepId
        val post = steps.single { it.label == "POST /orders" }
        post.from shouldBe "frontend"
        post.to shouldBe "orders-service"
        post.parentStepId shouldBe submit.stepId
        steps.single { it.label == "GET /orders/{id}" }.parentStepId shouldBe route.stepId
        // the answered fetches add no steps of their own, and the service side still nests under the post
        steps.none { it.event is HttpClientEvent && (it.event as HttpClientEvent).component == "frontend" } shouldBe true
        steps.single { it.label == "POST /reservations" }.from shouldBe "orders-service"
    }

    @Test
    fun `fetch callee nests under its parent ui span when there is one`() {
        val first = action("first", 0, parent = null)
        val second = action("second", 100)
        val fetch = client("fetch", "frontend", 200, 50, url = "http://localhost:3000/orders", parent = "first", serverAddress = "localhost")
        val server = http("server", "orders-service", 210, 30, parent = "fetch")

        val steps = builder.build(emptyManifest(), listOf(first, second, fetch, server), emptyMap()).steps.associateBy { it.stepId }

        steps.getValue("server").parentStepId shouldBe "first"
    }

    @Test
    fun `of two ui steps starting together the later one in the list parents the fetch`() {
        val one = action("one", 100)
        val two = action("two", 100)
        val fetch = client("fetch", "frontend", 100, 50, url = "http://localhost:3000/orders", serverAddress = "localhost")
        val server = http("server", "orders-service", 110, 30, parent = "fetch")

        builder.build(emptyManifest(), listOf(one, two, fetch, server), emptyMap())
            .steps.single { it.stepId == "server" }.parentStepId shouldBe "two"
    }

    /**
     * The POSTs of the two browser runs recorded on 2026-10-31 at 18:04, with the ui spans on the fetch's clock (whole
     * milliseconds, as the browser package now records them): in the out-of-stock run the click on the submit button,
     * the submit it triggers and the POST the submit handler starts all fall in one millisecond.
     */
    @Test
    fun `a POST its submit handler starts nests under the submit, on the timestamps of real runs`() {
        fun parentOfPost(load: Long, click: Long, submit: Long, post: Long): String? = builder.build(
            emptyManifest(),
            listOf(
                navigation("load", load),
                action("click", click, target = "order-form"),
                action("submit", submit, event = UiActionKind.SUBMIT, target = "order-form"),
                client("post", "frontend", post, 24_000_000, url = "http://localhost:3000/orders", serverAddress = "localhost"),
                http("server", "orders-service", post + 3_000_000, 18_000_000, parent = "post", method = "POST"),
            ),
            emptyMap(),
        ).steps.single { it.stepId == "server" }.parentStepId

        // place-order-in-browser_68773d2f
        parentOfPost(
            load = 1_793_469_863_650_899_902, click = 1_793_469_863_827_000_000, submit = 1_793_469_863_828_000_000,
            post = 1_793_469_863_828_000_000,
        ) shouldBe "submit"
        // place-order-out-of-stock-in-browser_1524df0c
        parentOfPost(
            load = 1_793_469_864_631_300_049, click = 1_793_469_864_728_000_000, submit = 1_793_469_864_728_000_000,
            post = 1_793_469_864_728_000_000,
        ) shouldBe "submit"
    }

    @Test
    fun `a frontend fetch before any ui step has no parent`() {
        val fetch = client("fetch", "frontend", 100, 50, url = "http://localhost:3000/orders", serverAddress = "localhost")
        val server = http("server", "orders-service", 110, 30, parent = "fetch")
        val later = action("later", 500)

        val steps = builder.build(emptyManifest(), listOf(fetch, server, later), emptyMap()).steps.associateBy { it.stepId }

        steps.getValue("server").parentStepId shouldBe null
        steps.getValue("server").from shouldBe "frontend"
    }

    @Test
    fun `unanswered frontend fetch is a failed hop to its path`() {
        val load = navigation("load", 0)
        val fetch = client(
            "fetch", "frontend", 100, 50, url = "http://localhost:3000/orders", serverAddress = "localhost",
            status = null, errorType = "TypeError",
        )
        val root = client("root", "frontend", 200, 50, url = "http://localhost:3000/", serverAddress = "localhost")

        val steps = builder.build(emptyManifest(), listOf(load, fetch, root), emptyMap()).steps.associateBy { it.stepId }

        with(steps.getValue("fetch")) {
            to shouldBe "orders"
            from shouldBe "frontend"
            status shouldBe null
            parentStepId shouldBe "load"
            connectionId shouldBe "frontend--orders"
            label shouldBe "POST /orders"
        }
        steps.getValue("root").to shouldBe "unknown-host"
    }

    @Test
    fun `a client event of a component without ui events keeps the host as its callee`() {
        val call = client("call", "orders-service", 0, 10, url = "http://localhost:3000/orders", serverAddress = "localhost")

        builder.build(emptyManifest(), listOf(call), emptyMap()).steps.single().to shouldBe "localhost"
    }

    private fun shot(vararg spanIds: String) =
        ScreenshotRecord(spanIds.first(), ScreenshotRecord.fileOf(spanIds.first()), spanIds.toList(), Instant.parse("2026-10-31T10:00:01Z"))

    @Test
    fun `ui steps carry the screenshot that lists their span`() {
        val flow = builder.build(
            repository.manifests().first { it.runId == "run-6" }, repository.events("run-6"), emptyMap(),
            listOf(shot("9a8b7c6d5e4f3021"), shot("6d5e4f302192a3b4", "5e4f302192a3b4c5")),
        )

        val ui = flow.steps.filter { it.kind == FlowStepKind.UI }
        ui.map { it.label to it.screenshot } shouldBe listOf(
            "load /" to "9a8b7c6d5e4f3021",
            "click to-order" to "6d5e4f302192a3b4",
            "→ /order" to "6d5e4f302192a3b4",
            "submit order-form" to null,
            "→ /orders/7c9e6679-7425-40de-944b-e07fc1f90ae7" to null,
        )
    }

    @Test
    fun `other steps never carry a screenshot`() {
        // 7c6d5e4f302192a3 is the frontend's GET /stock: its step is the inventory-service hop, never a ui step.
        val flow = builder.build(
            repository.manifests().first { it.runId == "run-6" }, repository.events("run-6"), emptyMap(),
            listOf(shot("7c6d5e4f302192a3")),
        )

        flow.steps.mapNotNull { it.screenshot } shouldBe emptyList()
    }

    @Test
    fun `a run without screenshots builds as before`() {
        browserRun().steps.mapNotNull { it.screenshot } shouldBe emptyList()
    }

    private fun browserRun() = builder.build(
        repository.manifests().first { it.runId == "run-6" }, repository.events("run-6"), emptyMap(),
    )

    private fun action(
        id: String, start: Long, parent: String? = null, event: UiActionKind = UiActionKind.CLICK, target: String = "button",
    ): RecordingEvent = UiActionEvent(
        eventId = id, runId = "r", traceId = "t1", spanId = id, parentSpanId = parent, component = "frontend",
        startEpochNanos = start, durationNanos = 0, event = event, target = target, route = "/",
    )

    private fun navigation(id: String, start: Long): RecordingEvent = UiNavigationEvent(
        eventId = id, runId = "r", traceId = "t1", spanId = id, parentSpanId = null, component = "frontend",
        startEpochNanos = start, durationNanos = 1, url = "http://localhost:3000/", route = "/", kind = UiNavigationKind.LOAD,
    )

    private fun step2(
        event: RecordingEvent, parent: String?, from: String, to: String, kind: FlowStepKind, label: String, status: Int?,
    ) = FlowStep(
        stepId = event.eventId, parentStepId = parent, from = from, to = to, fromUnit = null, toUnit = null,
        connectionId = "$from--$to", kind = kind, label = label, startEpochNanos = event.startEpochNanos,
        durationNanos = event.durationNanos, status = status, event = event, handler = null,
    )

    private fun emptyManifest() = RecordingManifest(
        runId = "r", scenarioId = null, kind = RecordingKind.MANUAL, startedAt = Instant.EPOCH,
        lastEventAt = null, stoppedAt = null, reporterVersion = "test", componentsSeen = emptyList(),
    )

    private fun http(
        id: String, component: String, start: Long, duration: Long,
        route: String? = "/x", path: String = "/x", trace: String = "t1", parent: String? = null, method: String = "GET",
    ): RecordingEvent = HttpServerEvent(
        eventId = id, runId = "r", traceId = trace, spanId = id, parentSpanId = parent, component = component,
        startEpochNanos = start, durationNanos = duration, method = method, route = route, path = path, status = 200,
        requestHeaders = emptyMap(), responseHeaders = emptyMap(), requestBody = null, responseBody = null,
    )

    private fun client(
        id: String, component: String, start: Long, duration: Long, url: String, parent: String? = null,
        serverAddress: String? = "inventory-service", status: Int? = 201, errorType: String? = null,
    ): RecordingEvent = HttpClientEvent(
        eventId = id, runId = "r", traceId = "t1", spanId = id, parentSpanId = parent, component = component,
        startEpochNanos = start, durationNanos = duration, method = "POST", url = url, serverAddress = serverAddress,
        serverPort = null, status = status, errorType = errorType,
    )

    private fun db(
        id: String, component: String, start: Long, duration: Long, statement: String = "select 1", dbName: String = "orders",
        parent: String? = null,
    ): RecordingEvent =
        DbClientEvent(
            eventId = id, runId = "r", traceId = "t1", spanId = id, parentSpanId = parent, component = component,
            startEpochNanos = start, durationNanos = duration, dbSystem = "postgresql", dbName = dbName,
            statement = statement, parameters = emptyList(), rowCount = null,
        )

    private fun method(
        id: String, component: String, start: Long, duration: Long, className: String, methodName: String,
        parent: String? = null, args: List<String> = emptyList(), exceptionType: String? = null, trace: String = "t1",
    ): MethodEvent = MethodEvent(
        eventId = id, runId = "r", traceId = trace, spanId = id, parentSpanId = parent, component = component,
        startEpochNanos = start, durationNanos = duration, className = className, methodName = methodName,
        parameterNames = emptyList(), arguments = args, returnValue = null, exceptionType = exceptionType,
        exceptionMessage = exceptionType?.let { "failed" }, thread = "main",
    )
}
