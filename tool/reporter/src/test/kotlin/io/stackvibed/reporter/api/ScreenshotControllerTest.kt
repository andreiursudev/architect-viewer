package io.stackvibed.reporter.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.stackvibed.model.RecordingKind
import io.stackvibed.model.RecordingManifest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import java.util.UUID
import kotlin.io.path.listDirectoryEntries

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScreenshotControllerTest {
    companion object {
        private val recordings: Path = Files.createTempDirectory("reporter-screenshots")

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("stack.reporter.recordings-dir") { recordings.toString() }
        }

        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        private const val SPAN = "04b7ffff7cd2b57d"
    }

    @LocalServerPort private var port: Int = 0
    private val http = HttpClient.newHttpClient()
    private val mapper = jacksonMapperBuilder().build()

    private fun upload(
        runId: String = UUID.randomUUID().toString(),
        scenarioId: String = "browse-stock",
        spanIds: String = SPAN,
        body: ByteArray = PNG,
    ): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/runs/$runId/screenshots?scenarioId=$scenarioId&spanIds=$spanIds"))
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun folderOf(runId: String): Path = recordings.listDirectoryEntries().single { it.fileName.toString().endsWith(runId.take(8)) }

    @Test fun `an uploaded screenshot is stored`() {
        val runId = UUID.randomUUID().toString()
        val response = upload(runId = runId, spanIds = "$SPAN,d6c8a8bdd007c68c")

        response.statusCode() shouldBe 201
        mapper.readValue<Map<String, String>>(response.body()) shouldBe mapOf("id" to SPAN)
        Files.readAllBytes(folderOf(runId).resolve("screenshots/$SPAN.png")) shouldBe PNG
        Files.readString(folderOf(runId).resolve("screenshots.jsonl")) shouldContain "\"spanIds\":[\"$SPAN\",\"d6c8a8bdd007c68c\"]"
    }

    @Test fun `a screenshot before any span creates the run`() {
        val runId = UUID.randomUUID().toString()
        upload(runId = runId).statusCode() shouldBe 201

        val manifest = mapper.readValue<RecordingManifest>(Files.readString(folderOf(runId).resolve("manifest.json")))
        manifest.runId shouldBe runId
        manifest.scenarioId shouldBe "browse-stock"
        manifest.kind shouldBe RecordingKind.E2E
    }

    @Test fun `invalid uploads are refused`() {
        val tooBig = PNG + ByteArray(5 * 1024 * 1024 + 1 - PNG.size)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46)
        val refused = listOf(
            "run id" to upload(runId = "run-1"),
            "scenario" to upload(scenarioId = "Browse"),
            "uppercase span id" to upload(spanIds = "04B7FFFF7CD2B57D"),
            "no span ids" to upload(spanIds = ""),
            "21 span ids" to upload(spanIds = (1..21).joinToString(",") { "%016x".format(it) }),
            "not a png" to upload(body = jpeg),
            "over 5 MiB" to upload(body = tooBig),
        )
        refused.map { (what, response) -> what to response.statusCode() } shouldBe refused.map { (what, _) -> what to 400 }
    }
}
