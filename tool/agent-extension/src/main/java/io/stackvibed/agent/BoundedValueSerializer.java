package io.stackvibed.agent;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Dependency-free JSON rendering of arbitrary values with hard bounds on depth, collection size and
 * string length. Used when the application offers no JSON mapper, so it favours safety over
 * fidelity: cycles, deep nesting and inaccessible objects degrade to short placeholder strings.
 */
public final class BoundedValueSerializer {

    private static final String INPUT_STREAM_SOURCE = "org.springframework.core.io.InputStreamSource";

    private final int maxDepth;
    private final int maxCollection;
    private final int maxString;
    private final StringBuilder out = new StringBuilder();
    private final IdentityHashMap<Object, Boolean> inProgress = new IdentityHashMap<>();

    private BoundedValueSerializer(int maxDepth, int maxCollection, int maxString) {
        this.maxDepth = maxDepth;
        this.maxCollection = maxCollection;
        this.maxString = maxString;
    }

    public static String toJson(Object value, int maxDepth, int maxCollection, int maxString) {
        BoundedValueSerializer serializer = new BoundedValueSerializer(maxDepth, maxCollection, maxString);
        serializer.write(value, 0);
        return serializer.out.toString();
    }

    /**
     * A placeholder for values whose content must not be read to render them (doing so could load a
     * whole file into memory or consume/leak a stream), or {@code null} for ordinary values. Spring's
     * {@code InputStreamSource} (e.g. {@code Resource}) is matched by name so no Spring type is loaded.
     */
    public static String placeholder(Object value) {
        if (value instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        }
        if (value instanceof InputStream) {
            return "<stream>";
        }
        if (value instanceof Reader) {
            return "<reader>";
        }
        if (value != null && implementsByName(value.getClass(), INPUT_STREAM_SOURCE)) {
            return "<resource>";
        }
        return null;
    }

    private static boolean implementsByName(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(name)) {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (implementsByName(implemented, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Appends {@code value} as a JSON string literal. */
    public static void appendString(StringBuilder out, CharSequence value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void write(Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence text) {
            string(text);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number number) {
            number(number);
        } else if (value instanceof Character || value instanceof Enum<?>) {
            string(value instanceof Enum<?> e ? e.name() : value.toString());
        } else {
            reference(value, depth);
        }
    }

    private void reference(Object value, int depth) {
        String opaque = placeholder(value);
        if (opaque != null) {
            string(opaque);
        } else if (depth >= maxDepth) {
            depthPlaceholder(value);
        } else if (inProgress.put(value, Boolean.TRUE) != null) {
            string("<cycle>");
        } else {
            try {
                composite(value, depth);
            } finally {
                inProgress.remove(value);
            }
        }
    }

    private void composite(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            object(map, depth);
        } else if (value instanceof Iterable<?> iterable) {
            array(iterable.iterator(), depth);
        } else if (value.getClass().isArray()) {
            array(value, depth);
        } else if (isPlatformType(value.getClass())) {
            string(String.valueOf(value));
        } else {
            fields(value, depth);
        }
    }

    private void object(Map<?, ?> map, int depth) {
        out.append('{');
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count == maxCollection) {
                break;
            }
            if (count++ > 0) {
                out.append(',');
            }
            string(String.valueOf(entry.getKey()));
            out.append(':');
            write(entry.getValue(), depth + 1);
        }
        out.append('}');
    }

    private void array(Iterator<?> items, int depth) {
        out.append('[');
        for (int count = 0; count < maxCollection && items.hasNext(); count++) {
            if (count > 0) {
                out.append(',');
            }
            write(items.next(), depth + 1);
        }
        out.append(']');
    }

    private void array(Object array, int depth) {
        out.append('[');
        int length = Math.min(Array.getLength(array), maxCollection);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(',');
            }
            write(Array.get(array, i), depth + 1);
        }
        out.append(']');
    }

    private void fields(Object value, int depth) {
        out.append('{');
        int count = 0;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (count == maxCollection) {
                    break;
                }
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
                        || !field.trySetAccessible()) {
                    continue;
                }
                Object fieldValue;
                try {
                    fieldValue = field.get(value);
                } catch (IllegalAccessException e) {
                    continue;
                }
                if (count++ > 0) {
                    out.append(',');
                }
                string(field.getName());
                out.append(':');
                write(fieldValue, depth + 1);
            }
        }
        out.append('}');
    }

    private void number(Number number) {
        if ((number instanceof Double d && (d.isNaN() || d.isInfinite()))
                || (number instanceof Float f && (f.isNaN() || f.isInfinite()))) {
            string(number.toString());
        } else {
            out.append(number);
        }
    }

    private void depthPlaceholder(Object value) {
        string("<" + value.getClass().getSimpleName() + ">");
    }

    private void string(CharSequence text) {
        appendString(out, text.length() > maxString ? text.subSequence(0, maxString) : text);
    }

    private static boolean isPlatformType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }
}
