package io.stackvibed.sandbox.inventory

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/stock")
class StockController(private val stock: StockRepository) {

    @GetMapping
    fun list(): List<Stock> = stock.findAll()
}
