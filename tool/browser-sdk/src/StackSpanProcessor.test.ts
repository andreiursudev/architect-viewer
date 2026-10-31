import { InMemorySpanExporter, SimpleSpanProcessor, TracerProvider } from '@opentelemetry/sdk-trace';
import { afterEach, expect, it } from 'vitest';
import { RUN, SCENARIO } from './attributes.js';
import { StackSpanProcessor } from './StackSpanProcessor.js';

afterEach(() => {
  delete window.__STACK__;
});

it('processor tags spans when a run is known and leaves them alone otherwise', async () => {
  const exporter = new InMemorySpanExporter();
  const provider = new TracerProvider({
    spanProcessors: [new StackSpanProcessor(), new SimpleSpanProcessor({ exporter })],
  });
  const tracer = provider.getTracer('test');

  tracer.startSpan('before the run').end();
  window.__STACK__ = { scenario: 's-1', run: 'r-1' };
  tracer.startSpan('during the run', { attributes: { other: 'kept' } }).end();
  await provider.forceFlush();

  const [untagged, tagged] = exporter.getFinishedSpans();
  expect(untagged.attributes).toEqual({});
  expect(tagged.attributes).toEqual({ other: 'kept', [SCENARIO]: 's-1', [RUN]: 'r-1' });
});

it('processor waits until every started span has ended, but no longer than asked', async () => {
  const processor = new StackSpanProcessor();
  const tracer = new TracerProvider({ spanProcessors: [processor] }).getTracer('test');
  await processor.whenNoSpanOpen(1000); // nothing open: resolves at once

  const open = tracer.startSpan('in flight');
  let idle = false;
  const waiting = processor.whenNoSpanOpen(1000).then(() => (idle = true));
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(idle).toBe(false);
  open.end();
  await waiting;
  expect(idle).toBe(true);

  tracer.startSpan('never ends');
  const started = Date.now();
  await processor.whenNoSpanOpen(50);
  expect(Date.now() - started).toBeGreaterThanOrEqual(45);
});

it('processor releases every waiter when the last open span ends', async () => {
  const processor = new StackSpanProcessor();
  const tracer = new TracerProvider({ spanProcessors: [processor] }).getTracer('test');
  const open = tracer.startSpan('in flight');
  const released: string[] = [];
  const first = processor.whenNoSpanOpen(1000).then(() => released.push('first'));
  const second = processor.whenNoSpanOpen(1000).then(() => released.push('second'));

  open.end();
  await new Promise((resolve) => setTimeout(resolve, 20));

  // Both at once, not the second only at its 1 s cap.
  expect(released).toEqual(['first', 'second']);
  await Promise.all([first, second]);
});
