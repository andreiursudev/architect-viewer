package io.stackvibed.agent;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.stackvibed.agent.contract.StackAttributes;

/** Copies the scenario and run ids from the propagated baggage onto every span as it starts. */
public final class BaggageToAttributesSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        try {
            Baggage baggage = Baggage.fromContext(parentContext);
            copy(baggage, StackAttributes.SCENARIO, span);
            copy(baggage, StackAttributes.RUN, span);
        } catch (Throwable ignored) {
            // capture must never break the application
        }
    }

    private static void copy(Baggage baggage, String key, ReadWriteSpan span) {
        String value = baggage.getEntryValue(key);
        if (value != null && !value.isEmpty()) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {}

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
