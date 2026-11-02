import type { Context } from '@opentelemetry/api';
import type { ReadableSpan, Span, SpanProcessor } from '@opentelemetry/sdk-trace';
import { ACTION_SPAN, NAVIGATION_SPAN } from './attributes.js';

/** The longest a batch of ui steps waits for the page to settle before it is shot as it is. */
export const SETTLE_CAP_MILLIS = 2000;
/** The longest the shield stays up for a screenshot the harness never answers. */
export const SHIELD_TIMEOUT_MILLIS = 10_000;
/** Marks the shield element. */
export const SHIELD_ATTRIBUTE = 'data-stack-shield';

const SHIELD_STYLE = 'position: fixed; inset: 0; z-index: 2147483647; background: transparent';

/** The spans of the page that are still open (StackSpanProcessor). */
export interface OpenSpans {
  /** Resolves once no span is open, or after [maxWaitMillis]. */
  whenNoSpanOpen(maxWaitMillis: number): Promise<void>;
  hasOpenSpan(): boolean;
}

/**
 * Asks the test harness for one screenshot per settle of the page's ui steps (page loads, route changes, clicks and
 * submits), through `window.__STACK_SCREENSHOT__`, which only `StackBrowser.tag` defines: without it this does nothing.
 *
 * The first step of a batch starts the settle: once no span is open (the fetches the steps started have ended) and still
 * none after two painted frames, at most 2 s after that step, the batch is closed and shot; a step after that starts
 * the next batch. The frames matter twice: the app paints what it fetched, and a fetch a submit handler starts only
 * opens after the click's step (the capture-phase listener runs first), so it is seen on the second look. Meanwhile a transparent shield covers the page, so that Playwright's next click waits (another element
 * would receive it) until the screenshot is taken and the page it shows is the result of the steps before the click.
 */
export class ScreenshotRequester implements SpanProcessor {
  private pending: string[] = [];
  private settling: Promise<void> | null = null;
  private readonly inFlight = new Set<Promise<void>>();
  private shield: HTMLElement | null = null;

  constructor(private readonly spans: OpenSpans) {}

  onStart(_span: Span, _parentContext: Context): void {}

  onEnd(span: ReadableSpan): void {
    if (span.name !== NAVIGATION_SPAN && span.name !== ACTION_SPAN) return;
    if (typeof window === 'undefined' || typeof window.__STACK_SCREENSHOT__ !== 'function') return;
    this.pending.push(span.spanContext().spanId);
    this.raiseShield();
    this.settling ??= this.settle();
  }

  /** Resolves once no batch is settling and no screenshot call is in flight. */
  async whenTaken(): Promise<void> {
    while (this.settling || this.inFlight.size > 0) await Promise.all([this.settling, ...this.inFlight]);
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }

  private async settle(): Promise<void> {
    const deadline = Date.now() + SETTLE_CAP_MILLIS;
    const quiet = async () => {
      do {
        await this.spans.whenNoSpanOpen(SETTLE_CAP_MILLIS);
        await twoFrames();
      } while (this.spans.hasOpenSpan() && Date.now() < deadline);
    };
    await withTimeout(quiet(), SETTLE_CAP_MILLIS);
    const spanIds = this.pending;
    this.pending = [];
    this.settling = null;
    this.take(spanIds);
  }

  private take(spanIds: string[]): void {
    const call: Promise<void> = withTimeout(
      Promise.resolve().then(() => window.__STACK_SCREENSHOT__?.(spanIds)),
      SHIELD_TIMEOUT_MILLIS,
    )
      .catch(() => undefined) // a failed screenshot is the harness's to report; the page goes on
      .then(() => {
        this.inFlight.delete(call);
        this.lowerShieldWhenIdle();
      });
    this.inFlight.add(call);
  }

  private raiseShield(): void {
    if (this.shield) return;
    const shield = document.createElement('div');
    shield.setAttribute(SHIELD_ATTRIBUTE, '');
    shield.style.cssText = SHIELD_STYLE;
    document.documentElement.appendChild(shield);
    this.shield = shield;
  }

  private lowerShieldWhenIdle(): void {
    if (this.settling || this.inFlight.size > 0) return;
    this.shield?.remove();
    this.shield = null;
  }
}

/** Resolves when [promise] settles or after [millis], whichever comes first; never rejects for the timeout. */
function withTimeout(promise: Promise<unknown>, millis: number): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<void>((resolve) => (timer = setTimeout(resolve, millis)));
  return Promise.race([promise.then(() => undefined), timeout]).finally(() => clearTimeout(timer));
}

const twoFrames = () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
