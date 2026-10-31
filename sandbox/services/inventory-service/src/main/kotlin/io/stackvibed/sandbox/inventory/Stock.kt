package io.stackvibed.sandbox.inventory

/** One row of the stock table: how many units of [sku] can still be reserved. */
data class Stock(val sku: String, val available: Int)
