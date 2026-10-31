import { SpanKind } from '@opentelemetry/api';
import { InMemorySpanExporter, type ReadableSpan } from '@opentelemetry/sdk-trace';
import { startStackRecording } from '../../src/index.js';
import type { PlainSpan } from './plainSpan.js';

// ?reporter=<url> sets reporterUrl; ?exporter=otlp leaves the exporter out, so the real OTLP exporter posts to the reporter.
const params = new URLSearchParams(location.search);
const inMemory = new InMemorySpanExporter();

startStackRecording({
  component: 'fixture',
  reporterUrl: params.get('reporter') ?? undefined,
  exporter: params.get('exporter') === 'otlp' ? undefined : inMemory,
});

const plain = (span: ReadableSpan): PlainSpan => ({
  name: span.name,
  kind: SpanKind[span.kind],
  attributes: { ...span.attributes },
  resource: { ...span.resource.attributes },
});

window.__SPANS__ = async () => {
  await window.__STACK_FLUSH__?.();
  return inMemory.getFinishedSpans().map(plain);
};

// Renders the echo's answer, as an app renders its fetched data: the test waits for it like a user would.
let echoes = 0;
document.querySelector('[data-testid=go]')?.addEventListener('click', async () => {
  history.pushState({}, '', '/next');
  const response = await fetch('/api/echo');
  await response.text();
  document.querySelector('[data-testid=echo-status]')!.textContent = `echoed ${++echoes}`;
});
