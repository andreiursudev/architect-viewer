package io.stackvibed.sandbox.e2e

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.stackvibed.harness.StackContext
import io.stackvibed.harness.StackScenario
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

/**
 * Step 1 acceptance: the whole stack (Postgres, reporter, orders-service under the agent) comes up with Docker
 * Compose, the tagged scenarios run against it, and the reporter's recordings describe the expected flow.
 */
@Testcontainers
class StepOneAcceptanceTest {

    @Test
    @StackScenario("create-order")
    fun `create order is recorded end to end`() {
        OrdersScenarios(BASE_URL).createOrder()

        val run = awaitRecording(scenario = "create-order", minEvents = 2)

        run.manifest["kind"] shouldBe "e2e"
        run.manifest["scenarioId"] shouldBe "create-order"
        run.events.single { it["type"] == "http.server" }.let {
            it["component"] shouldBe "orders-service"
            it["method"] shouldBe "POST"
            it["route"] shouldBe "/orders"
            it["status"] shouldBe 201
            (it["requestBody"] as String) shouldContain "c-1"
            (it["responseBody"] as String) shouldContain "CREATED"
        }
        // Exactly one statement: the reporter drops the empty queries the connection pool validates connections with.
        val statements = run.events.filter { it["type"] == "db.client" }
        statements shouldHaveSize 1
        statements.single().let {
            (it["statement"] as String) shouldStartWith "insert into orders"
            it["dbSystem"] shouldBe "postgresql"
            it["dbName"] shouldBe "orders"
            (it["parameters"] as List<*>) shouldContain "sku-1"
            it["rowCount"] shouldBe 1
        }
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

    /** A recorded run: its manifest and its events, both as plain JSON maps (no dependency on tool internals). */
    private data class Recording(val manifest: Map<String, Any?>, val events: List<Map<String, Any?>>)

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
                "last seen: $latest",
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
                return Recording(manifest, events)
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

        /** The reporter's volume (`./recordings:/recordings` in docker-compose.yml); tests run from e2e-tests/. */
        private val RECORDINGS: Path = Path.of("..", "recordings").toAbsolutePath().normalize()

        private val JSON = JsonMapper.builder().build()

        @Container
        @JvmField
        val stack: ComposeContainer = ComposeContainer(File("../docker-compose.yml")) // runs the local `docker compose`
            .withBuild(true)
            // Missing images are pulled by `compose up`; an explicit pull would fail on the locally built reporter.
            .withPull(false)
            // Exposed services are probed through Testcontainers' ambassador; the tests use the fixed host ports.
            .withExposedService("reporter", 4318, Wait.forHttp("/api/recordings/active").forStatusCodeMatching { it < 500 })
            .withExposedService("orders-service", 8080, Wait.forHttp("/actuator/health"))
            .withStartupTimeout(Duration.ofMinutes(3))

        private lateinit var seededOrderId: String

        /**
         * get-order needs an existing order. It is created here, outside any @StackScenario, so the request is
         * untagged and the get-order run holds only the GET (the seed lands in the reporter's `_unassigned`).
         */
        @JvmStatic
        @BeforeAll
        fun seedOrder() {
            seededOrderId = OrdersScenarios(BASE_URL).createOrder()
        }
    }
}
