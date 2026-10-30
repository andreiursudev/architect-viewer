package io.stackvibed.agent.method;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The base packages whose Spring beans get one span per method call. Method capture is off (no type
 * matches at all) unless the {@code stack.methods.packages} system property or the {@code
 * STACK_METHODS_PACKAGES} environment variable lists packages, comma-separated.
 */
public final class MethodCaptureConfig {

    public static final String PROPERTY = "stack.methods.packages";
    public static final String ENVIRONMENT = "STACK_METHODS_PACKAGES";

    private MethodCaptureConfig() {}

    public static List<String> basePackages() {
        return basePackages(System.getProperty(PROPERTY), System.getenv(ENVIRONMENT));
    }

    /** The property when it is non-blank, otherwise the environment value; split on commas, trimmed, blanks dropped. */
    public static List<String> basePackages(String property, String env) {
        String raw = property != null && !property.isBlank() ? property : env;
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> packages = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                packages.add(trimmed);
            }
        }
        return Collections.unmodifiableList(packages);
    }
}
