import type { Context, TextMapGetter, TextMapPropagator, TextMapSetter } from '@opentelemetry/api';
import { RUN, SCENARIO } from './attributes.js';
import { currentRun } from './stackContext.js';

const BAGGAGE = 'baggage';

/**
 * Sends the page's scenario and run with every traced fetch as W3C baggage, which the agent extension copies onto the
 * backend's spans. Pages outside a recorded run send none.
 */
export class StackBaggagePropagator implements TextMapPropagator {
  inject(_context: Context, carrier: unknown, setter: TextMapSetter): void {
    const run = currentRun();
    if (!run) return;
    setter.set(carrier, BAGGAGE, `${SCENARIO}=${encodeURIComponent(run.scenario)},${RUN}=${encodeURIComponent(run.run)}`);
  }

  extract(context: Context, _carrier: unknown, _getter: TextMapGetter): Context {
    return context;
  }

  fields(): string[] {
    return [BAGGAGE];
  }
}
