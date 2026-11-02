package io.stackvibed.viewer.recordings.internal

import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.ScreenshotRecord
import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.recordings.RecordingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `<recordings>/<folder>/{manifest.json,events.jsonl,screenshots.jsonl,screenshots/}` and
 * `<workspace>/reference-recordings/<folder>/...`.
 *
 * The reporter appends to these files while the viewer reads them, so reading is forgiving: a folder without a readable
 * manifest is skipped, and an unfinished last line of `events.jsonl` or `screenshots.jsonl` is ignored.
 */
@Component
class FileRecordingRepository(paths: ViewerPaths) : RecordingRepository {
    private val recordingsRoot: Path = paths.recordings.toAbsolutePath().normalize()
    private val referenceRoot: Path = paths.workspace.toAbsolutePath().normalize().resolve(REFERENCE_RECORDINGS)

    private val json: JsonMapper = jacksonMapperBuilder().build()

    override fun manifests(): List<RecordingManifest> = runs(recordingsRoot).map { it.manifest }

    override fun referenceManifests(): List<RecordingManifest> = runs(referenceRoot).map { it.manifest }

    override fun events(runId: String): List<RecordingEvent> =
        readLines(folderOf(runId).resolve(EVENTS_FILE), RecordingEvent::class.java)

    override fun screenshots(runId: String): List<ScreenshotRecord> =
        readLines(folderOf(runId).resolve(SCREENSHOTS_FILE), ScreenshotRecord::class.java)

    override fun screenshot(runId: String, id: String): Path? {
        val folder = folderOf(runId)
        if (!ScreenshotRecord.ID.matches(id)) return null
        if (readLines(folder.resolve(SCREENSHOTS_FILE), ScreenshotRecord::class.java).none { it.id == id }) return null
        // The path comes from the checked id, never from the record's file name.
        return folder.resolve(ScreenshotRecord.fileOf(id)).takeIf { Files.isRegularFile(it) }
    }

    private fun folderOf(runId: String): Path =
        (runs(recordingsRoot) + runs(referenceRoot)).firstOrNull { it.manifest.runId == runId }?.folder
            ?: throw NoSuchElementException("No recording with run id '$runId'")

    private class Run(val folder: Path, val manifest: RecordingManifest)

    private fun runs(root: Path): List<Run> {
        if (!Files.isDirectory(root)) return emptyList()
        val folders = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) && it.fileName.toString() != UNASSIGNED }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
        return folders.mapNotNull { folder -> readManifest(folder)?.let { Run(folder, it) } }
            .sortedBy { it.manifest.startedAt }
    }

    private fun readManifest(folder: Path): RecordingManifest? {
        val file = folder.resolve(MANIFEST_FILE)
        if (!Files.isRegularFile(file)) return null
        return try {
            json.readValue(Files.readString(file), RecordingManifest::class.java)
        } catch (e: Exception) {
            log.warn("Skipping recording {}: unreadable manifest ({})", folder.fileName, e.message?.lineSequence()?.first())
            null
        }
    }

    private fun <T> readLines(file: Path, type: Class<T>): List<T> {
        if (!Files.isRegularFile(file)) return emptyList()
        // Decoding never fails: a line cut inside a multi-byte character just becomes an unparsable partial line.
        val text = String(Files.readAllBytes(file), UTF_8)
        val lines = text.split('\n')
        val unterminated = lines.size - 1 // the segment after the last newline (empty when the file ends with one)
        return lines.mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            try {
                json.readValue(line, type)
            } catch (e: Exception) {
                if (index != unterminated) {
                    log.warn("Skipping unreadable line {} of {}: {}", index + 1, file, e.message?.lineSequence()?.first())
                }
                null
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FileRecordingRepository::class.java)

        const val UNASSIGNED = "_unassigned"
        const val REFERENCE_RECORDINGS = "reference-recordings"
        const val MANIFEST_FILE = "manifest.json"
        const val EVENTS_FILE = "events.jsonl"
        const val SCREENSHOTS_FILE = "screenshots.jsonl"
    }
}
