import { type BrowserContext, test as base, expect, type Page } from '@playwright/test';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import {
  ACTION_SPAN,
  ERROR_TYPE,
  HTTP_REQUEST_METHOD,
  HTTP_RESPONSE_STATUS_CODE,
  NAVIGATION_SPAN,
  RUN,
  SCENARIO,
  SERVER_ADDRESS,
  SERVER_PORT,
  SERVICE_NAME,
  UI_ACTION_EVENT,
  UI_ACTION_TARGET,
  UI_NAVIGATION_KIND,
  UI_ROUTE,
  UI_URL,
  URL_FULL,
} from '../src/attributes.js';
import type { PlainSpan } from './fixture/plainSpan.js';

const REPORTER = 'http://reporter.test';
const TRACES = `${REPORTER}/v1/traces`;
const withReporter = `/?reporter=${encodeURIComponent(REPORTER)}`;

const spans = (page: Page): Promise<PlainSpan[]> => page.evaluate(() => window.__SPANS__());
const fetchSpans = async (page: Page) => (await spans(page)).filter((span) => span.kind === 'CLIENT');

async function tagRun(page: Page) {
  await page.addInitScript(() => {
    window.__STACK__ = { scenario: 's-1', run: 'r-1' };
  });
}

/** Answers the fixture's `GET /api/echo` and keeps the headers of every request. */
async function echo(page: Page): Promise<Record<string, string>[]> {
  const received: Record<string, string>[] = [];
  await page.route('**/api/echo', async (route) => {
    received.push(await route.request().allHeaders());
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
  });
  return received;
}

/** Answers every request to reporter.test (another origin, as the reporter is in the stack); keeps the POSTs' headers. */
async function routedReporter(context: BrowserContext): Promise<Record<string, string>[]> {
  const received: Record<string, string>[] = [];
  await context.route(`${REPORTER}/**`, async (route) => {
    if (route.request().method() === 'POST') received.push(await route.request().allHeaders());
    await route.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*' } });
  });
  return received;
}

interface ReporterRequest {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
  bodyBytes: number;
}

interface Reporter {
  url: string;
  /** The status every export is answered with. */
  status: number;
  received: ReporterRequest[];
}

