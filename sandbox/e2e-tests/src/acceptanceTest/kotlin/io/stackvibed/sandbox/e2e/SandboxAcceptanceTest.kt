package io.stackvibed.sandbox.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.stackvibed.harness.StackBrowser
import io.stackvibed.harness.StackContext
import io.stackvibed.harness.StackScenario
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

/**
 * Sandbox acceptance: the whole stack (both services under the agent, their Postgres databases, the reporter, the
 * frontend) comes up with Docker Compose, the tagged scenarios run against it (the browser ones in Chromium), and the
 * reporter's recordings describe the expected flow across the frontend and both services.
 */
@Testcontainers
class SandboxAcceptanceTest {

    @Test
    @StackScenario("create-order")
    fun `create order is recorded across both services`() {
        OrdersScenarios(BASE_URL).createOrder()

        // 5 http/db events and 6 method events (the methods asserted below).
        val run = awaitRecording(scenario = "create-order", minEvents = 11)

        run.manifest["kind"] shouldBe "e2e"
        run.manifest["scenarioId"] shouldBe "create-order"
        (run.manifest["componentsSeen"] as List<*>).toSet() shouldBe setOf("orders-service", "inventory-service")
        val servers = run.events.filter { it["type"] == "http.server" }
        servers.map { it["component"] }.toSet() shouldBe setOf("orders-service", "inventory-service")
        val reserve = servers.single { it["component"] == "inventory-service" }
        reserve["route"] shouldBe "/reservations"
        reserve["status"] shouldBe 201
        (reserve["requestBody"] as String) shouldContain "sku-1"
        // The call orders-service makes to inventory-service, and the server span on the other side is its child.
        val client = run.events.single { it["type"] == "http.client" }
        client["component"] shouldBe "orders-service"
        client["method"] shouldBe "POST"
        client["status"] shouldBe 201
        (client["url"] as String) shouldEndWith "/reservations"
        reserve["parentSpanId"] shouldBe client["spanId"]
        // Exactly one statement per database: the reporter drops the empty queries the connection pool validates connections with.
        val statements = run.events.filter { it["type"] == "db.client" }
        statements.map { it["dbName"] }.toSet() shouldBe setOf("orders", "inventory")
        statements.single { it["dbName"] == "orders" }.let {
            (it["statement"] as String) shouldStartWith "insert into orders"
            it["dbSystem"] shouldBe "postgresql"
            (it["parameters"] as List<*>) shouldContain "sku-1"
            it["rowCount"] shouldBe 1
        }
        statements.single { it["dbName"] == "inventory" }.let {
            (it["statement"] as String) shouldStartWith "update stock"
            it["rowCount"] shouldBe 1
        }
        // Level 2: one event per method of the services' own beans, the HTTP client call a child of its method.
        val methods = run.events.filter { it["type"] == "method" }
        methods.map { "${(it["className"] as String).substringAfterLast('.')}.${it["methodName"]}" }.toSet() shouldBe setOf(
            "OrdersController.create", "OrderService.create", "InventoryClient.reserve", "OrderRepository.insert",
            "ReservationsController.reserve", "StockRepository.reserve",
        )
        val reserveMethod = methods.single { it["methodName"] == "reserve" && it["component"] == "orders-service" }
        client["parentSpanId"] shouldBe reserveMethod["spanId"]
        (reserveMethod["arguments"] as List<*>) shouldBe listOf("\"sku-1\"", "2") // the values createOrder() posts
    }

