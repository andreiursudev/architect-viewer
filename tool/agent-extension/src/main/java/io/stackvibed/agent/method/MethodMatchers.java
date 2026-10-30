package io.stackvibed.agent.method;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Which classes and methods get one span per call. */
public final class MethodMatchers {

    /** Spring stereotypes, matched by name and only when declared directly on the class. */
    private static final String[] STEREOTYPES = {
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController",
    };

    private MethodMatchers() {}

    /**
     * Concrete, non-synthetic classes in one of {@code packages} (or a sub-package) that carry a
     * stereotype directly; never generated proxies (names containing {@code $$}). Matches nothing when
     * {@code packages} is empty.
     */
    public static ElementMatcher<TypeDescription> types(List<String> packages) {
        if (packages.isEmpty()) {
            return none();
        }
        ElementMatcher.Junction<TypeDescription> inPackages = none();
        for (String basePackage : packages) {
            inPackages = inPackages.or(nameStartsWith(basePackage + "."));
        }
        return not(isInterface())
                .and(not(isSynthetic()))
                .and(inPackages)
                .and(not(nameContains("$$")))
                .and(isAnnotatedWith(namedOneOf(STEREOTYPES)));
    }

    /**
     * Public instance methods that are neither synthetic nor bridges, minus accessors, {@code Object}
     * methods and Kotlin-generated names (see {@link #isExcludedName}).
     */
    public static ElementMatcher<MethodDescription> methods() {
        return isMethod()
                .and(isPublic())
                .and(not(isStatic()))
                .and(not(isSynthetic()))
                .and(not(isBridge()))
                .and(new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
                    @Override
                    public boolean matches(MethodDescription method) {
                        return !isExcludedName(method.getName(), method.getParameters().size());
                    }
                });
    }

    /**
     * {@code get*}/{@code is*} without parameters, {@code set*} with one (the prefix followed by an
     * upper-case letter, so {@code issue()} or {@code settle()} stay), {@code toString}, {@code equals},
     * {@code hashCode}, Kotlin's {@code copy} and {@code componentN}, and {@code *$default}.
     */
    public static boolean isExcludedName(String name, int parameterCount) {
        switch (name) {
            case "toString":
            case "equals":
            case "hashCode":
            case "copy":
                return true;
            default:
                break;
        }
        if (name.endsWith("$default") || isComponentN(name)) {
            return true;
        }
        if (parameterCount == 0 && (isAccessor(name, "get") || isAccessor(name, "is"))) {
            return true;
        }
        return parameterCount == 1 && isAccessor(name, "set");
    }

    private static boolean isAccessor(String name, String prefix) {
        return name.length() > prefix.length()
                && name.startsWith(prefix)
                && Character.isUpperCase(name.charAt(prefix.length()));
    }

    /** Kotlin data class {@code component1()}, {@code component2()}, … */
    private static boolean isComponentN(String name) {
        String prefix = "component";
        if (name.length() <= prefix.length() || !name.startsWith(prefix) || name.charAt(prefix.length()) == '0') {
            return false;
        }
        for (int i = prefix.length(); i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
