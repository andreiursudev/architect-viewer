package io.stackvibed.harness

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import java.util.Collections

class StackScenarioExtensionTest {

    @Test
    @StackScenario("create-order")
    fun `context is set inside an annotated test`() {
        StackContext.current()!!.scenarioId shouldBe "create-order"
        StackContext.headers()["baggage"]!! shouldMatch Regex("stack\\.scenario=create-order,stack\\.run=[0-9a-f-]{36}")
        StackContext.headers()["traceparent"]!! shouldMatch Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")
    }

    @Test
    fun `no context outside an annotated test`() {
        StackContext.current() shouldBe null
        StackContext.headers().shouldBeEmpty()
    }

    @Test
    @StackScenario("trace-ids")
    fun `traceparent is fresh on every call but baggage is stable`() {
        val first = StackContext.headers()
        val second = StackContext.headers()
        first["baggage"] shouldBe second["baggage"]
        (first["traceparent"] == second["traceparent"]) shouldBe false
    }

    @RepeatedTest(2)
    @StackScenario("repeated")
    fun `each invocation gets a new run id`(repetition: RepetitionInfo) {
        runIds += StackContext.current()!!.runId
        // a repeated run id would leave the set smaller than the number of repetitions so far
        runIds.size shouldBe repetition.currentRepetition
    }

    @Test
    fun `invalid scenario id fails fast`() {
        val results = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(InvalidIdFixture::class.java))
            .execute()
        val failures = results.testEvents().failed().list()
        failures shouldHaveSize 1
        val message = failures.single().getPayload(TestExecutionResult::class.java).get().throwable.get().message!!
        message shouldContain "must match"
        message shouldContain "Bad Id"
    }

    @Tag("fixture")
    class InvalidIdFixture {
        @Test
        @StackScenario("Bad Id")
        fun `never reached`() {
            error("the body must not run for an invalid scenario id")
        }
    }

    companion object {
        private val runIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    }
}
