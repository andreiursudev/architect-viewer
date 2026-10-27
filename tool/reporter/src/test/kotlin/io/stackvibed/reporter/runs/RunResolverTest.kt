package io.stackvibed.reporter.runs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.stackvibed.model.RecordingKind
import io.stackvibed.reporter.otlp.SpanTags
import org.junit.jupiter.api.Test

class RunResolverTest {
    private val window = RecordingWindow()
    private val resolver = RunResolver(window)

    @Test fun `tag wins over active window`() {
        window.start("manual-x")
        resolver.resolve(SpanTags("create-order", "run-1")) shouldBe RunAssignment("run-1", "create-order", RecordingKind.E2E)
    }

    @Test fun `window catches untagged spans`() {
        val started = window.start("manual-x")
        resolver.resolve(SpanTags(null, null)) shouldBe RunAssignment(started.runId, "manual-x", RecordingKind.MANUAL)
    }

    @Test fun `untagged without window is unassigned`() {
        resolver.resolve(SpanTags(null, null)) shouldBe RunAssignment(UNASSIGNED_RUN, null, RecordingKind.MANUAL)
    }

    @Test fun `scenario without run id is not a tagged run`() {
        resolver.resolve(SpanTags("create-order", null)).runId shouldBe UNASSIGNED_RUN
    }

    @Test fun `starting a second window is rejected and stop clears the first`() {
        val first = window.start("a")
        shouldThrow<WindowAlreadyActive> { window.start("b") }
        window.active() shouldBe first
        window.stop() shouldBe first
        window.active() shouldBe null
        window.stop() shouldBe null
    }
}
