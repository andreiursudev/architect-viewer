import type { Context } from '@opentelemetry/api';
import type { ReadableSpan, Span, SpanProcessor } from '@opentelemetry/sdk-trace';
import { RUN, SCENARIO } from './attributes.js';
import { currentRun } from './stackContext.js';

/**
 * Puts the page's scenario and run on every span as it starts, as the agent extension does from baggage on the JVM.
 * It also keeps the spans that have started and not yet ended, so a flush can wait for them (see whenNoSpanOpen).
 */
export class StackSpanProcessor implements SpanProcessor {
  private readonly open = new Set<ReadableSpan>();
  private idleWaiters: (() => void)[] = [];

  onStart(span: Span, _parentContext: Context): void {
    const run = currentRun();
    if (run) span.setAttributes({ [SCENARIO]: run.scenario, [RUN]: run.run });
    this.open.add(span);
  }

  onEnd(span: ReadableSpan): void {
    this.open.delete(span);
    if (this.open.size > 0) return;
    // A copy: each waiter removes itself from the list as it is released.
    const waiters = [...this.idleWaiters];
    this.idleWaiters = [];
    for (const resolve of waiters) resolve();
  }

  /** Resolves once no span is open, or after maxWaitMillis, whichever comes first. Never rejects. */
  whenNoSpanOpen(maxWaitMillis: number): Promise<void> {
    if (this.open.size === 0) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(done, maxWaitMillis);
      const waiters = this.idleWaiters;
      function done() {
        clearTimeout(timer);
        const index = waiters.indexOf(done);
        if (index >= 0) waiters.splice(index, 1);
        resolve();
      }
      waiters.push(done);
    });
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
