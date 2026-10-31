package io.stackvibed.reporter.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsTest {
    companion object {
        private val recordings: Path = Files.createTempDirectory("reporter-cors")

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("stack.reporter.recordings-dir") { recordings.toString() }
        }
    }

    @LocalServerPort private var port: Int = 0
    private val http = HttpClient.newHttpClient()

    private fun preflight(path: String, origin: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun HttpResponse<String>.allowOrigin(): String? = headers().firstValue("Access-Control-Allow-Origin").orElse(null)

    @Test fun `preflight from a localhost origin is allowed`() {
        val response = preflight("/v1/traces", "http://localhost:3000")
        response.statusCode() shouldBe 200
        response.allowOrigin() shouldBe "http://localhost:3000"
    }

    @Test fun `preflight from a loopback address origin is allowed`() {
        val response = preflight("/v1/traces", "http://127.0.0.1:5173")
        response.statusCode() shouldBe 200
        response.allowOrigin() shouldBe "http://127.0.0.1:5173"
    }

    @Test fun `preflight from another origin is refused`() {
        val response = preflight("/v1/traces", "http://example.com")
        response.statusCode() shouldBe 403
        response.allowOrigin() shouldBe null
    }

    @Test fun `control api has no cors`() {
        preflight("/api/recordings/start", "http://localhost:3000").allowOrigin() shouldBe null
    }
}
