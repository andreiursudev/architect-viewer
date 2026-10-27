package io.stackvibed.viewer

import java.nio.file.Files
import java.nio.file.Path

/** Recording runs under `src/test/resources/fixtures/recordings` (the first is the reporter's golden create-order run). */
object Fixtures {
    const val CREATE_ORDER_1 = "2026-10-27T22-41-05_create-order_run-1"
    const val CREATE_ORDER_2 = "2026-10-27T23-10-00_create-order_run-2"
    const val GET_ORDER_3 = "2026-10-27T23-20-00_get-order_run-3"

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