    @Test
    @StackScenario("create-order-out-of-stock")
    fun `out of stock is recorded as a failed hop and no insert`() {
        OrdersScenarios(BASE_URL).createOrderOutOfStock()

        // 4 http/db events and 5 method events (no OrderRepository.insert).
        val run = awaitRecording(scenario = "create-order-out-of-stock", minEvents = 9)

        run.events.single { it["type"] == "http.server" && it["component"] == "orders-service" }["status"] shouldBe 409
        run.events.single { it["type"] == "http.client" }["status"] shouldBe 409
        // Only inventory's update ran (and changed no row); orders-service stored nothing.
        val statements = run.events.filter { it["type"] == "db.client" }
        statements.single()["dbName"] shouldBe "inventory"
        statements.single()["rowCount"] shouldBe 0
        // The 409 travels up the orders-service call chain as the same exception, and no insert method ran.
        val methods = run.events.filter { it["type"] == "method" && it["component"] == "orders-service" }
        val named = methods.associateBy { "${(it["className"] as String).substringAfterLast('.')}.${it["methodName"]}" }
        for (name in listOf("InventoryClient.reserve", "OrderService.create", "OrdersController.create")) {
            named[name]?.get("exceptionType") shouldBe "org.springframework.web.server.ResponseStatusException"
        }
        named.keys shouldNotContain "OrderRepository.insert"
    }

    @Test
    @StackScenario("get-order")
    fun `get order is recorded`() {
        OrdersScenarios(BASE_URL).getOrder(seededOrderId)

        val run = awaitRecording(scenario = "get-order", minEvents = 2)

        run.manifest["kind"] shouldBe "e2e"
        run.events.single { it["type"] == "http.server" }.let {
            it["method"] shouldBe "GET"
            it["route"] shouldBe "/orders/{id}"
            it["path"] shouldBe "/orders/$seededOrderId"
            it["status"] shouldBe 200
            (it["responseBody"] as String) shouldContain seededOrderId
        }
        val statements = run.events.filter { it["type"] == "db.client" }
        statements shouldHaveSize 1
        statements.single().let {
            (it["statement"] as String) shouldStartWith "select"
            (it["parameters"] as List<*>) shouldContain seededOrderId
        }
    }

    @Test
    @StackScenario("browse-stock")
    fun `browsing stock is recorded from the browser`() {
        inBrowser { FrontendScenarios(it, FRONTEND_URL).browseStock() }

        // Real recording: 1 ui navigation and 1 fetch (frontend), then inventory-service's server span, its database call and 2 methods.
        val run = awaitRecording(scenario = "browse-stock", minEvents = 6)

        (run.manifest["componentsSeen"] as List<*>) shouldContainAll listOf("frontend", "inventory-service")
        val navigation = run.events.single { it["type"] == "ui.navigation" }
        navigation["component"] shouldBe "frontend"
        navigation["kind"] shouldBe "load"
        navigation["route"] shouldBe "/"
        val fetch = run.events.single { it["type"] == "http.client" }
        fetch["component"] shouldBe "frontend"
        fetch["method"] shouldBe "GET"
        (fetch["url"] as String) shouldEndWith "/stock"
        // The service-side span of the browser's fetch is its child.
        val server = run.events.single { it["type"] == "http.server" && it["component"] == "inventory-service" }
        server["method"] shouldBe "GET"
        server["route"] shouldBe "/stock"
        server["status"] shouldBe 200
        (server["responseBody"] as String) shouldContain "sku-1"
        server["parentSpanId"] shouldBe fetch["spanId"]

        assertScreenshots(run, listOf(listOf("load /")))
    }

