import type { Context } from '@opentelemetry/api';
import type { ReadableSpan, Span, SpanProcessor } from '@opentelemetry/sdk-trace';
import { RUN, SCENARIO } from './attributes.js';
import { currentRun } from './stackContext.js';

/** Puts the page's scenario and run on every span as it starts, as the agent extension does from baggage on the JVM. */
export class StackSpanProcessor implements SpanProcessor {
  onStart(span: Span, _parentContext: Context): void {
    const run = currentRun();
    if (run) span.setAttributes({ [SCENARIO]: run.scenario, [RUN]: run.run });
  }

  onEnd(_span: ReadableSpan): void {}

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
