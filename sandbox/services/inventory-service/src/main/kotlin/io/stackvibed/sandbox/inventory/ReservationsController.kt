package io.stackvibed.sandbox.inventory

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/reservations")
class ReservationsController(private val stock: StockRepository) {

    @PostMapping
    fun reserve(@RequestBody request: NewReservation): ResponseEntity<Reservation> {
        if (!stock.reserve(request.sku, request.quantity)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "not enough stock for ${request.sku}")
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Reservation(UUID.randomUUID(), request.sku, request.quantity))
    }
}
