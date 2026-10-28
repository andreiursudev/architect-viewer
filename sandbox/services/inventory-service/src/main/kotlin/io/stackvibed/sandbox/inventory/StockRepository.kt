package io.stackvibed.sandbox.inventory

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Plain SQL through [JdbcClient]: every statement is a prepared statement with bound parameters. */
@Repository
class StockRepository(private val jdbc: JdbcClient) {

    /** Takes [quantity] units of [sku] out of stock in one statement; false when there is not enough (or no such sku). */
    fun reserve(sku: String, quantity: Int): Boolean =
        jdbc.sql("update stock set available = available - ? where sku = ? and available >= ?")
            .params(quantity, sku, quantity)
            .update() == 1
}