// The real exporter's tests post to a real HTTP server: an export sent while the page unloads (keepalive) outlives
// the page, and Playwright's routing ends with the page. It answers CORS as the reporter does (Content-Type only).
const test = base.extend<{ reporter: Reporter }>({
  reporter: async ({}, use) => {
    const reporter: Reporter = { url: '', status: 200, received: [] };
    const server = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on('data', (chunk: Buffer) => chunks.push(chunk));
      request.on('end', () => {
        reporter.received.push({
          method: request.method ?? '',
          url: `${reporter.url}${request.url}`,
          headers: request.headers,
          bodyBytes: Buffer.concat(chunks).length,
        });
        response.setHeader('Access-Control-Allow-Origin', request.headers.origin ?? '*');
        if (request.method === 'OPTIONS') {
          response.setHeader('Access-Control-Allow-Methods', 'POST');
          response.setHeader('Access-Control-Allow-Headers', 'Content-Type');
          response.end();
          return;
        }
        // Retry-After: 0 makes the exporter's retries of a 503 immediate, so the test need not wait out its backoff.
        response.setHeader('Access-Control-Expose-Headers', 'Retry-After');
        if (reporter.status === 503) response.setHeader('Retry-After', '0');
        response.statusCode = reporter.status;
        response.end();
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    reporter.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    await use(reporter);
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  },
});

const withRealExporter = (reporter: Reporter) => `/?reporter=${encodeURIComponent(reporter.url)}&exporter=otlp`;
const posts = (reporter: Reporter) => reporter.received.filter((request) => request.method === 'POST');

test('page load is one navigation span', async ({ page }) => {
  await page.goto('/');

  const navigations = (await spans(page)).filter((span) => span.name === NAVIGATION_SPAN);
  expect(navigations).toEqual([
    {
      name: NAVIGATION_SPAN,
      spanId: expect.stringMatching(/^[0-9a-f]{16}$/),
      kind: 'INTERNAL',
      startMillis: expect.any(Number),
      attributes: { [UI_NAVIGATION_KIND]: 'load', [UI_URL]: page.url(), [UI_ROUTE]: '/' },
      resource: expect.objectContaining({ [SERVICE_NAME]: 'fixture' }),
    },
  ]);
});

test('a click records an action, a route change and a traced fetch', async ({ page, baseURL }) => {
  const echoed = await echo(page);
  await page.goto('/');

  await page.getByTestId('go').click();

  await expect.poll(() => fetchSpans(page)).toHaveLength(1);
  const recorded = await spans(page);
  expect(recorded.filter((span) => span.name === ACTION_SPAN)).toEqual([
    expect.objectContaining({
      kind: 'INTERNAL',
      attributes: { [UI_ACTION_EVENT]: 'click', [UI_ACTION_TARGET]: 'go', [UI_ROUTE]: '/' },
    }),
  ]);
  expect(recorded).toContainEqual(
    expect.objectContaining({
      name: NAVIGATION_SPAN,
      kind: 'INTERNAL',
      attributes: { [UI_NAVIGATION_KIND]: 'route', [UI_URL]: `${baseURL}/next`, [UI_ROUTE]: '/next' },
    }),
  );
  const [fetched] = await fetchSpans(page);
  expect(fetched.attributes).toEqual(
    expect.objectContaining({
      [HTTP_REQUEST_METHOD]: 'GET',
      [URL_FULL]: `${baseURL}/api/echo`,
      [SERVER_ADDRESS]: 'localhost',
      [SERVER_PORT]: Number(new URL(baseURL!).port),
      [HTTP_RESPONSE_STATUS_CODE]: 200,
    }),
  );
  expect(fetched.resource).toEqual(expect.objectContaining({ [SERVICE_NAME]: 'fixture' }));
  expect(echoed).toHaveLength(1);
  expect(echoed[0].traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
  expect(echoed[0]).not.toHaveProperty('baggage');
});

// The fetch instrumentation starts its spans on the wall clock (Date.now(), whole milliseconds): a ui span on the
// sub-millisecond performance clock would start after the fetch its own handler began in the same millisecond.
test('a fetch started in a submit handler starts no earlier than the submit, and the submit no earlier than its click', async ({ page }) => {
  await echo(page);
  await page.goto('/');

  const sends = 5; // the clocks disagree only within a millisecond: several sends make a mismatch all but certain
  for (let i = 0; i < sends; i++) await page.getByTestId('send').click();

  await expect.poll(() => fetchSpans(page)).toHaveLength(sends);
  const recorded = await spans(page);
  const actions = recorded.filter((span) => span.name === ACTION_SPAN);
  // Exported in dispatch order, click before its submit: of two ui steps starting together the later one in the list wins.
  expect(actions.map((span) => span.attributes[UI_ACTION_EVENT])).toEqual(Array(sends).fill(['click', 'submit']).flat());
  const byStart = (a: PlainSpan, b: PlainSpan) => a.startMillis - b.startMillis;
  const fetches = (await fetchSpans(page)).sort(byStart);
  for (let i = 0; i < sends; i++) {
    const [click, submit] = actions.slice(2 * i, 2 * i + 2);
    expect(submit.startMillis, `send ${i}`).toBeGreaterThanOrEqual(click.startMillis);
    expect(fetches[i].startMillis, `send ${i}`).toBeGreaterThanOrEqual(submit.startMillis);
  }
});

test('a fetch that gets no response records its error type', async ({ page }) => {
  await page.route('**/api/fail', (route) => route.abort());
  await page.goto('/');

  const outcome = await page.evaluate(() => fetch('/api/fail').then(() => 'answered', (error: Error) => error.name));
  expect(outcome).toBe('TypeError');

  await expect.poll(() => fetchSpans(page)).toHaveLength(1);
  const [failed] = await fetchSpans(page);
  expect(failed.attributes).toEqual(expect.objectContaining({ [ERROR_TYPE]: 'TypeError', [HTTP_RESPONSE_STATUS_CODE]: 0 }));
});

test('an answered fetch has no error type', async ({ page }) => {
  await echo(page);
  await page.goto('/');

  await page.getByTestId('go').click();

  await expect.poll(() => fetchSpans(page)).toHaveLength(1);
  const [fetched] = await fetchSpans(page);
  expect(fetched.attributes).not.toHaveProperty(ERROR_TYPE);
});

test('flush waits for a fetch still in flight', async ({ page }) => {
  await echo(page);
  await page.goto('/');

  await page.getByTestId('go').click();
  await expect(page.getByTestId('echo-status')).toHaveText('echoed 1');

  // The fetch instrumentation ends its span a little after the response; the flush must still export it.
  expect(await fetchSpans(page)).toHaveLength(1);
});

test('a tagged page puts the run on every span and on fetch baggage', async ({ page }) => {
  await tagRun(page);
  const echoed = await echo(page);
  await page.goto('/');

  await page.getByTestId('go').click();

  await expect.poll(() => fetchSpans(page)).toHaveLength(1);
  const recorded = await spans(page);
  expect(recorded.map((span) => span.name)).toEqual(
    expect.arrayContaining([NAVIGATION_SPAN, ACTION_SPAN, 'GET']),
  );
  for (const span of recorded) {
    expect(span.attributes, span.name).toEqual(expect.objectContaining({ [SCENARIO]: 's-1', [RUN]: 'r-1' }));
  }
  expect(echoed).toHaveLength(1);
  expect(echoed[0].baggage).toBe('stack.scenario=s-1,stack.run=r-1');
});

test('export requests to the reporter are not traced', async ({ page, context }) => {
  await tagRun(page);
  const received = await routedReporter(context);
  await echo(page);
  await page.goto(withReporter);

  const status = await page.evaluate((url) => fetch(url, { method: 'POST' }).then((response) => response.status), TRACES);
  expect(status).toBe(200);
  // A traced fetch after it: once its span is in, a span for the reporter request would be in too.
  await page.getByTestId('go').click();
  await expect.poll(() => fetchSpans(page)).toHaveLength(1);

  const [fetched] = await fetchSpans(page);
  expect(fetched.attributes[URL_FULL]).not.toContain(REPORTER);
  expect(received).toHaveLength(1);
  expect(received[0]).not.toHaveProperty('traceparent');
  expect(received[0]).not.toHaveProperty('baggage');
});

test('export requests are not traced when the reporter url is written differently', async ({ page, context }) => {
  await tagRun(page);
  const received = await routedReporter(context);
  await echo(page);
  // The same reporter as REPORTER, with an upper-case host, the default port and a trailing slash.
  await page.goto(`/?reporter=${encodeURIComponent('http://REPORTER.test:80/')}`);

  const status = await page.evaluate((url) => fetch(url, { method: 'POST' }).then((response) => response.status), TRACES);
  expect(status).toBe(200);
  await page.getByTestId('go').click();
  await expect.poll(() => fetchSpans(page)).toHaveLength(1);

  const [fetched] = await fetchSpans(page);
  expect(fetched.attributes[URL_FULL]).not.toContain(REPORTER);
  expect(received).toHaveLength(1);
  expect(received[0]).not.toHaveProperty('traceparent');
});

test('the real exporter posts protobuf to the reporter', async ({ page, reporter }) => {
  await echo(page);
  await page.goto(withRealExporter(reporter));

  await page.getByTestId('go').click();
  await page.evaluate(() => window.__STACK_FLUSH__!());

  expect(posts(reporter).length).toBeGreaterThan(0);
  for (const request of posts(reporter)) {
    expect(request.url).toBe(`${reporter.url}/v1/traces`);
    expect(request.headers['content-type']).toBe('application/x-protobuf');
    expect(request.bodyBytes).toBeGreaterThan(0);
  }
  // The preflight asks for Content-Type only, the one request header the reporter's CORS allows.
  for (const preflight of reporter.received.filter((request) => request.method === 'OPTIONS')) {
    expect(preflight.headers['access-control-request-headers']).toBe('content-type');
  }
});

test('spans are exported on pagehide', async ({ page, reporter }) => {
  await page.goto(withRealExporter(reporter));

  // The load span is still in the batch (scheduled 200 ms after the load event) when the page goes away.
  await page.close({ runBeforeUnload: true });

  await expect.poll(() => posts(reporter).length).toBe(1);
  expect(posts(reporter)[0].url).toBe(`${reporter.url}/v1/traces`);
});

test('a failing reporter only warns', async ({ page, reporter }) => {
  reporter.status = 503;
  const warnings: string[] = [];
  const errors: Error[] = [];
  page.on('console', (message) => {
    if (message.type() === 'warning') warnings.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error));
  const echoed = await echo(page);
  await page.goto(withRealExporter(reporter));

  await page.getByTestId('go').click();
  await page.evaluate(() => window.__STACK_FLUSH__!());
  await page.getByTestId('go').click();
  await page.evaluate(() => window.__STACK_FLUSH__!());

  // The page keeps working: both clicks still navigate and fetch.
  await expect.poll(() => echoed.length).toBe(2);
  expect(page.url()).toMatch(/\/next$/);
  expect(posts(reporter).length).toBeGreaterThan(1);
  expect(warnings.filter((warning) => warning.startsWith('stack recording:'))).toEqual([
    `stack recording: export to ${reporter.url}/v1/traces failed`,
  ]);
  expect(errors).toEqual([]);
});

/** Exposes the harness's screenshot function: keeps each call's span ids and when it answered, `millis` after the call. */
async function screenshotHarness(page: Page, millis = 300): Promise<{ spanIds: string[]; answeredAt: number }[]> {
  const calls: { spanIds: string[]; answeredAt: number }[] = [];
  await page.exposeFunction('__STACK_SCREENSHOT__', async (spanIds: string[]) => {
    await new Promise((resolve) => setTimeout(resolve, millis));
    calls.push({ spanIds, answeredAt: Date.now() });
  });
  return calls;
}

test('screenshot calls group the steps as they settle', async ({ page }) => {
  await echo(page);
  const calls = await screenshotHarness(page);
  await page.goto('/');

  await page.getByTestId('go').click();
  await expect(page.getByTestId('echo-status')).toHaveText('echoed 1');

  await expect.poll(() => calls.length).toBe(2);
  const recorded = await spans(page);
  const load = recorded.find((span) => span.attributes[UI_NAVIGATION_KIND] === 'load')!;
  const click = recorded.find((span) => span.name === ACTION_SPAN)!;
  const route = recorded.find((span) => span.attributes[UI_NAVIGATION_KIND] === 'route')!;
  expect(calls.map((call) => call.spanIds)).toEqual([[load.spanId], [click.spanId, route.spanId]]);
});

test('a Playwright click waits for the shield', async ({ page }) => {
  await echo(page);
  const calls = await screenshotHarness(page, 500);
  await page.goto('/');

  await page.getByTestId('go').click();

  const click = (await spans(page)).find((span) => span.name === ACTION_SPAN)!;
  expect(calls[0].spanIds).toHaveLength(1);
  expect(click.startMillis).toBeGreaterThanOrEqual(calls[0].answeredAt);
});

test('flush waits for a screenshot in flight', async ({ page }) => {
  await echo(page);
  const calls = await screenshotHarness(page, 500);
  await page.goto('/');
  await page.getByTestId('go').click();

  await page.evaluate(() => window.__STACK_FLUSH__!());
  expect(calls).toHaveLength(2);
});

test('navigating away while a screenshot is pending neither hangs nor fails', async ({ page }) => {
  await page.exposeFunction('__STACK_SCREENSHOT__', () => new Promise<void>(() => undefined));
  await page.goto('/');
  await expect(page.locator('[data-stack-shield]')).toHaveCount(1);

  await page.goto('/?again', { timeout: 5000 });
  expect(page.url()).toContain('again');
});
