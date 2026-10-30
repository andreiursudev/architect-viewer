package io.stackvibed.agent.method

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.stackvibed.sample.PlainHelper
import io.stackvibed.sample.SampleApi
import io.stackvibed.sample.SampleService
import io.stackvibed.sample.sub.NestedComponent
import io.stackvibed.samplex.SiblingService
import net.bytebuddy.description.type.TypeDescription
import org.junit.jupiter.api.Test

class MethodMatchersTest {

    private fun matches(type: Class<*>, vararg packages: String): Boolean =
        MethodMatchers.types(packages.toList()).matches(TypeDescription.ForLoadedType.of(type))

    @Test
    fun `stereotype class in base package matches`() {
        matches(SampleService::class.java, "io.stackvibed.sample") shouldBe true
        matches(SampleService::class.java, "io.other", "io.stackvibed.sample") shouldBe true
    }

    @Test
    fun `sub package matches and sibling package does not`() {
        matches(NestedComponent::class.java, "io.stackvibed.sample") shouldBe true
        matches(SiblingService::class.java, "io.stackvibed.sample") shouldBe false
    }

    @Test
    fun `class without stereotype does not match`() {
        matches(PlainHelper::class.java, "io.stackvibed.sample") shouldBe false
    }

    @Test
    fun `interface does not match`() {
        matches(SampleApi::class.java, "io.stackvibed.sample") shouldBe false
    }

    @Test
    fun `cglib proxy name does not match`() {
        matches(SampleService.`Proxy$$SpringCGLIB$$0`::class.java, "io.stackvibed.sample") shouldBe false
    }

    @Test
    fun `no packages matches nothing`() {
        matches(SampleService::class.java) shouldBe false
    }

    @Test
    fun `only public instance methods with a body and a non-accessor name match`() {
        val methods = MethodMatchers.methods()
        val matched = TypeDescription.ForLoadedType.of(SampleService::class.java).declaredMethods
            .filter { methods.matches(it) }
            .map { it.name }
        matched shouldContainExactlyInAnyOrder listOf("issue")
    }

    @Test
    fun `accessor object and kotlin generated names are excluded`() {
        val excluded = listOf(
            "getName" to 0, "isOpen" to 0, "setName" to 1, "toString" to 0, "equals" to 1,
            "hashCode" to 0, "copy" to 3, "component2" to 0, "create\$default" to 4,
        )
        for ((name, count) in excluded) {
            withClue("$name/$count") { MethodMatchers.isExcludedName(name, count) shouldBe true }
        }
    }

    @Test
    fun `business names are kept`() {
        val kept = listOf("getOrCreate" to 1, "get" to 1, "issue" to 0, "settle" to 0, "create" to 1)
        for ((name, count) in kept) {
            withClue("$name/$count") { MethodMatchers.isExcludedName(name, count) shouldBe false }
        }
    }
}
