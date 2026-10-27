package io.stackvibed.harness

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.support.AnnotationSupport
import java.util.UUID

class StackScenarioExtension : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        val annotation = AnnotationSupport.findAnnotation(context.element, StackScenario::class.java).orElse(null) ?: return
        require(SCENARIO_ID.matches(annotation.id)) {
            "@StackScenario id '${annotation.id}' must match ${SCENARIO_ID.pattern}"
        }
        StackContext.begin(ScenarioRun(annotation.id, UUID.randomUUID().toString()))
    }

    override fun afterEach(context: ExtensionContext) = StackContext.end()

    private companion object {
        val SCENARIO_ID = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}
