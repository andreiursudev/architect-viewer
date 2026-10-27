package io.stackvibed.viewer.web

import io.stackvibed.model.workspace.BoardLayout
import io.stackvibed.viewer.workspace.WorkspaceStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class LayoutController(private val workspace: WorkspaceStore) {
    @GetMapping("/api/boards/{board}/layout")
    fun get(@PathVariable board: String): ResponseEntity<BoardLayout> =
        workspace.readLayout(board)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PutMapping("/api/boards/{board}/layout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun put(@PathVariable board: String, @RequestBody layout: BoardLayout) = workspace.writeLayout(board, layout)
}
