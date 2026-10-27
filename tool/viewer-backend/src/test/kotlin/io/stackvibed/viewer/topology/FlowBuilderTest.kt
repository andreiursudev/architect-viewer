package io.stackvibed.viewer.topology

import io.kotest.matchers.shouldBe
import io.stackvibed.model.ComponentIds
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingKind
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.workspace.ConnectionKind
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
        flow.steps[0].kind shouldBe ConnectionKind.HTTP
        flow.steps[0].from shouldBe ComponentIds.E2E_CLIENT
        flow.steps[0].parentStepId shouldBe null
        flow.steps[1].parentStepId shouldBe flow.steps[0].stepId
        flow.steps[1].kind shouldBe ConnectionKind.DB
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

    private fun emptyManifest() = RecordingManifest(
        runId = "r", scenarioId = null, kind = RecordingKind.MANUAL, startedAt = Instant.EPOCH,
        lastEventAt = null, stoppedAt = null, reporterVersion = "test", componentsSeen = emptyList(),
    )

    private fun http(
        id: String, component: String, start: Long, duration: Long,
        route: String? = "/x", path: String = "/x", trace: String = "t1",
    ): RecordingEvent = HttpServerEvent(
        eventId = id, runId = "r", traceId = trace, spanId = id, parentSpanId = null, component = component,
        startEpochNanos = start, durationNanos = duration, method = "GET", route = route, path = path, status = 200,
        requestHeaders = emptyMap(), responseHeaders = emptyMap(), requestBody = null, responseBody = null,
    )

    private fun db(id: String, component: String, start: Long, duration: Long, statement: String = "select 1"): RecordingEvent =
        DbClientEvent(
            eventId = id, runId = "r", traceId = "t1", spanId = id, parentSpanId = null, component = component,
            startEpochNanos = start, durationNanos = duration, dbSystem = "postgresql", dbName = "orders",
            statement = statement, parameters = emptyList(), rowCount = null,
        )
}
