import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { comp, createOrderFlow, viewWith } from '../board/testData';
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
      if (url === '/api/runs/empty/flow') return Response.json({ ...createOrderFlow, runId: 'empty', steps: [] });
      return Response.json({ error: 'unknown run' }, { status: 404 });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

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
  await user.click(await screen.findByRole('button', { name: /response 1 rows/ }));
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
