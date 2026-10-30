package io.stackvibed.agent.method;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.lang.reflect.Method;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * One span per call of the public methods of Spring stereotype beans in the packages that {@link
 * MethodCaptureConfig} lists (see {@link MethodMatchers} for what is left out). Off, and matching no
 * type at all, when no package is configured.
 */
public class MethodInstrumentationModule extends InstrumentationModule {

    public MethodInstrumentationModule() {
        super("stack-methods", "stack");
    }

    /** Every stereotype is meta-annotated with (or is) {@code @Component}: no Spring, nothing to match. */
    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed("org.springframework.stereotype.Component");
    }

    // No muzzle code generation in this build: helpers referenced by advice are listed explicitly.
    // MethodCaptureConfig and MethodMatchers only run in the agent, when the type matcher is built.
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return List.of(
                "io.stackvibed.agent.BoundedValueSerializer",
                "io.stackvibed.agent.BodyCapture",
                "io.stackvibed.agent.BodyCapture$LimitedWriter",
                "io.stackvibed.agent.method.MethodSpans",
                "io.stackvibed.agent.method.MethodSpans$Call",
                "io.stackvibed.agent.method.MethodSpans$CallAttributes");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new BeanInstrumentation());
    }

    public static class BeanInstrumentation implements TypeInstrumentation {

        /** Reads the configured packages once, when the agent installs this instrumentation. */
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return MethodMatchers.types(MethodCaptureConfig.basePackages());
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    MethodMatchers.methods(), MethodInstrumentationModule.class.getName() + "$MethodAdvice");
        }
    }

    public static class MethodAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static MethodSpans.Call onEnter(
                @Advice.Origin Method method, @Advice.AllArguments Object[] args) {
            return MethodSpans.start(
                    method.getDeclaringClass(), method.getName(), args, MethodSpans.parameterNames(method));
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.Enter MethodSpans.Call call,
                @Advice.Origin("#r") String returnType,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                @Advice.Thrown Throwable thrown) {
            MethodSpans.end(call, returned, "void".equals(returnType), thrown);
        }
    }
}
