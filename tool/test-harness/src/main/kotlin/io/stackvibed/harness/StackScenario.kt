package io.stackvibed.harness

import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tags a test as one named scenario. While the test runs, [StackContext] exposes the baggage and trace headers
 * that the OpenTelemetry agent in the service under test turns into recording scenario attribution.
 *
 * [id] must match `^[a-z0-9][a-z0-9-]*$`; anything else fails the test before its body runs.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(StackScenarioExtension::class)
annotation class StackScenario(val id: String)
