package io.stackvibed.harness

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Sends a screenshot of a run's ui steps to the reporter, which stores it with the run. */
internal class ScreenshotUploader(
    private val reporterUrl: String = System.getProperty("stack.reporter.url", DEFAULT_REPORTER_URL),
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    /** @throws IOException when the reporter cannot be reached or does not store it */
    fun upload(run: ScenarioRun, spanIds: List<String>, png: ByteArray) {
        // Ids are slugs, UUIDs and hex span ids (validated where they come from), so they need no encoding.
        val uri = URI.create(
            "${reporterUrl.trimEnd('/')}/api/runs/${run.runId}/screenshots" +
                "?scenarioId=${run.scenarioId}&spanIds=${spanIds.joinToString(",")}",
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "image/png")
            .POST(HttpRequest.BodyPublishers.ofByteArray(png))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 201) throw IOException("reporter answered ${response.statusCode()}: ${response.body()}")
    }

    private companion object {
        const val DEFAULT_REPORTER_URL = "http://localhost:4318"
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
