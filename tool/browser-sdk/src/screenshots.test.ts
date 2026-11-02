import { InMemorySpanExporter, SimpleSpanProcessor, type Span, TracerProvider } from '@opentelemetry/sdk-trace';
import { recordActions } from './actions.js';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ACTION_SPAN, NAVIGATION_SPAN } from './attributes.js';
import { ScreenshotRequester, SETTLE_CAP_MILLIS, SHIELD_ATTRIBUTE } from './screenshots.js';
import { StackSpanProcessor } from './StackSpanProcessor.js';

let calls: string[][];
let answer: () => Promise<void>;

beforeEach(() => {
  calls = [];
  answer = () => Promise.resolve();
  window.__STACK_SCREENSHOT__ = (spanIds: string[]) => {
    calls.push(spanIds);
    return answer();
  };
});

afterEach(() => {
  delete window.__STACK_SCREENSHOT__;
  document.querySelectorAll(`[${SHIELD_ATTRIBUTE}]`).forEach((shield) => shield.remove());
  vi.useRealTimers();
});

function setUp() {
  const stack = new StackSpanProcessor();
  const requester = new ScreenshotRequester(stack);
  const tracer = new TracerProvider({ spanProcessors: [stack, requester] }).getTracer('test');
  /** A ui step: a zero-length span ended at once, as the package records clicks and route changes. */
  const step = (name = ACTION_SPAN) => {
    const span = tracer.startSpan(name);
    span.end();
    return span.spanContext().spanId;
  };
  return { requester, step, open: (name = 'fetch'): Span => tracer.startSpan(name) as Span };
}

const shield = () => document.querySelector(`[${SHIELD_ATTRIBUTE}]`);
const sleep = (millis: number) => new Promise((resolve) => setTimeout(resolve, millis));

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => (resolve = r));
  return { promise, resolve };
}

it('steps that settle together share one screenshot call', async () => {
  const { step, open } = setUp();
  const fetch = open();
  const click = step(ACTION_SPAN);
  const route = step(NAVIGATION_SPAN);
  await sleep(50);
  expect(calls).toEqual([]);

  fetch.end();
  await vi.waitFor(() => expect(calls).toEqual([[click, route]]));
});

it("a fetch the step's own handler starts holds the batch", async () => {
  const { step, open } = setUp();
  // A click runs the capture-phase listener (the step) first; the submit handler then starts its fetch in the same task.
  const click = step();
  const fetch = open();
  await sleep(100);
  const route = step(NAVIGATION_SPAN); // the app navigates on the response
  await sleep(20);
  expect(calls).toEqual([]);

  fetch.end(); // the fetch span ends a little after its response
  await vi.waitFor(() => expect(calls).toEqual([[click, route]]));
});

it('a step after the call starts a new batch', async () => {
  const { step } = setUp();
  const first = step();
  await vi.waitFor(() => expect(calls).toHaveLength(1));
  const second = step(NAVIGATION_SPAN);
  await vi.waitFor(() => expect(calls).toEqual([[first], [second]]));
});

it('a page that never settles is shot after 2 s', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'Date'] });
  const { step, open } = setUp();
  open(); // a fetch that never ends
  const click = step();

  await vi.advanceTimersByTimeAsync(SETTLE_CAP_MILLIS - 10);
  expect(calls).toEqual([]);
  await vi.advanceTimersByTimeAsync(20);
  expect(calls).toEqual([[click]]);
});

it('the shield covers the page from the step until the call answers', async () => {
  const call = deferred();
  answer = () => call.promise;
  const { step } = setUp();
  expect(shield()).toBeNull();

  step();
  expect(shield()).not.toBeNull();
  await vi.waitFor(() => expect(calls).toHaveLength(1));
  expect(shield()).not.toBeNull();

  call.resolve();
  await vi.waitFor(() => expect(shield()).toBeNull());
});

it('the shield is a transparent cover over the whole viewport', async () => {
  const { requester, step } = setUp();
  step();
  const cover = shield() as HTMLElement;
  expect(cover.style.position).toBe('fixed');
  expect(cover.style.inset).toBe('0px');
  expect(cover.style.zIndex).toBe('2147483647');
  expect(cover.style.background).toBe('transparent');
  await requester.whenTaken(); // its batch must not call into the next test
});

it('the shield comes down after 10 s when the call never answers', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'Date'] });
  answer = () => new Promise<void>(() => undefined);
  const { requester, step } = setUp();
  step();
  await vi.advanceTimersByTimeAsync(100);
  expect(calls).toHaveLength(1);
  expect(shield()).not.toBeNull();

  await vi.advanceTimersByTimeAsync(10_000);
  expect(shield()).toBeNull();
  await requester.whenTaken();
});

it('a rejected call is ignored', async () => {
  answer = () => Promise.reject(new Error('the harness failed'));
  const { requester, step } = setUp();
  step();
  await vi.waitFor(() => expect(calls).toHaveLength(1));
  await requester.whenTaken();
  expect(shield()).toBeNull();
});

it('other spans start no batch', async () => {
  const { open } = setUp();
  open('fetch').end();
  expect(shield()).toBeNull();
  await sleep(50);
  expect(calls).toEqual([]);
});

it('without the harness function nothing waits and no shield is laid', async () => {
  delete window.__STACK_SCREENSHOT__;
  const { requester, step, open } = setUp();
  open(); // would hold a batch, were there one
  step();
  expect(shield()).toBeNull();
  await requester.whenTaken();
});

it('whenTaken waits for a batch settling and a call in flight', async () => {
  const call = deferred();
  answer = () => call.promise;
  const { requester, step, open } = setUp();
  const fetch = open();
  step();
  let taken = false;
  void requester.whenTaken().then(() => (taken = true));
  await sleep(20);
  expect(taken).toBe(false);

  fetch.end();
  await vi.waitFor(() => expect(calls).toHaveLength(1));
  await sleep(20);
  expect(taken).toBe(false);

  call.resolve();
  await vi.waitFor(() => expect(taken).toBe(true));
});

it('a click that lands on the shield is no ui step and warns once', async () => {
  const { requester, step } = setUp();
  const exporter = new InMemorySpanExporter();
  const provider = new TracerProvider({ spanProcessors: [new SimpleSpanProcessor({ exporter })] });
  recordActions(provider.getTracer('actions'));
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  try {
    step(); // lays the shield
    const cover = shield()!;
    cover.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    cover.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    cover.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    cover.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(exporter.getFinishedSpans()).toEqual([]);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(String(warn.mock.calls[0][0])).toContain('screenshots = false');
  } finally {
    warn.mockRestore();
    await requester.whenTaken();
  }
});
