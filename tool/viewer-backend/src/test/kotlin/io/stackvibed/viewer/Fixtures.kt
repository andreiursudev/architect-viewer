package io.stackvibed.viewer

import java.nio.file.Files
import java.nio.file.Path

/** Recording runs under `src/test/resources/fixtures/recordings` (the first is the reporter's golden create-order run). */
object Fixtures {
    const val CREATE_ORDER_1 = "2026-10-27T22-41-05_create-order_run-1"
    const val CREATE_ORDER_2 = "2026-10-27T23-10-00_create-order_run-2"
    const val GET_ORDER_3 = "2026-10-27T23-20-00_get-order_run-3"

    /** A schema version 2 run: orders-service calls inventory-service, which answers 409 for `sku-out`. */
    const val OUT_OF_STOCK_4 = "2026-10-28T09-00-00_create-order-out-of-stock_run-4"

    /**
     * A schema version 3 run with method capture on in both services: the latest create-order run. Its orders side has
     * the shape of the reporter's `golden/create-order-methods`; inventory-service answers the reservation.
     */
    const val CREATE_ORDER_METHODS_5 = "2026-10-30T10-00-00_create-order_run-5"

    /**
     * A schema version 4 run: the reporter's golden `place-order-in-browser` frontend events, with the service side of
     * a create-order run re-parented under the frontend's fetches (same trace), plus a `GET /stock` and a
     * `GET /orders/{id}` server event under the other two fetches.
     */
    const val PLACE_ORDER_IN_BROWSER_6 = "2026-10-31T10-00-00_place-order-in-browser_run-6"

    val recordings: Path = Path.of(Fixtures::class.java.getResource("/fixtures/recordings")!!.toURI())

    /** Copies one fixture run folder into [targetRoot] (created if needed) and returns the copy. */
    fun copyRun(name: String, targetRoot: Path): Path = copyTree(recordings.resolve(name), targetRoot.resolve(name))

    fun copyAll(targetRoot: Path) {
        Files.list(recordings).use { it.toList() }.forEach { copyTree(it, targetRoot.resolve(it.fileName.toString())) }
    }

    private fun copyTree(from: Path, to: Path): Path {
        Files.createDirectories(to)
        Files.list(from).use { it.toList() }.forEach { Files.copy(it, to.resolve(it.fileName.toString())) }
        return to
    }
}
