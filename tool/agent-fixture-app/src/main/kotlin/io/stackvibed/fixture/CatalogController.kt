package io.stackvibed.fixture

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream

data class Quote(val catalog: String, val sku: String, val quantity: Int, val total: Long)

/** A node that points at itself: rendering it must stop at the cycle. */
class CyclicNode(val name: String) {
    var next: CyclicNode? = null
}

/** Endpoints that drive [CatalogService] for the method-capture agent tests. */
@RestController
class CatalogController(private val catalog: CatalogService) {

    @GetMapping("/catalog/price")
    fun price(@RequestParam sku: String, @RequestParam quantity: Int): Quote =
        Quote(catalog.getName(), sku, quantity, catalog.price(sku, quantity))

    @GetMapping("/catalog/fail")
    fun fail(): String = catalog.fail("no stock for sku-9")

    @GetMapping("/catalog/count")
    fun count(): Long = catalog.count()

    /** Passes the (huge) body, a cyclic object and a stream to [CatalogService.echo]. */
    @PostMapping("/catalog/echo")
    fun echo(@RequestBody body: String): Int {
        val node = CyclicNode("loop").also { it.next = it }
        return catalog.echo(body) + catalog.echo(node) + catalog.echo(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
    }

    /** Answers 500 with the exception as the method threw it, so the test can see it arrived unchanged. */
    @ExceptionHandler(IllegalStateException::class)
    fun failed(e: IllegalStateException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("${e.javaClass.name}: ${e.message}")
}
