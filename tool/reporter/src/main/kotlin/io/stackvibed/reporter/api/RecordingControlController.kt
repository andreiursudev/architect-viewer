package io.stackvibed.reporter.api

import io.stackvibed.model.RecordingManifest
import io.stackvibed.reporter.runs.RecordingWindow
import io.stackvibed.reporter.runs.WindowAlreadyActive
import io.stackvibed.reporter.store.RecordingStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.io.path.name

data class StartRecordingRequest(val scenarioId: String?)

data class RecordingStarted(val runId: String, val folder: String)

data class ActiveRecording(val runId: String, val scenarioId: String?, val folder: String)

/** Start/stop of the manual recording window. */
@RestController
@RequestMapping("/api/recordings")
class RecordingControlController(private val window: RecordingWindow, private val store: RecordingStore) {
    @PostMapping("/start")
    fun start(@RequestBody request: StartRecordingRequest): ResponseEntity<Any> {
        val scenarioId = request.scenarioId
        if (scenarioId == null || !SLUG.matches(scenarioId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "scenarioId must match ${SLUG.pattern}"))
        }
        val assignment = try {
            window.start(scenarioId)
        } catch (e: WindowAlreadyActive) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))
        }
        val folder = store.ensureRun(assignment)
        return ResponseEntity.status(HttpStatus.CREATED).body(RecordingStarted(assignment.runId, folder.name))
    }

    @PostMapping("/stop")
    fun stop(): ResponseEntity<RecordingManifest> {
        val assignment = window.stop() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(store.markStopped(assignment.runId))
    }

    @GetMapping("/active")
    fun active(): ResponseEntity<ActiveRecording> {
        val assignment = window.active() ?: return ResponseEntity.noContent().build()
        val folder = store.folderOf(assignment.runId)?.name ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(ActiveRecording(assignment.runId, assignment.scenarioId, folder))
    }

    private companion object {
        val SLUG = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}
