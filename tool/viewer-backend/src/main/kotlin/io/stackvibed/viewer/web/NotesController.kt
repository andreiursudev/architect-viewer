package io.stackvibed.viewer.web

import io.stackvibed.viewer.workspace.WorkspaceStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

private const val MARKDOWN = "text/markdown;charset=UTF-8"

@RestController
class NotesController(private val workspace: WorkspaceStore) {
    @GetMapping("/api/notes/{targetId}", produces = [MARKDOWN])
    fun get(@PathVariable targetId: String): ResponseEntity<String> =
        workspace.readNote(targetId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PutMapping("/api/notes/{targetId}", consumes = ["text/markdown"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun put(@PathVariable targetId: String, @RequestBody(required = false) markdown: String?) =
        workspace.writeNote(targetId, markdown.orEmpty()) // an empty body clears the note
}
