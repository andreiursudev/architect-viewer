package io.stackvibed.viewer.web

import io.stackvibed.viewer.registry.WorkspaceRegistry
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
class NotesController(private val registry: WorkspaceRegistry) {
    @GetMapping("/api/w/{ws}/notes/{targetId}", produces = [MARKDOWN])
    fun get(@PathVariable ws: String, @PathVariable targetId: String): ResponseEntity<String> =
        registry.open(ws).store.readNote(targetId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PutMapping("/api/w/{ws}/notes/{targetId}", consumes = ["text/markdown"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun put(@PathVariable ws: String, @PathVariable targetId: String, @RequestBody(required = false) markdown: String?) =
        registry.open(ws).store.writeNote(targetId, markdown.orEmpty()) // an empty body clears the note
}
