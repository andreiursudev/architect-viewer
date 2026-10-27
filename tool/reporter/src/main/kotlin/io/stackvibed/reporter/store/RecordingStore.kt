package io.stackvibed.reporter.store

import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.reporter.runs.RunAssignment
import io.stackvibed.reporter.runs.UNASSIGNED_RUN
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Writes `<root>/<folder>/manifest.json` and `events.jsonl` per run (the files the viewer reads).
 * Events of a run are serialised by a per-run lock and each is a single append of one whole line;
 * the manifest is replaced atomically after every append.
 */
class RecordingStore(
    private val root: Path,
    private val clock: Clock,
    private val reporterVersion: String,
) {
    private class Run(val folder: Path, var manifest: RecordingManifest?)

    private val log = LoggerFactory.getLogger(RecordingStore::class.java)
    private val mapper = jacksonMapperBuilder().build()
    private val runs = ConcurrentHashMap<String, Run>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    init {
        rebuildIndex()
    }

    fun folderOf(runId: String): Path? =
        if (runId == UNASSIGNED_RUN) root.resolve(UNASSIGNED_RUN).takeIf(Files::isDirectory) else runs[runId]?.folder

    /** Creates the run's folder and manifest if they do not exist yet; returns the folder. */
    fun ensureRun(assignment: RunAssignment): Path = withRun(assignment.runId) { openRun(assignment).folder }

    fun append(assignment: RunAssignment, events: List<RecordingEvent>) {
        if (events.isEmpty()) return
        withRun(assignment.runId) {
            val run = openRun(assignment)
            for (event in events) {
                val line = mapper.writeValueAsString(event) + "\n"
                Files.write(
                    run.folder.resolve(EVENTS_FILE), line.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                )
            }
            run.manifest?.let { current ->
                writeManifest(
                    run,
                    current.copy(
                        lastEventAt = clock.instant(),
                        componentsSeen = (current.componentsSeen + events.map { it.component }).distinct().sorted(),
                    ),
                )
            }
        }
    }

    fun markStopped(runId: String): RecordingManifest = withRun(runId) {
        val run = runs[runId] ?: throw NoSuchElementException("No recording for run '$runId'")
        val current = checkNotNull(run.manifest)
        current.copy(stoppedAt = clock.instant()).also { writeManifest(run, it) }
    }

    private fun <T> withRun(runId: String, block: () -> T): T = locks.computeIfAbsent(runId) { ReentrantLock() }.withLock(block)

    /** Must be called with the run's lock held. */
    private fun openRun(assignment: RunAssignment): Run {
        if (assignment.runId == UNASSIGNED_RUN) {
            return runs.getOrPut(UNASSIGNED_RUN) { Run(Files.createDirectories(root.resolve(UNASSIGNED_RUN)), null) }
        }
        runs[assignment.runId]?.let { return it }
        val startedAt = clock.instant()
        val folder = uniqueFolder(FolderNames.of(startedAt, assignment.scenarioId, assignment.runId))
        Files.createDirectories(folder)
        val run = Run(folder, null)
        writeManifest(
            run,
            RecordingManifest(
                runId = assignment.runId,
                scenarioId = assignment.scenarioId,
                kind = assignment.kind,
                startedAt = startedAt,
                lastEventAt = null,
                stoppedAt = null,
                reporterVersion = reporterVersion,
                componentsSeen = emptyList(),
            ),
        )
        runs[assignment.runId] = run
        return run
    }

    /** Two runs whose ids share a prefix can produce the same name in the same second; never merge them. */
    private fun uniqueFolder(name: String): Path {
        var candidate = root.resolve(name)
        var n = 2
        while (Files.exists(candidate)) candidate = root.resolve("$name-${n++}")
        return candidate
    }

    private fun writeManifest(run: Run, manifest: RecordingManifest) {
        val target = run.folder.resolve(MANIFEST_FILE)
        val temp = run.folder.resolve("$MANIFEST_FILE.tmp")
        Files.writeString(temp, mapper.writeValueAsString(manifest))
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        run.manifest = manifest
    }

    private fun rebuildIndex() {
        if (!Files.isDirectory(root)) return
        Files.newDirectoryStream(root).use { folders ->
            for (folder in folders) {
                val manifestFile = folder.resolve(MANIFEST_FILE)
                if (!Files.isRegularFile(manifestFile)) continue
                try {
                    val manifest: RecordingManifest = mapper.readValue(Files.readString(manifestFile))
                    runs[manifest.runId] = Run(folder, manifest)
                } catch (e: Exception) {
                    log.warn("Ignoring unreadable manifest {}: {}", manifestFile, e.message)
                }
            }
        }
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val EVENTS_FILE = "events.jsonl"
    }
}
