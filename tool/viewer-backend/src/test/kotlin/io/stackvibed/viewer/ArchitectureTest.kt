package io.stackvibed.viewer

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val mainClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("io.stackvibed.viewer")

    /** Nothing outside the web package may depend on it: web is the top of the module graph (REST sits on the modules, never below them). */
    private fun noOneDependsOn(webPackage: String): ArchRule =
        noClasses().that().resideOutsideOfPackage(webPackage)
            .should().dependOnClassesThat().resideInAPackage(webPackage)
            .allowEmptyShould(true)

    @Test
    fun `no module depends on web`() {
        mainClasses.isEmpty() shouldBe false
        noOneDependsOn("io.stackvibed.viewer.web..").check(mainClasses)
    }

    @Test
    fun `the web rule catches an offending dependency`() {
        val fixture = ClassFileImporter().importPackages("io.stackvibed.archfixture")

        fixture.isEmpty() shouldBe false
        shouldThrow<AssertionError> { noOneDependsOn("io.stackvibed.archfixture.web..").check(fixture) }
    }
}
