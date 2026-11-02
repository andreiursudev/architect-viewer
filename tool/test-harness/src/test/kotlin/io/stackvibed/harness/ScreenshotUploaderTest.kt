package io.stackvibed.harness

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException

class ScreenshotUploaderTest {
    private val run = ScenarioRun("browse-stock", "6f1c1f4e-0b9e-4c55-9a43-8d5e2a1c7b10")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test
    fun `posts the png with the run, scenario and span ids`() {
        FakeReporter().use { reporter ->
            ScreenshotUploader(reporter.url).upload(run, listOf("04b7ffff7cd2b57d", "d6c8a8bdd007c68c"), png)

            val upload = reporter.uploads.single()
            upload.path shouldBe "/api/runs/6f1c1f4e-0b9e-4c55-9a43-8d5e2a1c7b10/screenshots"
            upload.query shouldBe "scenarioId=browse-stock&spanIds=04b7ffff7cd2b57d,d6c8a8bdd007c68c"
            upload.contentType shouldBe "image/png"
            upload.body shouldBe png
        }
    }

    @Test
    fun `a refused upload throws`() {
        FakeReporter(status = 400).use { reporter ->
            shouldThrow<IOException> { ScreenshotUploader(reporter.url).upload(run, listOf("04b7ffff7cd2b57d"), png) }
        }
    }
}
