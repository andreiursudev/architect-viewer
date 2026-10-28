package io.stackvibed.sandbox.orders

import java.time.Instant
import java.util.UUID

enum class OrderStatus { CREATED }

data class NewOrder(val customerId: String, val sku: String, val quantity: Int)

data class Order(
    val id: UUID,
    val customerId: String,
    val sku: String,
    val quantity: Int,
    val status: OrderStatus,
    val createdAt: Instant,
)
