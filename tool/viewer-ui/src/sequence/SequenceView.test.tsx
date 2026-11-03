import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { comp, createOrderFlow, loadStep, methodFlow, routeStep, submitStep, viewWith } from '../board/testData';
import { SequenceView } from './SequenceView';

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/workspace') {
        return Response.json(
          viewWith({
            components: [
              comp('e2e-client', { type: 'client', displayName: 'E2E client' }),
              comp('orders-service', { displayName: 'Orders service' }),
              comp('postgresql-orders', { type: 'database', displayName: 'Orders DB' }),
            ],
          }),
        );
      }
      if (url === '/api/runs/run-1/flow') return Response.json(createOrderFlow);
      if (url === '/api/runs/methods/flow') return Response.json(methodFlow);
      if (url === '/api/runs/shots/flow') return Response.json(shotsFlow);
      if (url === '/api/runs/empty/flow') return Response.json({ ...createOrderFlow, runId: 'empty', steps: [] });
      return Response.json({ error: 'unknown run' }, { status: 404 });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

// three ui steps, the route change and the submit settled together and share one screenshot
const [first, shared] = ['1111111111111111', '2222222222222222'];
const shotsFlow = {
  ...createOrderFlow,
  runId: 'shots',
  steps: [
    { ...loadStep, screenshot: first },
    { ...routeStep, screenshot: shared },
    { ...submitStep, screenshot: shared },
    ...createOrderFlow.steps,
  ],
};

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<p>the board</p>} />
          <Route path="/sequence/:runId" element={<SequenceView />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test('shows the participants of the run with their display names', async () => {
  renderAt('/sequence/run-1');
  expect(await screen.findByText('E2E client')).toBeInTheDocument();
  expect(screen.getByText('Orders service')).toBeInTheDocument();
  expect(screen.getByText('Orders DB')).toBeInTheDocument();
});

test('every message is a button; the details panel asks for a selection first', async () => {
  renderAt('/sequence/run-1');
  const diagram = await screen.findByRole('group', { name: /sequence diagram/i });
  expect(within(diagram).getAllByRole('button')).toHaveLength(4);
  expect(screen.getByText(/select a message/i)).toBeInTheDocument();
});

test('clicking a message shows its payload', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/run-1');
  await user.click(await screen.findByRole('button', { name: /request POST \/orders/ }));
  expect(screen.getByText(/"customerId": "c-1"/)).toBeInTheDocument();
  expect(screen.queryByText(/select a message/i)).not.toBeInTheDocument();
});

test('clicking a database response shows the statement of the same step', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/run-1');
  await user.click(await screen.findByRole('button', { name: /response 1 row$/ }));
  expect(screen.getByText(/values \(\?, \?, \?, \?\)/)).toBeInTheDocument();
  expect(screen.getByRole('region', { name: 'Response' })).toHaveClass('is-selected');
});

test('an empty run says so', async () => {
  renderAt('/sequence/empty');
  expect(await screen.findByText(/recorded no calls/i)).toBeInTheDocument();
});

test('an unknown run shows the error and a way back', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/nope');
  // the query client retries a failed load once, after a delay
  expect(await screen.findByText(/could not load the run/i, {}, { timeout: 4000 })).toBeInTheDocument();
  await user.click(screen.getByRole('link', { name: /board/i }));
  expect(screen.getByText('the board')).toBeInTheDocument();
});

test('the bar counts calls, and method calls when there are any', async () => {
  const first = renderAt('/sequence/run-1');
  expect(await screen.findByText('2 calls')).toBeInTheDocument();
  first.unmount();
  renderAt('/sequence/methods');
  expect(await screen.findByText('4 calls · 4 method calls')).toBeInTheDocument();
});

test('without method steps there is nothing to expand', async () => {
  renderAt('/sequence/run-1');
  await screen.findByText('2 calls');
  expect(screen.queryByRole('button', { name: 'Expand all' })).not.toBeInTheDocument();
});

test('expand all and collapse all', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/methods');
  const diagram = await screen.findByRole('group', { name: /sequence diagram/i });
  expect(within(diagram).getAllByRole('button')).toHaveLength(8); // 4 calls, request and response each
  expect(screen.getByRole('button', { name: '▸ 4 classes' })).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Expand all' }));
  expect(within(diagram).getAllByRole('button')).toHaveLength(16);
  expect(screen.getByText('OrderService')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /classes$/ })).not.toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Collapse all' }));
  expect(within(diagram).getAllByRole('button')).toHaveLength(8);
  expect(screen.queryByText('OrderService')).not.toBeInTheDocument();
});

test('a service expands on its own toggle and collapses on its group button', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/methods');
  await user.click(await screen.findByRole('button', { name: '▸ 4 classes' }));
  expect(screen.getByText('OrderService')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Collapse Orders service' }));
  expect(screen.queryByText('OrderService')).not.toBeInTheDocument();
});

