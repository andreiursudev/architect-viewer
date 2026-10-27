package io.stackvibed.model

import io.kotest.matchers.shouldBe
import io.stackvibed.model.schema.SchemaGenerator
import org.junit.jupiter.api.Test

class SchemaSnapshotTest {
    @Test
    fun `schema matches committed snapshot`() {
        SchemaGenerator.generate() shouldBe readResource("schema-snapshot/model.schema.json")
    }

    private fun readResource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing resource $name" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }
}
