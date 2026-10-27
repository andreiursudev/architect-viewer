package io.stackvibed.agent

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.jar.JarFile

/** The shadowed extension jar is self-contained plain Java: our classes, the contract, the SPI files, no Kotlin. */
class ExtensionJarTest {
    private val entries = JarFile(System.getProperty("stack.extensionJar")).use { jar ->
        jar.entries().asSequence().map { it.name }.toList()
    }

    @Test
    fun containsContractAndServiceRegistrations() {
        entries shouldContainAll listOf(
            "io/stackvibed/agent/contract/StackAttributes.class",
            "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider",
            "META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
        )
    }

    @Test
    fun bundlesNoKotlin() {
        entries.map { it.substringBefore('/') } shouldNotContain "kotlin"
    }
}
