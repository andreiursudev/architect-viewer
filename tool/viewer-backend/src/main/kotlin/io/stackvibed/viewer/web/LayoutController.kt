package io.stackvibed.viewer.web

import io.stackvibed.model.workspace.BoardLayout
import io.stackvibed.viewer.registry.WorkspaceRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class LayoutController(private val registry: WorkspaceRegistry) {
    @GetMapping("/api/w/{ws}/boards/{board}/layout")
    fun get(@PathVariable ws: String, @PathVariable board: String): ResponseEntity<BoardLayout> =
        registry.open(ws).store.readLayout(board)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PutMapping("/api/w/{ws}/boards/{board}/layout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun put(@PathVariable ws: String, @PathVariable board: String, @RequestBody layout: BoardLayout) =
        registry.open(ws).store.writeLayout(board, layout)
}
