package io.stackvibed.sandbox.shipping

import java.time.Instant
import java.util.UUID

/** One row of the shipments table: what shipping-service made of an `order-placed` record. */
data class Shipment(val orderId: UUID, val status: String, val createdAt: Instant)

/** What `GET /shipments/{orderId}` returns. */
data class ShipmentView(val orderId: UUID, val status: String)
