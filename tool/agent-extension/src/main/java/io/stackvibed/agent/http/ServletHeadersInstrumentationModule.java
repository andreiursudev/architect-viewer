package io.stackvibed.agent.http;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.extendsClass;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.stackvibed.agent.HeaderCapture;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Captures request headers on entry to and response headers on exit from
 * {@code HttpServlet#service(HttpServletRequest, HttpServletResponse)} and its overrides (Spring's
 * {@code FrameworkServlet} overrides it and handles PATCH without calling super). Nested calls just
 * overwrite the attributes; the outermost exit, which sees the final response headers, wins.
 */
public class ServletHeadersInstrumentationModule extends InstrumentationModule {

    private static final String HTTP_SERVLET = "jakarta.servlet.http.HttpServlet";

    public ServletHeadersInstrumentationModule() {
        super("stack-servlet-headers", "stack");
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed(HTTP_SERVLET);
    }

    // No muzzle code generation in this build: helpers referenced by advice are listed explicitly.
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return List.of("io.stackvibed.agent.BoundedValueSerializer", "io.stackvibed.agent.HeaderCapture");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new HttpServletInstrumentation());
    }

    public static class HttpServletInstrumentation implements TypeInstrumentation {

        @Override
        public ElementMatcher<ClassLoader> classLoaderOptimization() {
            return hasClassesNamed(HTTP_SERVLET);
        }

        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return extendsClass(named(HTTP_SERVLET));
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("service")
                            .and(takesArguments(2))
                            .and(takesArgument(0, named("jakarta.servlet.http.HttpServletRequest")))
                            .and(takesArgument(1, named("jakarta.servlet.http.HttpServletResponse"))),
                    ServletHeadersInstrumentationModule.class.getName() + "$ServiceAdvice");
        }
    }

    public static class ServiceAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(0) HttpServletRequest request) {
            HeaderCapture.recordRequestHeaders(request);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Argument(1) HttpServletResponse response) {
            HeaderCapture.recordResponseHeaders(response);
        }
    }
}
