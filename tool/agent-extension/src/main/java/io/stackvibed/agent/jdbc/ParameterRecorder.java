package io.stackvibed.agent.jdbc;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.SpanKey;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.stackvibed.agent.BoundedValueSerializer;
import io.stackvibed.agent.contract.StackAttributes;
import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the parameters bound to each {@link PreparedStatement} (in a {@link VirtualField}, so
 * they live and die with the statement) and records them, plus update row counts, on the JDBC
 * client span when the statement executes.
 */
public final class ParameterRecorder {

    // Field-backed on statement classes the agent transforms, a weak-keyed cache otherwise: no leak.
    private static final VirtualField<PreparedStatement, Map<Integer, String>> PARAMETERS =
            VirtualField.find(PreparedStatement.class, Map.class);

    private ParameterRecorder() {}

    /** After a successful {@code set*(int index, value, ..)}. */
    public static void record(PreparedStatement statement, String method, int index, Object value) {
        try {
            Map<Integer, String> parameters = PARAMETERS.get(statement);
            if (parameters == null) {
                parameters = new HashMap<>();
                PARAMETERS.set(statement, parameters);
            }
            parameters.put(index, render(method, value));
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    /** After {@code clearParameters()}. */
    public static void clear(PreparedStatement statement) {
        try {
            PARAMETERS.set(statement, null);
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    /**
     * On exit from {@code execute*()}. Only acts when the current span is the database client span
     * that OTel's JDBC instrumentation opened for this call: a pool proxy's execute exits after that
     * span ended, and its attributes must not land on the enclosing (e.g. server) span.
     */
    public static void onExecute(PreparedStatement statement, String method, Object result, Throwable thrown) {
        try {
            Context context = Context.current();
            Span span = Span.fromContext(context);
            if (!span.isRecording() || !isClientSpan(context, span)) {
                return;
            }
            Map<Integer, String> parameters = PARAMETERS.get(statement);
            if (parameters != null && !parameters.isEmpty()) {
                span.setAttribute(StackAttributes.DB_PARAMETERS, toJson(parameters));
            }
            if (thrown == null
                    && result instanceof Number
                    && ("executeUpdate".equals(method) || "executeLargeUpdate".equals(method))) {
                span.setAttribute(StackAttributes.DB_ROW_COUNT, ((Number) result).longValue());
            }
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    // The instrumenter stores a database span under DB_CLIENT (default "semconv" suppression strategy)
    // or KIND_CLIENT ("span-kind" strategy).
    private static boolean isClientSpan(Context context, Span span) {
        return span == SpanKey.DB_CLIENT.fromContextOrNull(context)
                || span == SpanKey.KIND_CLIENT.fromContextOrNull(context);
    }

    /**
     * The parameter as recorded: {@code null} for SQL NULL, {@code "<n bytes>"} for binary data,
     * placeholders for streams and readers (never read), {@link String#valueOf} otherwise.
     */
    public static String render(String method, Object value) {
        if (value == null || "setNull".equals(method)) {
            return null;
        }
        if (value instanceof byte[]) {
            return "<" + ((byte[]) value).length + " bytes>";
        }
        if (value instanceof InputStream) {
            return "<stream>";
        }
        if (value instanceof Reader) {
            return "<reader>";
        }
        return String.valueOf(value);
    }

    /** A JSON array of the parameters by 1-based index; unbound indexes are {@code null}. */
    public static String toJson(Map<Integer, String> parameters) {
        int max = 0;
        for (Integer index : parameters.keySet()) {
            max = Math.max(max, index);
        }
        StringBuilder out = new StringBuilder("[");
        for (int index = 1; index <= max; index++) {
            if (index > 1) {
                out.append(',');
            }
            String value = parameters.get(index);
            if (value == null) {
                out.append("null");
            } else {
                BoundedValueSerializer.appendString(out, value);
            }
        }
        return out.append(']').toString();
    }
}
