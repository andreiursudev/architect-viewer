package io.stackvibed.sandbox.inventory

import java.util.UUID

data class NewReservation(val sku: String, val quantity: Int)

/** Only answered, never stored: the sandbox keeps stock levels, not a reservations table. */
data class Reservation(val id: UUID, val sku: String, val quantity: Int)
