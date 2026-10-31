import { type BrowserContext, test as base, expect, type Page } from '@playwright/test';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import {
  ACTION_SPAN,
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
      kind: 'INTERNAL',
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