test('collapsing a service clears a selected method step of it', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/methods');
  await user.click(await screen.findByRole('button', { name: 'Expand all' }));
  const diagram = screen.getByRole('group', { name: /sequence diagram/i });
  await user.click(within(diagram).getByRole('button', { name: /OrderService\.placeOrder/ }));
  const panel = screen.getByRole('complementary', { name: 'Message details' });
  expect(within(panel).getByRole('heading', { name: 'OrderService.placeOrder' })).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Collapse Orders service' }));
  expect(within(panel).queryByRole('heading', { name: 'OrderService.placeOrder' })).not.toBeInTheDocument();
  expect(within(panel).getByText(/Select a message/)).toBeInTheDocument();
});

test('collapse all clears a selected method step, but keeps a selected call', async () => {
  const user = userEvent.setup();
  renderAt('/sequence/methods');
  await user.click(await screen.findByRole('button', { name: 'Expand all' }));
  const diagram = screen.getByRole('group', { name: /sequence diagram/i });
  const panel = screen.getByRole('complementary', { name: 'Message details' });

  await user.click(within(diagram).getByRole('button', { name: /POST \/orders/ }));
  await user.click(screen.getByRole('button', { name: 'Collapse all' }));
  expect(within(panel).getByRole('heading', { name: 'POST /orders' })).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Expand all' }));
  await user.click(within(diagram).getByRole('button', { name: /OrderService\.placeOrder/ }));
  await user.click(screen.getByRole('button', { name: 'Collapse all' }));
  expect(within(panel).getByText(/Select a message/)).toBeInTheDocument();
});

async function openCarouselAt(label: string) {
  const user = userEvent.setup();
  renderAt('/sequence/shots');
  const diagram = await screen.findByRole('group', { name: /sequence diagram/i });
  await user.click(within(diagram).getByRole('button', { name: `screenshot ${label}` }));
  await user.click(screen.getByRole('img', { name: `Screenshot after ${label}` }));
  return { user, diagram, dialog: screen.getByRole('dialog', { name: 'Screenshot' }) };
}

const panelHeading = () => within(screen.getByRole('complementary', { name: 'Message details' })).getByRole('heading', { level: 2 });

test('the panel screenshot opens a carousel over the run\'s browser steps', async () => {
  const { dialog } = await openCarouselAt('load /');
  expect(within(dialog).getByRole('img')).toHaveAttribute('src', `/api/runs/shots/screenshots/${first}`);
  expect(within(dialog).getByText('load / · 1 of 3')).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Previous screenshot' })).toBeDisabled();
  expect(within(dialog).getByRole('button', { name: 'Next screenshot' })).toBeEnabled();
});

test('the arrow keys and buttons move one step at a time and select it in the sequence', async () => {
  const { user, diagram, dialog } = await openCarouselAt('load /');

  await user.keyboard('{ArrowRight}');
  expect(within(dialog).getByText('→ /order · 2 of 3')).toBeInTheDocument();
  expect(within(dialog).getByRole('img')).toHaveAttribute('src', `/api/runs/shots/screenshots/${shared}`);
  expect(within(diagram).getByRole('button', { name: 'request → /order' })).toHaveAttribute('aria-pressed', 'true');
  expect(within(diagram).getByRole('button', { name: 'screenshot → /order' })).toHaveAttribute('aria-pressed', 'true');
  expect(within(diagram).getByRole('button', { name: 'request load /' })).toHaveAttribute('aria-pressed', 'false');
  expect(panelHeading()).toHaveTextContent('→ /order');

  // the same screenshot again, as the next step shares it
  await user.click(within(dialog).getByRole('button', { name: 'Next screenshot' }));
  expect(within(dialog).getByText('submit order-form · 3 of 3')).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Next screenshot' })).toBeDisabled();
  await user.keyboard('{ArrowRight}');
  expect(within(dialog).getByText('submit order-form · 3 of 3')).toBeInTheDocument();

  await user.keyboard('{ArrowLeft}');
  expect(within(dialog).getByText('→ /order · 2 of 3')).toBeInTheDocument();
  await user.click(within(dialog).getByRole('button', { name: 'Previous screenshot' }));
  expect(within(dialog).getByText('load / · 1 of 3')).toBeInTheDocument();
  expect(panelHeading()).toHaveTextContent('load /');
});

test('closing the carousel keeps the step it showed selected', async () => {
  const { user } = await openCarouselAt('load /');
  await user.keyboard('{ArrowRight}');
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(panelHeading()).toHaveTextContent('→ /order');
});

test('the carousel closes with its button', async () => {
  const { user, dialog } = await openCarouselAt('→ /order');
  expect(within(dialog).getByText('→ /order · 2 of 3')).toBeInTheDocument();
  await user.click(within(dialog).getByRole('button', { name: 'Close' }));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('moving in the carousel scrolls the selected step into view behind it', async () => {
  const scrolled: Element[] = [];
  Element.prototype.scrollIntoView = vi.fn(function (this: Element) {
    scrolled.push(this);
  });
  try {
    const { user } = await openCarouselAt('load /');
    scrolled.length = 0;
    await user.keyboard('{ArrowRight}');
    expect(scrolled.map((e) => e.getAttribute('aria-label'))).toContain('request → /order');
  } finally {
    delete (Element.prototype as Partial<Element>).scrollIntoView;
  }
});