    @Test
    @StackScenario("place-order-in-browser")
    fun `placing an order is recorded from the browser`() {
        var orderId = ""
        inBrowser { orderId = FrontendScenarios(it, FRONTEND_URL).placeOrder() }

        // Real recording: 3 ui navigations, 3 ui actions, 3 frontend fetches, 4 server spans, orders-service's call to
        // inventory-service, 4 database calls and 11 methods (7 in orders-service, 4 in inventory-service).
        val run = awaitRecording(scenario = "place-order-in-browser", minEvents = 29)

        val navigations = run.events.filter { it["type"] == "ui.navigation" }.sortedBy { it["startEpochNanos"] as Long }
        navigations.map { it["route"] } shouldBe listOf("/", "/order", "/orders/$orderId")
        navigations.map { it["kind"] } shouldBe listOf("load", "route", "route")
        val actions = run.events.filter { it["type"] == "ui.action" }
        actions.map { it["component"] }.toSet() shouldBe setOf("frontend")
        // In recording order: the click on the submit button comes before the submit it triggers.
        actions.map { it["event"] to it["target"] } shouldBe
            listOf("click" to "to-order", "click" to "place-order", "submit" to "order-form")

        val fetches = run.events.filter { it["type"] == "http.client" && it["component"] == "frontend" }.sortedBy { it["startEpochNanos"] as Long }
        fetches.map { it["method"] } shouldBe listOf("GET", "POST", "GET")
        (fetches[0]["url"] as String) shouldEndWith "/stock"
        (fetches[1]["url"] as String) shouldEndWith "/orders"
        fetches[1]["status"] shouldBe 201
        // The POST the submit handler starts is on the same clock as the submit, so it nests under it in the viewer.
        startOf(fetches[1]) shouldBeGreaterThanOrEqual startOf(actions.single { it["event"] == "submit" })
        (fetches[2]["url"] as String) shouldEndWith "/orders/$orderId"
        fetches[2]["status"] shouldBe 200

        val servers = run.events.filter { it["type"] == "http.server" }
        servers.single { it["component"] == "orders-service" && it["method"] == "POST" }["parentSpanId"] shouldBe fetches[1]["spanId"]
        servers.single { it["component"] == "inventory-service" && it["route"] == "/reservations" }["status"] shouldBe 201

        // The shield holds each click until the screenshot of the steps before it is taken, so the load is shot alone.
        assertScreenshots(
            run,
            listOf(
                listOf("load /"),
                listOf("click to-order", "→ /order"),
                listOf("click place-order", "submit order-form", "→ /orders/$orderId"),
            ),
        )
    }

    @Test
    @StackScenario("place-order-out-of-stock-in-browser")
    fun `an out-of-stock order is recorded from the browser`() {
        inBrowser { FrontendScenarios(it, FRONTEND_URL).placeOrderOutOfStock() }

        // Real recording: 1 ui navigation, 2 ui actions, 1 frontend fetch, 2 server spans, orders-service's failed call to
        // inventory-service, 1 database call and 5 methods.
        val run = awaitRecording(scenario = "place-order-out-of-stock-in-browser", minEvents = 13)

        val fetch = run.events.single { it["type"] == "http.client" && it["component"] == "frontend" }
        fetch["method"] shouldBe "POST"
        (fetch["url"] as String) shouldEndWith "/orders"
        fetch["status"] shouldBe 409
        val actions = run.events.filter { it["type"] == "ui.action" }
        actions.map { it["event"] to it["target"] } shouldBe listOf("click" to "place-order", "submit" to "order-form")
        startOf(fetch) shouldBeGreaterThanOrEqual startOf(actions.single { it["event"] == "submit" })
        run.events.single { it["type"] == "http.server" && it["component"] == "orders-service" }["status"] shouldBe 409
        run.events.filter { it["type"] == "db.client" }.map { it["statement"] as String }.none { it.startsWith("insert into orders") } shouldBe true

        assertScreenshots(run, listOf(listOf("load /order"), listOf("click place-order", "submit order-form")))
    }

    /** Untagged: nginx must hand a browser reload of /orders/<id> the app, whose own fetch then reaches orders-service. */
    @Test
    fun `order page survives a reload`() {
        inBrowser { page ->
            FrontendScenarios(page, FRONTEND_URL).placeOrder()
            page.reload()
            assertThat(page.getByTestId("order")).isVisible()
        }
    }

    /** Runs [block] with a page of a fresh browser context, tagged with the current scenario when there is one. */
    private fun inBrowser(block: (Page) -> Unit) {
        browser.newContext().use { context ->
            val page = context.newPage()
            if (StackContext.current() != null) StackBrowser.tag(page)
            block(page)
        }
    }

    /** The start of a recorded event; Jackson reads the epoch nanos as a Long. */
    private fun startOf(event: Map<String, Any?>): Long = event["startEpochNanos"] as Long

