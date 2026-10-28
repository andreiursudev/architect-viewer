package io.stackvibed.reporter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.stackvibed.model.RecordingKind
import io.stackvibed.model.RecordingManifest
import io.stackvibed.reporter.otlp.OtlpFixtures
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.name
import kotlin.io.path.readText

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReporterGoldenTest.FixedClockConfig::class)
class ReporterGoldenTest {
    @TestConfiguration
    class FixedClockConfig {
        @Bean @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-10-27T22:41:05Z"), ZoneOffset.UTC)
    }

    companion object {
        private val recordings: Path = Files.createTempDirectory("reporter-golden")

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("stack.reporter.recordings-dir") { recordings.toString() }
            registry.add("stack.reporter.version") { "0.1.0-test" }
        }
    }

    @LocalServerPort private var port: Int = 0
    private val http = HttpClient.newHttpClient()
    private val reader = jacksonMapperBuilder().build()

    private fun url(path: String) = URI.create("http://localhost:$port$path")

    private fun postProtobuf(path: String, request: ExportTraceServiceRequest): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(url(path)).header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun postJson(path: String, body: String?): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(url(path)).header("Content-Type", "application/json")
                .POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "missing golden $name" }.readText()

    private fun recordingFolder(suffix: String): Path =
        Files.list(recordings).use { it.filter { p -> p.name.endsWith(suffix) }.toList() }.single()

    @Test fun `create-order fixture produces golden recording`() {
        val response = postProtobuf("/v1/traces", OtlpFixtures.createOrderRun())
        response.statusCode() shouldBe 200
        ExportTraceServiceResponse.parseFrom(response.body()) shouldBe ExportTraceServiceResponse.getDefaultInstance()

        val folder = recordingFolder("_create-order_run-1")
        folder.name shouldBe "2026-10-27T22-41-05_create-order_run-1"
        folder.resolve("events.jsonl").readText() shouldBe golden("create-order/events.jsonl")
        folder.resolve("manifest.json").readText() shouldBe golden("create-order/manifest.json")
    }

    @Test fun `two services same run share one folder`() {
        for (request in OtlpFixtures.createOrderReservesRun()) postProtobuf("/v1/traces", request).statusCode() shouldBe 200

        val folder = recordingFolder("_create-order_run-2")
        // componentsSeen is kept sorted, not in arrival order.
        reader.readValue<RecordingManifest>(folder.resolve("manifest.json").readText()).componentsSeen shouldBe
            listOf("inventory-service", "orders-service")
        folder.resolve("events.jsonl").readText() shouldBe golden("create-order-reserves/events.jsonl")
        folder.resolve("manifest.json").readText() shouldBe golden("create-order-reserves/manifest.json")
    }

    @Test fun `malformed protobuf is a 400`() {
        val response = http.send(
            HttpRequest.newBuilder(url("/v1/traces")).header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(0x0a, 0x7f, 0x01))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        response.statusCode() shouldBe 400
    }

    @Test fun `start and stop window over http`() {
        get("/api/recordings/active").statusCode() shouldBe 204
        postJson("/api/recordings/start", """{"scenarioId":"Not A Slug"}""").statusCode() shouldBe 400

        val started = postJson("/api/recordings/start", """{"scenarioId":"manual-x"}""")
        started.statusCode() shouldBe 201
        val startedJson = reader.readTree(started.body())
        val startedRunId = startedJson["runId"].asString()
        val folderName = startedJson["folder"].asString()
        folderName shouldContain "_manual-x_"
        get("/api/recordings/active").statusCode() shouldBe 200
        postJson("/api/recordings/start", """{"scenarioId":"another"}""").statusCode() shouldBe 409

        val untagged = OtlpFixtures.request("orders-service", listOf(OtlpFixtures.serverSpan(null, null), OtlpFixtures.dbSpan(null, null)))
        postProtobuf("/v1/traces", untagged).statusCode() shouldBe 200

        val folder = recordings.resolve(folderName)
        reader.readValue<RecordingManifest>(folder.resolve("manifest.json").readText()).apply {
            runId shouldBe startedRunId
            kind shouldBe RecordingKind.MANUAL
            scenarioId shouldBe "manual-x"
            lastEventAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
            stoppedAt shouldBe null
        }
        Files.readAllLines(folder.resolve("events.jsonl")).size shouldBe 2

        val stopped = postJson("/api/recordings/stop", null)
        stopped.statusCode() shouldBe 200
        reader.readValue<RecordingManifest>(stopped.body()).stoppedAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
        reader.readValue<RecordingManifest>(folder.resolve("manifest.json").readText()).stoppedAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
        postJson("/api/recordings/stop", null).statusCode() shouldBe 404
        get("/api/recordings/active").statusCode() shouldBe 204
    }
}
