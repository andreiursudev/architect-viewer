package io.stackvibed.agent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BoundedValueSerializerTest {

    private fun json(value: Any?, maxDepth: Int = 8, maxCollection: Int = 100, maxString: Int = 4096) =
        BoundedValueSerializer.toJson(value, maxDepth, maxCollection, maxString)

    @Test
    fun scalarsAndEscapedStrings() {
        json(null) shouldBe "null"
        json(42) shouldBe "42"
        json(true) shouldBe "true"
        json(Double.NaN) shouldBe "\"NaN\""
        json(Thread.State.NEW) shouldBe "\"NEW\""
        json("a\"b\\c\nd\u0001") shouldBe "\"a\\\"b\\\\c\\nd\\u0001\""
    }

    @Test
    fun mapsCollectionsAndArraysAreBounded() {
        json(linkedMapOf("a" to 1, "b" to listOf("x", "y"))) shouldBe """{"a":1,"b":["x","y"]}"""
        json(linkedMapOf("a" to 1, "b" to 2), maxCollection = 1) shouldBe """{"a":1}"""
        json(intArrayOf(1, 2, 3, 4), maxCollection = 2) shouldBe "[1,2]"
        json((1..10).toList(), maxCollection = 3) shouldBe "[1,2,3]"
        json("abcdef", maxString = 3) shouldBe "\"abc\""
    }

    @Test
    fun objectsAreRenderedFieldByFieldWithinDepth() {
        json(Node("root", Node("child", null))) shouldBe """{"name":"root","next":{"name":"child","next":null}}"""
        json(Node("a", Node("b", Node("c", null))), maxDepth = 2) shouldBe
            """{"name":"a","next":{"name":"b","next":"<Node>"}}"""
    }

    @Test
    fun cyclesDegradeToPlaceholder() {
        val list = mutableListOf<Any>("x")
        list.add(list)
        json(list) shouldBe """["x","<cycle>"]"""
    }

    @Test
    fun opaqueValuesArePlaceholdersAndNeverRead() {
        BoundedValueSerializer.placeholder(byteArrayOf(1, 2, 3)) shouldBe "<binary 3 bytes>"
        BoundedValueSerializer.placeholder(UnreadableStream()) shouldBe "<stream>"
        BoundedValueSerializer.placeholder(java.io.StringReader("x")) shouldBe "<reader>"
        BoundedValueSerializer.placeholder(FakeResource()) shouldBe "<resource>"
        BoundedValueSerializer.placeholder("text") shouldBe null
        json(listOf(byteArrayOf(1, 2), UnreadableStream())) shouldBe """["<binary 2 bytes>","<stream>"]"""
    }

    @Test
    fun platformTypesUseToString() {
        json(java.util.UUID(0, 1)) shouldBe "\"00000000-0000-0000-0000-000000000001\""
    }

    private class Node(val name: String, val next: Node?)

    private class UnreadableStream : java.io.InputStream() {
        override fun read(): Int = throw AssertionError("capture must not read the stream")
    }

    /** Implements Spring's InputStreamSource indirectly, through a sub-interface (see test stub). */
    private class FakeResource : org.springframework.core.io.Resource {
        override fun getInputStream(): java.io.InputStream = throw AssertionError("capture must not open the resource")
    }
}
