import { context, propagation, trace } from '@opentelemetry/api';
import { CompositePropagator, type ExportResult, ExportResultCode, W3CTraceContextPropagator } from '@opentelemetry/core';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-proto';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  BatchSpanProcessor,
  type ReadableSpan,
  type SpanExporter,
  StackContextManager,
  TracerProvider,
} from '@opentelemetry/sdk-trace';
import { recordActions } from './actions.js';
import { SERVICE_NAME } from './attributes.js';
import { recordNavigations } from './navigation.js';
import { StackBaggagePropagator } from './StackBaggagePropagator.js';
import { StackSpanProcessor } from './StackSpanProcessor.js';

// Also brings the window.__STACK__ / window.__STACK_FLUSH__ declarations along with the package's types.
export type { StackRun } from './stackContext.js';

export interface StackRecordingOptions {
  /** The component the page belongs to (the spans' service.name), e.g. 'frontend'. */
  component: string;
  /** Where the stack reporter listens; spans go to `<reporterUrl>/v1/traces`. Default 'http://localhost:4318'. */
  reporterUrl?: string;
  /** For tests only: where the spans go instead of the reporter. */
  exporter?: SpanExporter;
}

const DEFAULT_REPORTER_URL = 'http://localhost:4318';

// A flush first waits for the spans still open: the fetch instrumentation ends a fetch's span 100-300 ms after the
// response (it waits for the resource-timing entry), so a test that flushes as soon as the page shows the data would
// otherwise lose that span. Capped, because a span that never ends (a hung fetch) must not hold the test up.
const FLUSH_WAIT_FOR_OPEN_SPANS_MILLIS = 1000;

let started = false;

/**
 * Starts recording this page for the stack tool: its load and route changes, clicks and form submits, and every
 * fetch (with the scenario and run as baggage, so the backend's spans join the same run). Call it once, before the
 * app renders; later calls do nothing.
 */
export function startStackRecording(options: StackRecordingOptions): void {
  if (started) return;
  started = true;

  const reporterUrl = (options.reporterUrl ?? DEFAULT_REPORTER_URL).replace(/\/+$/, '');
  const tracesUrl = `${reporterUrl}/v1/traces`;
  // The OTLP exporter sends Content-Type only: the reporter's CORS allows no other request header.
  const exporter = new WarnOnFailure(options.exporter ?? new OTLPTraceExporter({ url: tracesUrl }), tracesUrl);

  const stackProcessor = new StackSpanProcessor();
  const provider = new TracerProvider({
    resource: resourceFromAttributes({ [SERVICE_NAME]: options.component }),
    spanProcessors: [stackProcessor, new BatchSpanProcessor({ exporter, scheduledDelayMillis: 200 })],
  });
  // No zone.js: the stack context manager keeps a span active only through synchronous code, which is all the fetch
  // instrumentation needs to put the span's traceparent on the request.
  context.setGlobalContextManager(new StackContextManager().enable());
  propagation.setGlobalPropagator(
    new CompositePropagator({ propagators: [new W3CTraceContextPropagator(), new StackBaggagePropagator()] }),
  );
  trace.setGlobalTracerProvider(provider);

  // The fetch instrumentation writes the stable HTTP semantic conventions (http.request.method, url.full, ...).
  // The exporter's own requests are left out: a span per export would be exported again, forever.
  registerInstrumentations({
    tracerProvider: provider,
    instrumentations: [new FetchInstrumentation({ ignoreUrls: [new RegExp(`^${escapeRegExp(reporterUrl)}([/?#]|$)`)] })],
  });

  const tracer = provider.getTracer('@stackvibed/browser');
  recordNavigations(tracer);
  recordActions(tracer);

  // A failed export has already warned (WarnOnFailure); flushing never rejects, so callers need not care.
  const flushNow = () => provider.forceFlush().catch(() => undefined);
  window.__STACK_FLUSH__ = () => stackProcessor.whenNoSpanOpen(FLUSH_WAIT_FOR_OPEN_SPANS_MILLIS).then(flushNow);
  // The browser batch processor also flushes when the page is hidden; this keeps the flush on unload independent of it.
  // An unloading page cannot wait (its timers may never fire), so it exports what has ended, in a keepalive request.
  window.addEventListener('pagehide', () => void flushNow());
}

/** Passes spans on, and warns once per page the first time the reporter cannot be reached. */
class WarnOnFailure implements SpanExporter {
  private warned = false;

  constructor(
    private readonly exporter: SpanExporter,
    private readonly url: string,
  ) {}

  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    this.exporter.export(spans, (result) => {
      if (result.code !== ExportResultCode.SUCCESS && !this.warned) {
        this.warned = true;
        console.warn(`stack recording: export to ${this.url} failed`);
      }
      resultCallback(result);
    });
  }

  shutdown(): Promise<void> {
    return this.exporter.shutdown();
  }

  forceFlush(): Promise<void> {
    return this.exporter.forceFlush?.() ?? Promise.resolve();
  }
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
