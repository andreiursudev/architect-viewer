package io.stackvibed.agent.method;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.stackvibed.agent.BodyCapture;
import io.stackvibed.agent.BoundedValueSerializer;
import io.stackvibed.agent.contract.StackAttributes;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * One INTERNAL span per call of an instrumented method, named {@code SimpleClass.method}, child of the
 * current context and current itself while the method runs. Arguments and the return value are
 * rendered with {@link BoundedValueSerializer} under {@link BodyCapture}'s bounds, each cut at
 * {@link BodyCapture#MAX_METHOD_VALUE_CHARS} characters; a value that cannot be rendered becomes a
 * placeholder. Nothing here may throw into, or change the behaviour of, the
 * application: the advice suppresses whatever escapes, and every rendering is guarded on its own.
 */
public final class MethodSpans {

    private static final Instrumenter<Call, Object> INSTRUMENTER = Instrumenter.<Call, Object>builder(
                    GlobalOpenTelemetry.get(), "stack-methods", Call::spanName)
            .addAttributesExtractor(new CallAttributes())
            // Record what the method threw, not a cause unwrapped from it.
            .setErrorCauseExtractor(error -> error)
            .buildInstrumenter(SpanKindExtractor.alwaysInternal());

    private MethodSpans() {}

    /** Starts the span and makes it current; {@code null} when no span is started. */
    public static Call start(Class<?> type, String method, Object[] args, String[] paramNames) {
        Context parent = Context.current();
        Call call = new Call(type, method, args, paramNames);
        if (!INSTRUMENTER.shouldStart(parent, call)) {
            return null;
        }
        call.context = INSTRUMENTER.start(parent, call);
        call.scope = call.context.makeCurrent();
        return call;
    }

    /** Leaves the span's scope and ends it with the returned value or the thrown exception. */
    public static void end(Call call, Object returned, boolean isVoid, Throwable thrown) {
        if (call == null) {
            return;
        }
        call.scope.close();
        call.isVoid = isVoid;
        INSTRUMENTER.end(call.context, call, returned, thrown);
    }

    /** Declared parameter names, or none when the class was compiled without them (the attribute is then left out). */
    public static String[] parameterNames(Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0 || !parameters[0].isNamePresent()) {
            return new String[0];
        }
        String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            names[i] = parameters[i].getName();
        }
        return names;
    }

    /** The value as JSON; a placeholder naming its type if rendering fails. */
    static String render(Object value) {
        try {
            return BoundedValueSerializer.toJson(
                    value,
                    BodyCapture.MAX_DEPTH,
                    BodyCapture.MAX_COLLECTION,
                    BodyCapture.MAX_STRING,
                    BodyCapture.MAX_METHOD_VALUE_CHARS);
        } catch (Throwable e) {
            StringBuilder out = new StringBuilder();
            BoundedValueSerializer.appendString(out, "<" + value.getClass().getSimpleName() + ">");
            return out.toString();
        }
    }

    /** The exception's message; {@code null} when it has none or an overridden getter fails. */
    private static String message(Throwable error) {
        try {
            return error.getMessage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A JSON array of strings. */
    static String jsonArray(String[] items) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            BoundedValueSerializer.appendString(out, items[i]);
        }
        return out.append(']').toString();
    }

    /** One method call: the instrumenter's request, plus the span context and scope between enter and exit. */
    public static final class Call {
        final Class<?> type;
        final String method;
        final Object[] args;
        final String[] paramNames;
        boolean isVoid;
        Context context;
        Scope scope;

        Call(Class<?> type, String method, Object[] args, String[] paramNames) {
            this.type = type;
            this.method = method;
            this.args = args;
            this.paramNames = paramNames;
        }

        String spanName() {
            String simple = type.getSimpleName();
            if (simple.isEmpty()) {
                simple = type.getName().substring(type.getName().lastIndexOf('.') + 1);
            }
            return simple + "." + method;
        }
    }

    static final class CallAttributes implements AttributesExtractor<Call, Object> {

        @Override
        public void onStart(AttributesBuilder attributes, Context parentContext, Call call) {
            attributes.put(StackAttributes.METHOD_CLASS, call.type.getName());
            attributes.put(StackAttributes.METHOD_NAME, call.method);
            attributes.put(StackAttributes.METHOD_THREAD, Thread.currentThread().getName());
            // Absent, not empty, when the class file has no names (compiled without -parameters) or
            // the method has no parameters.
            if (call.paramNames != null && call.paramNames.length > 0) {
                attributes.put(StackAttributes.METHOD_PARAM_NAMES, jsonArray(call.paramNames));
            }
            Object[] args = call.args == null ? new Object[0] : call.args;
            String[] rendered = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                rendered[i] = render(args[i]);
            }
            attributes.put(StackAttributes.METHOD_ARGS, jsonArray(rendered));
        }

        @Override
        public void onEnd(
                AttributesBuilder attributes, Context context, Call call, Object returned, Throwable error) {
            if (error != null) {
                attributes.put(StackAttributes.METHOD_EXCEPTION_TYPE, error.getClass().getName());
                String message = message(error);
                if (message != null) {
                    attributes.put(StackAttributes.METHOD_EXCEPTION_MESSAGE, message);
                }
            } else if (!call.isVoid) {
                attributes.put(StackAttributes.METHOD_RETURN, render(returned));
            }
        }
    }
}
