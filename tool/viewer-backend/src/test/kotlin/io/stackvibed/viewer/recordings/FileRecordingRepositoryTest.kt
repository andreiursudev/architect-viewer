package io.stackvibed.viewer.recordings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.stackvibed.model.DbClientEvent
import io.stackvibed.model.HttpServerEvent
import io.stackvibed.viewer.Fixtures
import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.recordings.internal.FileRecordingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FileRecordingRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    private val workspace get() = tmp.resolve("stack")
    private val recordings get() = tmp.resolve("recordings")

    private fun repository(): RecordingRepository = FileRecordingRepository(ViewerPaths(workspace, recordings))

    @Test
    fun `reads manifests of all runs`() {
        Fixtures.copyAll(recordings)

        repository().manifests().map { it.runId } shouldContainExactlyInAnyOrder listOf("run-1", "run-2", "run-3", "run-4", "run-5", "run-6")
    }

    @Test
    fun `missing folders mean no recordings`() {
        repository().manifests() shouldBe emptyList()
        repository().referenceManifests() shouldBe emptyList()
    }

    @Test
    fun `reference recordings come from the workspace`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, workspace.resolve("reference-recordings"))
        Fixtures.copyRun(Fixtures.GET_ORDER_3, recordings)

        val repository = repository()

        repository.referenceManifests().map { it.runId } shouldBe listOf("run-1")
        repository.manifests().map { it.runId } shouldBe listOf("run-3")
        repository.events("run-1") shouldHaveSize 2
        repository.events("run-3") shouldHaveSize 1
    }

    @Test
    fun `events are parsed into their types`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordings)

        val events = repository().events("run-1")

        events.shouldHaveSize(2)
        events[0].shouldBeInstanceOf<HttpServerEvent>().route shouldBe "/orders"
        events[1].shouldBeInstanceOf<DbClientEvent>().dbSystem shouldBe "postgresql"
    }

    @Test
    fun `unknown run id is reported`() {
        Fixtures.copyAll(recordings)

        shouldThrow<NoSuchElementException> { repository().events("nope") }
    }

    @Test
    fun `partial trailing line is ignored`() {
        val run = Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordings)
        run.resolve("events.jsonl").appendText("{\"type\":\"http.server\",\"schemaVersion\":1,\"eventId\":\"cut-of")

        repository().events("run-1") shouldHaveSize 2
    }

    @Test
    fun `a trailing line cut inside a multibyte character is ignored`() {
        val run = Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordings)
        val cut = "{\"type\":\"http.server\",\"requestBody\":\"café".toByteArray(Charsets.UTF_8).dropLast(1).toByteArray()
        java.nio.file.Files.write(run.resolve("events.jsonl"), cut, java.nio.file.StandardOpenOption.APPEND)

        repository().events("run-1") shouldHaveSize 2
    }

    @Test
    fun `a run with a manifest but no events yet has no events`() {
        val run = Fixtures.copyRun(Fixtures.CREATE_ORDER_2, recordings)
        java.nio.file.Files.delete(run.resolve("events.jsonl"))

        repository().events("run-2") shouldBe emptyList()
    }

    @Test
    fun `unassigned folder is skipped`() {
        Fixtures.copyAll(recordings)
        // even a manifest in there would not make it a run
        recordings.resolve("_unassigned").resolve("manifest.json")
            .writeText(recordings.resolve(Fixtures.GET_ORDER_3).resolve("manifest.json").toFile().readText().replace("run-3", "_unassigned"))

        repository().manifests().map { it.runId } shouldContainExactlyInAnyOrder listOf("run-1", "run-2", "run-3", "run-4", "run-5", "run-6")
        shouldThrow<NoSuchElementException> { repository().events("_unassigned") }
    }

    @Test
    fun `folders without a readable manifest are skipped`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordings)
        recordings.resolve("no-manifest").createDirectories()
        recordings.resolve("broken").createDirectories()
        recordings.resolve("broken/manifest.json").writeText("{ nope")

        repository().manifests().map { it.runId } shouldBe listOf("run-1")
    }

    @Test
    fun `screenshots of a run are read in order`() {
        Fixtures.copyRun(Fixtures.PLACE_ORDER_IN_BROWSER_6, recordings)

        repository().screenshots("run-6").map { it.spanIds } shouldBe listOf(
            listOf("9a8b7c6d5e4f3021"),
            listOf("6d5e4f302192a3b4", "5e4f302192a3b4c5"),
            listOf("4f302192a3b4c5d6", "2192a3b4c5d6e7f8"),
        )
    }

    @Test
    fun `a run without screenshots has none`() {
        Fixtures.copyRun(Fixtures.CREATE_ORDER_1, recordings)

        repository().screenshots("run-1") shouldBe emptyList()
    }

    @Test
    fun `a half-written screenshot line is ignored`() {
        val run = Fixtures.copyRun(Fixtures.PLACE_ORDER_IN_BROWSER_6, recordings)
        run.resolve("screenshots.jsonl").appendText("{\"id\":\"0123456789abcdef\",\"file\":\"screensh")

        repository().screenshots("run-6") shouldHaveSize 3
    }

    @Test
    fun `screenshot files are found by listed id only`() {
        val run = Fixtures.copyRun(Fixtures.PLACE_ORDER_IN_BROWSER_6, recordings)
        val repository = repository()

        repository.screenshot("run-6", "9a8b7c6d5e4f3021") shouldBe run.resolve("screenshots/9a8b7c6d5e4f3021.png")
        repository.screenshot("run-6", "5e4f302192a3b4c5") shouldBe null // listed in a record, but not a record id
        repository.screenshot("run-6", "0123456789abcdef") shouldBe null
        repository.screenshot("run-6", "../manifest") shouldBe null
        repository.screenshot("run-6", "9A8B7C6D5E4F3021") shouldBe null
        shouldThrow<NoSuchElementException> { repository.screenshot("nope", "9a8b7c6d5e4f3021") }
    }
}
