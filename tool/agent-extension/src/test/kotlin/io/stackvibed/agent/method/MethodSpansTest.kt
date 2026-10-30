package io.stackvibed.agent.method

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.stackvibed.agent.contract.StackAttributes
import io.stackvibed.sample.SampleService
import org.junit.jupiter.api.Test

class MethodSpansTest {

    private val paramNames = AttributeKey.stringKey(StackAttributes.METHOD_PARAM_NAMES)
    private val args = AttributeKey.stringKey(StackAttributes.METHOD_ARGS)

    private fun startAttributes(paramNames: Array<String>, vararg arguments: Any?): Attributes {
        val builder = Attributes.builder()
        val call = MethodSpans.Call(SampleService::class.java, "issue", arrayOf(*arguments), paramNames)
        MethodSpans.CallAttributes().onStart(builder, Context.root(), call)
        return builder.build()
    }

    @Test
    fun `class file without parameter names gives no names`() {
        // The Java test sources are compiled without -parameters (javac's default).
        val issue = SampleService::class.java.getMethod("issue", String::class.java, Int::class.javaPrimitiveType)
        MethodSpans.parameterNames(issue).toList().shouldBeEmpty()
    }

    @Test
    fun `param names are absent when the class file has none`() {
        val attributes = startAttributes(emptyArray(), "a", 3)
        attributes.get(paramNames).shouldBeNull()
        attributes.get(args) shouldBe """["\"a\"","3"]"""
    }

    @Test
    fun `param names are recorded when present`() {
        startAttributes(arrayOf("sku", "quantity"), "a", 3).get(paramNames) shouldBe """["sku","quantity"]"""
    }

    @Test
    fun `method without parameters records no param names`() {
        val attributes = startAttributes(emptyArray())
        attributes.get(paramNames).shouldBeNull()
        attributes.get(args) shouldBe "[]"
    }
}
