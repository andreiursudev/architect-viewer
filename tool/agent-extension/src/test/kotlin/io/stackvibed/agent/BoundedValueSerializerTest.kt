package io.stackvibed.agent

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
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
    fun streamsAndIteratorsArePlaceholdersAndNeverConsumed() {
        val consumed = java.util.concurrent.atomic.AtomicBoolean()
        val stream = java.util.stream.Stream.of("a", "b").peek { consumed.set(true) }
        BoundedValueSerializer.placeholder(stream) shouldBe "<stream>"
        BoundedValueSerializer.placeholder(java.util.stream.IntStream.range(0, 3)) shouldBe "<stream>"
        BoundedValueSerializer.placeholder(UnreadableIterator()) shouldBe "<stream>"
        json(linkedMapOf("items" to stream, "more" to UnreadableIterator())) shouldBe """{"items":"<stream>","more":"<stream>"}"""
        consumed.get() shouldBe false
        stream.toList() shouldBe listOf("a", "b") // still usable by the application
    }

    @Test
    fun platformTypesUseToString() {
        json(java.util.UUID(0, 1)) shouldBe "\"00000000-0000-0000-0000-000000000001\""
    }

    @Test
    fun `a total budget cuts a large shared object graph and ends with the marker`() {
        // Cycle detection is per path, so a graph sharing nodes renders each node once per path (a
        // bean factory renders to hundreds of thousands of characters this way).
        val inner = List(50) { "x".repeat(100) }
        val shared = List(50) { inner }
        val unbounded = json(shared)
        (unbounded.length > 100_000) shouldBe true

        val cut = BoundedValueSerializer.toJson(shared, 8, 100, 4096, 1000)
        cut.length shouldBeLessThanOrEqual 1000 + BoundedValueSerializer.TRUNCATED.length
        cut shouldEndWith BoundedValueSerializer.TRUNCATED
        cut shouldStartWith unbounded.substring(0, 1000)
    }

    @Test
    fun `a value within the budget is rendered whole`() {
        BoundedValueSerializer.toJson(listOf("a", 1), 8, 100, 4096, 9) shouldBe """["a",1]"""
        BoundedValueSerializer.toJson("abcdefgh", 8, 100, 4096, 4) shouldBe "\"abc" + BoundedValueSerializer.TRUNCATED
    }

    @Test
    fun `an uninitialized lazy collection is a placeholder and never read`() {
        BoundedValueSerializer.placeholder(LazyBag(initialized = false)) shouldBe "<lazy>"
        json(Order("o-1", LazyBag(initialized = false))) shouldBe """{"id":"o-1","items":"<lazy>"}"""
    }

    @Test
    fun `a lazy collection whose state cannot be read is a placeholder`() {
        BoundedValueSerializer.placeholder(LazyBag(initialized = null)) shouldBe "<lazy>"
    }

    @Test
    fun `an initialized lazy collection is rendered normally`() {
        BoundedValueSerializer.placeholder(LazyBag(initialized = true, listOf("a", "b"))) shouldBe null
        json(Order("o-1", LazyBag(initialized = true, listOf("a", "b")))) shouldBe """{"id":"o-1","items":["a","b"]}"""
    }

    @Test
    fun `an entity proxy is a placeholder and never read`() {
        BoundedValueSerializer.placeholder(OrderProxy()) shouldBe "<lazy>"
        json(listOf(OrderProxy())) shouldBe """["<lazy>"]"""
    }

    private class Node(val name: String, val next: Node?)

    private class Order(val id: String, val items: List<String>)

    /** A Hibernate-style bag (see test stub); {@code null} makes the state check itself fail. */
    private class LazyBag(private val initialized: Boolean?, private val items: List<String> = emptyList()) :
        java.util.AbstractList<String>(), org.hibernate.collection.spi.PersistentCollection {
        override fun wasInitialized(): Boolean = initialized ?: throw IllegalStateException("no session")
        override val size: Int get() = check().size
        override fun get(index: Int): String = check()[index]
        override fun iterator(): MutableIterator<String> = check().toMutableList().iterator()
        private fun check(): List<String> =
            if (initialized == true) items else throw AssertionError("capture must not load a lazy collection")
    }

    /** A Hibernate-style entity proxy: reading its state would load the entity. */
    private class OrderProxy : org.hibernate.proxy.HibernateProxy {
        @Suppress("unused")
        val id: String get() = throw AssertionError("capture must not initialize a proxy")
        @Suppress("unused")
        private val interceptor: Any = object { val session: Any = "must not be rendered" }
    }

    private class UnreadableIterator : Iterator<String> {
        override fun hasNext(): Boolean = throw AssertionError("capture must not advance the iterator")
        override fun next(): String = throw AssertionError("capture must not advance the iterator")
    }

    private class UnreadableStream : java.io.InputStream() {
        override fun read(): Int = throw AssertionError("capture must not read the stream")
    }

    /** Implements Spring's InputStreamSource indirectly, through a sub-interface (see test stub). */
    private class FakeResource : org.springframework.core.io.Resource {
        override fun getInputStream(): java.io.InputStream = throw AssertionError("capture must not open the resource")
    }
}
