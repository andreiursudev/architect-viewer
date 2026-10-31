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

document.querySelector('[data-testid=go]')?.addEventListener('click', async () => {
  history.pushState({}, '', '/next');
  await fetch('/api/echo');
});
