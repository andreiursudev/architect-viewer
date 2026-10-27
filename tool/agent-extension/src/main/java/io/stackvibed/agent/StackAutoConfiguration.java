package io.stackvibed.agent;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent defaults for local recording (all overridable through system properties or environment
 * variables) and registration of the baggage span processor.
 */
public final class StackAutoConfiguration implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addPropertiesSupplier(StackAutoConfiguration::defaults);
        customizer.addTracerProviderCustomizer(
                (builder, config) -> builder.addSpanProcessor(new BaggageToAttributesSpanProcessor()));
    }

    static Map<String, String> defaults() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("otel.traces.exporter", "otlp");
        defaults.put("otel.exporter.otlp.protocol", "http/protobuf");
        defaults.put("otel.exporter.otlp.endpoint", "http://localhost:4318");
        defaults.put("otel.metrics.exporter", "none");
        defaults.put("otel.logs.exporter", "none");
        defaults.put("otel.traces.sampler", "always_on");
        defaults.put("otel.bsp.schedule.delay", "200");
        // Recorded SQL keeps its literals. This is the key opentelemetry-javaagent 2.33 reads; the older
        // otel.instrumentation.common.db-statement-sanitizer.enabled logs a deprecation warning.
        defaults.put("otel.instrumentation.common.db.query-sanitization.enabled", "false");
        return defaults;
    }
}
