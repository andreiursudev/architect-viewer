package io.stackvibed.viewer.recordings

import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.ScreenshotRecord
import java.nio.file.Path

/**
 * The recordings module's public API: what the reporter wrote (one folder per run with `manifest.json` and
 * `events.jsonl`) plus the committed `reference-recordings/` of the workspace. The `_unassigned` folder is never a run.
 */
interface RecordingRepository {
    /** Runs found in the recordings folder, oldest first. */
    fun manifests(): List<RecordingManifest>

    /** Runs found in `<workspace>/reference-recordings`, oldest first. */
    fun referenceManifests(): List<RecordingManifest>

    /** Events of a run (recordings first, then references) in file order; a half-written trailing line is ignored. */
    fun events(runId: String): List<RecordingEvent>

    /** Screenshot records of a run in file order (none without `screenshots.jsonl`); an unreadable line is skipped. */
    fun screenshots(runId: String): List<ScreenshotRecord>

    /** The PNG of the run's screenshot [id]; null unless [id] is a span id, a record's id and its file exists. */
    fun screenshot(runId: String, id: String): Path?
}
