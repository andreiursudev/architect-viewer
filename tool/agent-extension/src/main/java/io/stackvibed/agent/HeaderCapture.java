package io.stackvibed.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.stackvibed.agent.contract.StackAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records all request and response headers on the server span as a JSON object of lower-cased
 * header name (HTTP names are case-insensitive) to the list of values.
 */
public final class HeaderCapture {

    private HeaderCapture() {}

    public static void recordRequestHeaders(HttpServletRequest request) {
        try {
            Span span = LocalRootSpan.current();
            if (!span.isRecording()) {
                return;
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            Enumeration<String> names = request.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> Collections.list(request.getHeaders(name)));
            }
            span.setAttribute(StackAttributes.HTTP_REQUEST_HEADERS, toJson(headers));
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    public static void recordResponseHeaders(HttpServletResponse response) {
        try {
            Span span = LocalRootSpan.current();
            if (!span.isRecording()) {
                return;
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String name : response.getHeaderNames()) {
                headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> copy(response.getHeaders(name)));
            }
            // Containers may only materialise Content-Type into the header list when the response commits.
            String contentType = response.getContentType();
            if (contentType != null) {
                headers.putIfAbsent("content-type", List.of(contentType));
            }
            span.setAttribute(StackAttributes.HTTP_RESPONSE_HEADERS, toJson(headers));
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    private static List<String> copy(Collection<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    static String toJson(Map<String, List<String>> headers) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            BoundedValueSerializer.appendString(out, header.getKey());
            out.append(":[");
            List<String> values = header.getValue();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                BoundedValueSerializer.appendString(out, values.get(i));
            }
            out.append(']');
        }
        return out.append('}').toString();
    }
}
