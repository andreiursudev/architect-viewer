package io.stackvibed.fixture

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class BigEntry(val index: Int, val payload: String)

/** Bodies that stress capture: a ~5 MB JSON array, a plain-text body and a binary resource. */
@RestController
class PayloadController {

    @GetMapping("/big")
    fun big(): List<BigEntry> = List(BIG_ENTRIES) { BigEntry(it, PAYLOAD) }

    @GetMapping("/text", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun text(): String = "hello"

    @GetMapping("/file")
    fun file(): ResponseEntity<Resource> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(ByteArrayResource("file-content".toByteArray()))

    private companion object {
        // Each entry serialises to ~105 chars: 50 000 entries ≈ 5 MB.
        const val BIG_ENTRIES = 50_000
        val PAYLOAD = "x".repeat(80)
    }
}
