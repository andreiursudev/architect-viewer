package io.stackvibed.agent.http;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.stackvibed.agent.BodyCapture;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Captures Spring MVC {@code @RequestBody}/{@code HttpEntity} arguments after the message converters
 * read them, and response bodies before the message converters write them.
 */
public class SpringMvcBodyInstrumentationModule extends InstrumentationModule {

    private static final String PACKAGE = "org.springframework.web.servlet.mvc.method.annotation.";
    private static final String ARGUMENT_RESOLVER = PACKAGE + "AbstractMessageConverterMethodArgumentResolver";
    private static final String METHOD_PROCESSOR = PACKAGE + "AbstractMessageConverterMethodProcessor";

    public SpringMvcBodyInstrumentationModule() {
        super("stack-spring-webmvc-bodies", "stack");
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed(METHOD_PROCESSOR);
    }

    // No muzzle code generation in this build: helpers referenced by advice are listed explicitly.
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return List.of(
                "io.stackvibed.agent.BoundedValueSerializer",
                "io.stackvibed.agent.BodyCapture",
                "io.stackvibed.agent.BodyCapture$LimitedWriter");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new ReadInstrumentation(), new WriteInstrumentation());
    }

    /** {@code readWithMessageConverters(HttpInputMessage, MethodParameter, Type)}: every read path ends here. */
    public static class ReadInstrumentation implements TypeInstrumentation {

        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named(ARGUMENT_RESOLVER);
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("readWithMessageConverters")
                            .and(takesArguments(3))
                            .and(takesArgument(0, named("org.springframework.http.HttpInputMessage"))),
                    SpringMvcBodyInstrumentationModule.class.getName() + "$ReadAdvice");
        }
    }

    /** {@code writeWithMessageConverters(T, MethodParameter, ServletServerHttpRequest, ServletServerHttpResponse)}. */
    public static class WriteInstrumentation implements TypeInstrumentation {

        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named(METHOD_PROCESSOR);
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("writeWithMessageConverters")
                            .and(takesArguments(4))
                            .and(takesArgument(2, named("org.springframework.http.server.ServletServerHttpRequest"))),
                    SpringMvcBodyInstrumentationModule.class.getName() + "$WriteAdvice");
        }
    }

    public static class ReadAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.Return Object body, @Advice.FieldValue("messageConverters") List<?> converters) {
            BodyCapture.recordRequestBody(body, converters);
        }
    }

    public static class WriteAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object value, @Advice.FieldValue("messageConverters") List<?> converters) {
            BodyCapture.recordResponseBody(value, converters);
        }
    }
}
