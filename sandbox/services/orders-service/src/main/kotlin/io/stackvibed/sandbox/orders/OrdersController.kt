package io.stackvibed.sandbox.orders

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/orders")
class OrdersController(private val orders: OrderService) {

    @PostMapping
    fun create(@RequestBody newOrder: NewOrder): ResponseEntity<Order> {
        val order = orders.create(newOrder)
        return ResponseEntity.created(URI.create("/orders/${order.id}")).body(order)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Order =
        orders.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "order $id not found")
}
