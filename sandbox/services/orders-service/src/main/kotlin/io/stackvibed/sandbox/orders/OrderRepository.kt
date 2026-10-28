package io.stackvibed.sandbox.orders

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Plain SQL through [JdbcClient]: every statement is a prepared statement with bound parameters. */
@Repository
class OrderRepository(private val jdbc: JdbcClient) {

    fun insert(order: Order) {
        jdbc.sql("insert into orders (id, customer_id, sku, quantity, status, created_at) values (?, ?, ?, ?, ?, ?)")
            .params(
                order.id,
                order.customerId,
                order.sku,
                order.quantity,
                order.status.name,
                // The Postgres driver binds OffsetDateTime (not Instant) to timestamptz.
                order.createdAt.atOffset(ZoneOffset.UTC),
            )
            .update()
    }

    fun find(id: UUID): Order? =
        jdbc.sql("select id, customer_id, sku, quantity, status, created_at from orders where id = ?")
            .param(id)
            .query { rs, _ -> rs.toOrder() }
            .optional()
            .orElse(null)

    private fun ResultSet.toOrder() = Order(
        id = getObject("id", UUID::class.java),
        customerId = getString("customer_id"),
        sku = getString("sku"),
        quantity = getInt("quantity"),
        status = OrderStatus.valueOf(getString("status")),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
}