    /** A recorded run: its folder, its manifest and its events, both as plain JSON maps (no dependency on tool internals). */
    private data class Recording(val folder: Path, val manifest: Map<String, Any?>, val events: List<Map<String, Any?>>)

    /**
     * Every ui step of [run] is in exactly one screenshot record, the records group the steps as [groups] (labelled as
     * the viewer labels them, in the order each record lists them), and each record's file is a PNG.
     */
    private fun assertScreenshots(run: Recording, groups: List<List<String>>) {
        val records = awaitScreenshots(run, records = groups.size)
        val labels = run.events.filter { (it["type"] as String).startsWith("ui.") }.associate { it["spanId"] to uiLabel(it) }
        records.map { record -> (record["spanIds"] as List<*>).map { labels[it] ?: "unknown span $it" } } shouldBe groups
        records.flatMap { it["spanIds"] as List<*> }.sortedBy { it.toString() } shouldBe labels.keys.sortedBy { it.toString() }
        for (record in records) {
            record["id"] shouldBe (record["spanIds"] as List<*>).first()
            record["file"] shouldBe "screenshots/${record["id"]}.png"
            val png = run.folder.resolve(record["file"] as String)
            png.exists() shouldBe true
            Files.readAllBytes(png).take(8) shouldBe PNG_SIGNATURE
        }
    }

    /** Waits until the run's `screenshots.jsonl` holds [records] whole lines. */
    private fun awaitScreenshots(run: Recording, records: Int, timeout: Duration = Duration.ofSeconds(10)): List<Map<String, Any?>> {
        val file = run.folder.resolve("screenshots.jsonl")
        val deadline = Instant.now().plus(timeout)
        var lines = emptyList<String>()
        while (Instant.now().isBefore(deadline)) {
            lines = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
            if (lines.size >= records) {
                try {
                    return lines.map(::readMap)
                } catch (_: JacksonException) {
                    // A line still being written; read it whole on the next poll.
                }
            }
            Thread.sleep(200)
        }
        throw AssertionError("expected $records screenshot records in $file within $timeout, found ${lines.size}: $lines")
    }

    /** A ui event labelled as the viewer labels its step. */
    private fun uiLabel(event: Map<String, Any?>): String = when (event["type"]) {
        "ui.navigation" -> if (event["kind"] == "load") "load ${event["route"]}" else "→ ${event["route"]}"
        else -> "${event["event"]} ${event["target"]}"
    }

    /**
     * Waits for the reporter to write the recording of the current test's run (the run id the harness put into
     * the baggage) with at least [minEvents] events. Spans reach the reporter asynchronously (batched export).
     */
    private fun awaitRecording(scenario: String, minEvents: Int, timeout: Duration = Duration.ofSeconds(10)): Recording {
        val runId = requireNotNull(StackContext.current()) { "no @StackScenario is active" }.runId
        val deadline = Instant.now().plus(timeout)
        var latest: Recording? = null
        while (Instant.now().isBefore(deadline)) {
            latest = readRecording(runId)
            if (latest != null && latest.events.size >= minEvents) {
                latest.manifest["scenarioId"] shouldBe scenario
                return latest
            }
            Thread.sleep(200)
        }
        throw AssertionError(
            "no recording of run $runId ($scenario) with at least $minEvents events in $RECORDINGS within $timeout; " +
                "last seen: ${latest?.events?.size ?: 0} events, types ${latest?.events?.groupingBy { it["type"] }?.eachCount()}; $latest",
        )
    }

