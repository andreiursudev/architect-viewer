package io.stackvibed.agent.kafka;

import io.opentelemetry.api.trace.Span;
import io.stackvibed.agent.BodyCapture;
import io.stackvibed.agent.BoundedValueSerializer;
import io.stackvibed.agent.contract.StackAttributes;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.utils.Bytes;

/**
 * Records a Kafka record's value and headers on the current span: the OTel agent's PRODUCER span inside
 * {@code KafkaProducer.send}, its CONSUMER {@code process} span around the Spring Kafka listener. See {@link
 * StackAttributes} for the rendering.
 */
public final class KafkaCapture {

    private KafkaCapture() {}

    public static void recordProduce(ProducerRecord<?, ?> record) {
        try {
            if (record != null) {
                record(record.value(), record.headers());
            }
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    public static void recordConsume(ConsumerRecord<?, ?> record) {
        try {
            if (record != null) {
                record(record.value(), record.headers());
            }
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    private static void record(Object value, Headers headers) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }
        String rendered = renderValue(value);
        if (rendered != null) {
            boolean truncated = rendered.length() > StackAttributes.MAX_BODY_CHARS;
            span.setAttribute(StackAttributes.KAFKA_VALUE, truncated ? cut(rendered) : rendered);
            span.setAttribute(StackAttributes.KAFKA_VALUE_TRUNCATED, truncated);
        }
        if (headers != null) {
            span.setAttribute(StackAttributes.KAFKA_HEADERS, headersJson(headers));
        }
    }

    /**
     * A {@code String} as is, bytes as {@code <N bytes>} (never read), anything else as JSON through {@link
     * BoundedValueSerializer}; {@code null} for a {@code null} value (a tombstone). May exceed {@link
     * StackAttributes#MAX_BODY_CHARS}; the caller cuts it.
     */
    static String renderValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof byte[] bytes) {
            return bytes(bytes.length);
        }
        if (value instanceof ByteBuffer buffer) {
            return bytes(buffer.remaining());
        }
        if (value instanceof Bytes bytes) {
            return bytes(bytes.get() == null ? 0 : bytes.get().length);
        }
        return BoundedValueSerializer.toJson(
                value,
                BodyCapture.MAX_DEPTH,
                BodyCapture.MAX_COLLECTION,
                BodyCapture.MAX_STRING,
                StackAttributes.MAX_BODY_CHARS);
    }

    /** Header name (as sent) to the list of its values, in first-seen order; values that are not UTF-8 as {@code <N bytes>}. */
    static String headersJson(Headers headers) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (Header header : headers) {
            byName.computeIfAbsent(header.key(), key -> new ArrayList<>()).add(decode(header.value()));
        }
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, List<String>> entry : byName.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            BoundedValueSerializer.appendString(out, entry.getKey());
            out.append(":[");
            List<String> values = entry.getValue();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                String value = values.get(i);
                if (value == null) {
                    out.append("null");
                } else {
                    BoundedValueSerializer.appendString(out, value);
                }
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    private static String decode(byte[] value) {
        if (value == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            return bytes(value.length);
        }
    }

    private static String bytes(int length) {
        return "<" + length + " bytes>";
    }

    private static String cut(String value) {
        int end = StackAttributes.MAX_BODY_CHARS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
