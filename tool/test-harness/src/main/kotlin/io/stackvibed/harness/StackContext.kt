package io.stackvibed.harness

import java.security.SecureRandom
import java.util.HexFormat

/** One execution of a [StackScenario] test. [runId] is a fresh UUID per test invocation. */
data class ScenarioRun(val scenarioId: String, val runId: String)

/** The scenario active on the current thread, and the HTTP headers that carry it to the service under test. */
object StackContext {
    private val active = ThreadLocal<ScenarioRun?>()
    private val random = SecureRandom()
    private val hex = HexFormat.of()

    fun current(): ScenarioRun? = active.get()

    /** W3C `baggage` and a fresh `traceparent`; empty when no scenario is active. */
    fun headers(): Map<String, String> {
        val run = current() ?: return emptyMap()
        return mapOf(
            "baggage" to "stack.scenario=${run.scenarioId},stack.run=${run.runId}",
            "traceparent" to "00-${randomHex(16)}-${randomHex(8)}-01",
        )
    }

    internal fun begin(run: ScenarioRun) = active.set(run)

    internal fun end() = active.remove()

    // An all-zero trace or span id is invalid in W3C trace context; 2^-64 odds, but cheap to rule out.
    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        do {
            random.nextBytes(buffer)
        } while (buffer.all { it == 0.toByte() })
        return hex.formatHex(buffer)
    }
}