    private fun readRecording(runId: String): Recording? {
        if (!RECORDINGS.isDirectory()) return null
        for (folder in RECORDINGS.listDirectoryEntries().filter { it.isDirectory() }) {
            val manifestFile = folder.resolve("manifest.json")
            val eventsFile = folder.resolve("events.jsonl")
            if (!manifestFile.exists()) continue
            try {
                val manifest = readMap(Files.readString(manifestFile))
                if (manifest["runId"] != runId) continue
                val events = if (eventsFile.exists()) eventsFile.readLines().filter { it.isNotBlank() }.map(::readMap) else emptyList()
                return Recording(folder, manifest, events)
            } catch (_: JacksonException) {
                // The reporter is mid-write (a partial last line); the next poll reads it whole.
                return null
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun readMap(json: String): Map<String, Any?> = JSON.readValue(json, Map::class.java) as Map<String, Any?>

    companion object {
        private const val BASE_URL = "http://localhost:8080"
        private const val FRONTEND_URL = "http://localhost:3000"
        private val PNG_SIGNATURE = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }

        private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(3)

        /** The reporter's volume (`./recordings:/recordings` in docker-compose.yml); tests run from e2e-tests/. */
        private val RECORDINGS: Path = Path.of("..", "recordings").toAbsolutePath().normalize()

        private val JSON = JsonMapper.builder().build()

        /** Whether the engine is Podman (its Docker-compatible API): it needs the adjustments of README "With Podman". */
        private val PODMAN: Boolean = DockerClientFactory.instance().client().versionCmd().exec()
            .components.orEmpty().any { it.name.orEmpty().contains("Podman", ignoreCase = true) }

        private val COMPOSE_FILES: Array<File> =
            listOfNotNull(File("../docker-compose.yml"), File("../docker-compose.podman.yml").takeIf { PODMAN }).toTypedArray()

        @Container
        @JvmField
        val stack: ComposeContainer = ComposeContainer(*COMPOSE_FILES) // runs the local `docker compose`
            .withBuild(true)
            // On Podman, images are built by Podman itself (classic builder) rather than streamed in from BuildKit.
            .apply { if (PODMAN) withEnv(mapOf("DOCKER_BUILDKIT" to "0", "COMPOSE_BAKE" to "false")) }
            // Missing images are pulled by `compose up`; an explicit pull would fail on the locally built reporter.
            .withPull(false)
            // Exposed services are probed through Testcontainers' ambassador; the tests use the fixed host ports.
            // The ambassador needs container links, which Podman does not support: there, seedOrder waits instead.
            .apply {
                if (!PODMAN) {
                    withExposedService("reporter", 4318, Wait.forHttp("/api/recordings/active").forStatusCodeMatching { it < 500 })
                    withExposedService("orders-service", 8080, Wait.forHttp("/actuator/health"))
                    withExposedService("inventory-service", 8081, Wait.forHttp("/actuator/health"))
                    withExposedService("frontend", 80, Wait.forHttp("/"))
                }
            }
            .withStartupTimeout(STARTUP_TIMEOUT)

        private lateinit var seededOrderId: String
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        /**
         * get-order needs an existing order. It is created here, outside any @StackScenario, so the request is
         * untagged and the get-order run holds only the GET (the seed lands in the reporter's `_unassigned`).
         */
        @JvmStatic
        @BeforeAll
        fun seedOrder() {
            if (PODMAN) {
                awaitHttp("http://localhost:4318/api/recordings/active")
                awaitHttp("$BASE_URL/actuator/health")
                awaitHttp("http://localhost:8081/actuator/health")
                awaitHttp(FRONTEND_URL)
            }
            seededOrderId = OrdersScenarios(BASE_URL).createOrder()
            playwright = Playwright.create() // downloads Chromium on the first run
            browser = playwright.chromium().launch()
        }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            if (::playwright.isInitialized) playwright.close()
        }

        /**
         * Waits until [url] answers this machine with a status below 500. On Podman the published ports start to
         * forward a while after the containers are up (and healthy), and until then connections are cut.
         */
        private fun awaitHttp(url: String) {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5)).build()
            val deadline = Instant.now().plus(STARTUP_TIMEOUT)
            var last: Any? = null
            while (Instant.now().isBefore(deadline)) {
                try {
                    val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                    if (status < 500) return
                    last = "status $status"
                } catch (e: IOException) {
                    last = e
                }
                Thread.sleep(500)
            }
            throw AssertionError("$url did not answer within $STARTUP_TIMEOUT; last: $last")
        }
    }
}
