package io.stackvibed.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.stackvibed.agent.contract.StackAttributes;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Records HTTP bodies on the server span as JSON, preferring the application's own Jackson mapper
 * (found among the Spring MVC message converters) so the capture matches what went over the wire.
 * Bodies are cut to {@link StackAttributes#MAX_BODY_CHARS} characters; the mapper writes into a
 * bounded writer, so a huge body is never rendered in full a second time.
 */
public final class BodyCapture {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_COLLECTION = 100;
    private static final int MAX_STRING = 4096;

    private BodyCapture() {}

    public static void recordRequestBody(Object body, List<?> converters) {
        record(StackAttributes.HTTP_REQUEST_BODY, StackAttributes.HTTP_REQUEST_BODY_TRUNCATED, body, converters);
    }

    public static void recordResponseBody(Object body, List<?> converters) {
        record(StackAttributes.HTTP_RESPONSE_BODY, StackAttributes.HTTP_RESPONSE_BODY_TRUNCATED, body, converters);
    }

    private static void record(String bodyKey, String truncatedKey, Object body, List<?> converters) {
        try {
            if (body == null) {
                return;
            }
            Span span = LocalRootSpan.current();
            if (!span.isRecording()) {
                return;
            }
            String json = toJson(body, converters);
            boolean truncated = json.length() > StackAttributes.MAX_BODY_CHARS;
            span.setAttribute(bodyKey, truncated ? cut(json) : json);
            span.setAttribute(truncatedKey, truncated);
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    /**
     * The body as JSON: character bodies as-is, otherwise via the application's Jackson mapper, falling
     * back to {@link BoundedValueSerializer}. May exceed {@link StackAttributes#MAX_BODY_CHARS} by at
     * most one character when produced by the mapper (to signal truncation); character bodies are
     * returned whole.
     */
    public static String toJson(Object value, List<?> converters) {
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        Object mapper = jsonMapper(converters);
        if (mapper != null) {
            LimitedWriter writer = new LimitedWriter(StackAttributes.MAX_BODY_CHARS + 1);
            try {
                mapper.getClass().getMethod("writeValue", Writer.class, Object.class).invoke(mapper, writer, value);
                return writer.toString();
            } catch (Throwable e) {
                if (writer.full) {
                    return writer.toString();
                }
            }
        }
        return BoundedValueSerializer.toJson(value, MAX_DEPTH, MAX_COLLECTION, MAX_STRING);
    }

    /** Jackson 3 ({@code getMapper()}) or Jackson 2 ({@code getObjectMapper()}) of the first JSON converter. */
    private static Object jsonMapper(List<?> converters) {
        if (converters == null) {
            return null;
        }
        for (Object converter : converters) {
            try {
                if (!supportsJson(converter)) {
                    continue;
                }
                Method getter = findMethod(converter.getClass(), "getMapper");
                if (getter == null) {
                    getter = findMethod(converter.getClass(), "getObjectMapper");
                }
                if (getter != null) {
                    Object mapper = getter.invoke(converter);
                    if (mapper != null) {
                        return mapper;
                    }
                }
            } catch (Throwable ignored) {
                // try the next converter
            }
        }
        return null;
    }

    private static boolean supportsJson(Object converter) throws ReflectiveOperationException {
        Object mediaTypes = converter.getClass().getMethod("getSupportedMediaTypes").invoke(converter);
        if (mediaTypes instanceof Iterable<?> types) {
            for (Object type : types) {
                if (String.valueOf(type).contains("json")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String cut(String json) {
        int end = StackAttributes.MAX_BODY_CHARS;
        if (Character.isHighSurrogate(json.charAt(end - 1))) {
            end--;
        }
        return json.substring(0, end);
    }

    /** Collects up to {@code limit} characters, then fails the write so the serializer stops early. */
    static final class LimitedWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final int limit;
        boolean full;

        LimitedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            append(new String(chars, offset, length));
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            append(text.substring(offset, offset + length));
        }

        private void append(String text) throws IOException {
            int room = limit - buffer.length();
            if (text.length() <= room) {
                buffer.append(text);
                return;
            }
            buffer.append(text, 0, room);
            full = true;
            throw new IOException("body capture limit reached");
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return buffer.toString();
        }
    }
}
