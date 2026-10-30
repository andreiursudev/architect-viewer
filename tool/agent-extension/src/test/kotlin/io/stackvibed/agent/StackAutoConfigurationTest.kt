package io.stackvibed.agent

import io.kotest.matchers.maps.shouldContain
import org.junit.jupiter.api.Test

class StackAutoConfigurationTest {

    @Test
    fun `the span queue holds the spans of a method-heavy request`() {
        // The SDK's default (2048) silently drops spans; the server span ends last, so it is the one lost.
        StackAutoConfiguration.defaults() shouldContain ("otel.bsp.max.queue.size" to "65536")
    }

    @Test
    fun `spans are exported over local OTLP without sampling`() {
        val defaults = StackAutoConfiguration.defaults()
        defaults shouldContain ("otel.exporter.otlp.endpoint" to "http://localhost:4318")
        defaults shouldContain ("otel.traces.sampler" to "always_on")
    }
}
