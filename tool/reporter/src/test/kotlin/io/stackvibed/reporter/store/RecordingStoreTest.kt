package io.stackvibed.reporter.store

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingKind
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.ScreenshotRecord
import io.stackvibed.reporter.otlp.OtlpFixtures
import io.stackvibed.reporter.otlp.SpanEventMapper
import io.stackvibed.reporter.runs.RunAssignment
import io.stackvibed.reporter.runs.UNASSIGNED_RUN
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.io.path.readLines

class RecordingStoreTest {
    @TempDir lateinit var root: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T22:41:05Z"), ZoneOffset.UTC)
    private val mapper = jacksonMapperBuilder().build()
    private val e2e = RunAssignment("run-1", "create-order", RecordingKind.E2E)

    private fun store() = RecordingStore(root, clock, "0.1.0-test")

    private fun events(runId: String): List<RecordingEvent> =
        SpanEventMapper().map(OtlpFixtures.createOrderRun()).map { it.toEvent.withRun(runId) }

    private fun manifest(folder: Path): RecordingManifest = mapper.readValue(Files.readString(folder.resolve("manifest.json")))

    @Test fun `first append creates folder and manifest`() {
        val store = store()
        store.append(e2e, events("run-1"))

        val folder = store.folderOf("run-1")!!
        folder.name shouldBe "2026-10-27T22-41-05_create-order_run-1"
        folder.parent shouldBe root
        val manifest = manifest(folder)
        manifest.kind shouldBe RecordingKind.E2E
        manifest.runId shouldBe "run-1"
        manifest.scenarioId shouldBe "create-order"
        manifest.startedAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
        manifest.lastEventAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
        manifest.stoppedAt shouldBe null
        manifest.reporterVersion shouldBe "0.1.0-test"
        manifest.componentsSeen shouldBe listOf("orders-service")
    }

    @Test fun `events are appended one json per line`() {
        val store = store()
        val first = events("run-1")
        store.append(e2e, first)
        store.append(e2e, first.take(1))

        val lines = store.folderOf("run-1")!!.resolve("events.jsonl").readLines()
        lines shouldHaveSize 3
        lines.take(2).map { mapper.readValue<RecordingEvent>(it) } shouldBe first
        lines.forEach { it.contains('\n') shouldBe false }
    }

    @Test fun concurrentAppendsProduceWholeLines() {
        val store = store()
        val event = events("run-1").first()
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val futures = (1..threads).map {
            pool.submit {
                start.await()
                repeat(perThread) { store.append(e2e, listOf(event)) }
            }
        }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        val lines = store.folderOf("run-1")!!.resolve("events.jsonl").readLines()
        lines shouldHaveSize threads * perThread
        lines.forEach { mapper.readValue<RecordingEvent>(it) shouldBe event }
        Files.list(store.folderOf("run-1")!!).use { it.map(Path::name).toList() }.sorted() shouldBe listOf("events.jsonl", "manifest.json")
    }

    @Test fun restartReusesExistingRunFolder() {
        val before = store()
        before.append(e2e, events("run-1"))
        val folder = before.folderOf("run-1")!!
        val linesBefore = folder.resolve("events.jsonl").readLines().size

        val laterClock = Clock.fixed(Instant.parse("2026-10-27T23:59:59Z"), ZoneOffset.UTC)
        val after = RecordingStore(root, laterClock, "0.1.0-test")
        after.folderOf("run-1") shouldBe folder
        after.append(e2e, events("run-1").take(1))

        after.folderOf("run-1") shouldBe folder
        Files.list(root).use { it.toList() } shouldHaveSize 1
        folder.resolve("events.jsonl").readLines().size shouldBe linesBefore + 1
        val manifest = manifest(folder)
        manifest.startedAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
        manifest.lastEventAt shouldBe Instant.parse("2026-10-27T23:59:59Z")
    }

    @Test fun `unassigned goes to its own folder without manifest`() {
        val store = store()
        store.append(RunAssignment(UNASSIGNED_RUN, null, RecordingKind.MANUAL), events(UNASSIGNED_RUN))

        val folder = root.resolve("_unassigned")
        folder.resolve("events.jsonl").readLines() shouldHaveSize 2
        Files.exists(folder.resolve("manifest.json")) shouldBe false
        store.folderOf(UNASSIGNED_RUN) shouldBe folder
    }

