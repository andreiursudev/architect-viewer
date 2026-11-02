package io.stackvibed.model

import java.time.Instant

/**
 * One line of a run's `screenshots.jsonl`: the screenshot the test harness took once the ui steps [spanIds] had
 * settled, stored as [file] in the run's folder. Steps that settle together share one screenshot; [id] is the first
 * of them.
 */
data class ScreenshotRecord(
    val id: String,
    val file: String,
    val spanIds: List<String>,
    val takenAt: Instant,
) {
    companion object {
        /** A span id, and so a screenshot id (the first span id of its batch). */
        val ID: Regex = Regex("^[0-9a-f]{16}$")

        fun fileOf(id: String): String = "screenshots/$id.png"
    }
}