    @Test fun `unassigned events survive a restart without being mistaken for a run`() {
        store().append(RunAssignment(UNASSIGNED_RUN, null, RecordingKind.MANUAL), events(UNASSIGNED_RUN))
        val restarted = store()
        restarted.append(RunAssignment(UNASSIGNED_RUN, null, RecordingKind.MANUAL), events(UNASSIGNED_RUN).take(1))
        root.resolve("_unassigned").resolve("events.jsonl").readLines() shouldHaveSize 3
    }

    @Test fun `folder names contain no unsafe characters`() {
        val store = store()
        val assignment = RunAssignment("a:b/c", "Create Order!", RecordingKind.E2E)
        store.append(assignment, events("a:b/c"))

        store.folderOf("a:b/c")!!.name shouldMatch Regex("^[A-Za-z0-9_-]+$")
        FolderNames.of(clock.instant(), null, "a:b/c") shouldBe "2026-10-27T22-41-05_manual_a-b-c"
        FolderNames.of(clock.instant(), "x", "0123456789abcdef") shouldBe "2026-10-27T22-41-05_x_01234567"
    }

    @Test fun `manual runs record kind and stop sets stoppedAt`() {
        val store = store()
        val manual = RunAssignment("0123456789abcdef", "manual-x", RecordingKind.MANUAL)
        store.ensureRun(manual).name shouldBe "2026-10-27T22-41-05_manual-x_01234567"
        manifest(store.folderOf(manual.runId)!!).lastEventAt shouldBe null

        store.append(manual, events(manual.runId))
        val stopped = store.markStopped(manual.runId)
        stopped.stoppedAt shouldBe Instant.parse("2026-10-27T22:41:05Z")
        stopped.kind shouldBe RecordingKind.MANUAL
        manifest(store.folderOf(manual.runId)!!) shouldBe stopped
    }

    @Test fun `distinct run ids that share a folder name get distinct folders`() {
        val store = store()
        store.append(RunAssignment("abcdefgh-1", "s", RecordingKind.E2E), events("abcdefgh-1"))
        store.append(RunAssignment("abcdefgh-2", "s", RecordingKind.E2E), events("abcdefgh-2"))
        (store.folderOf("abcdefgh-1") == store.folderOf("abcdefgh-2")) shouldBe false
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test fun `a screenshot is written with its record`() {
        val store = store()
        val record = store.addScreenshot(e2e, listOf("04b7ffff7cd2b57d", "d6c8a8bdd007c68c"), png)

        val folder = store.folderOf("run-1")!!
        record shouldBe ScreenshotRecord(
            id = "04b7ffff7cd2b57d",
            file = "screenshots/04b7ffff7cd2b57d.png",
            spanIds = listOf("04b7ffff7cd2b57d", "d6c8a8bdd007c68c"),
            takenAt = Instant.parse("2026-10-27T22:41:05Z"),
        )
        Files.readAllBytes(folder.resolve("screenshots/04b7ffff7cd2b57d.png")) shouldBe png
        folder.resolve("screenshots.jsonl").readLines().map { mapper.readValue<ScreenshotRecord>(it) } shouldBe listOf(record)
    }

    @Test fun `spans after a screenshot join its run`() {
        val store = store()
        store.addScreenshot(e2e, listOf("04b7ffff7cd2b57d"), png)
        store.append(e2e, events("run-1"))

        val folders = Files.list(root).use { it.toList() }
        folders shouldHaveSize 1
        val folder = folders.single()
        manifest(folder).scenarioId shouldBe "create-order"
        Files.exists(folder.resolve("screenshots/04b7ffff7cd2b57d.png")) shouldBe true
        Files.exists(folder.resolve("screenshots.jsonl")) shouldBe true
        Files.exists(folder.resolve("events.jsonl")) shouldBe true
    }

    @Test fun `concurrent screenshots of one run keep whole lines`() {
        val store = store()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ids = (1..20).map { "%016x".format(it) }
        for (id in ids) pool.submit { start.await(); store.addScreenshot(e2e, listOf(id), png) }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS) shouldBe true

        val folder = store.folderOf("run-1")!!
        val records = folder.resolve("screenshots.jsonl").readLines().map { mapper.readValue<ScreenshotRecord>(it) }
        records.map { it.id }.sorted() shouldBe ids
        for (id in ids) Files.readAllBytes(folder.resolve("screenshots/$id.png")) shouldBe png
    }
}
