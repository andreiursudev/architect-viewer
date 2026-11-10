import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { Flow } from '../api/types';
import { comp, createOrderFlow, viewWith } from '../board/testData';
import { InWorkspace } from '../test/inWorkspace';
import { useUiStore } from '../store/uiStore';
import { SidePanel } from './SidePanel';

const [request, insert] = createOrderFlow.steps;
const again = { ...insert, stepId: 'again', label: 'select * from orders', startEpochNanos: insert.startEpochNanos + 1_000_000 };
// the request (1), then two statements in a row over one connection (2×2)
const flow: Flow = { ...createOrderFlow, steps: [request, insert, again] };

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url === '/api/w/test/workspace' ? Response.json(viewWith({ components: [comp('orders-service')] })) : Response.json(flow),
    ),
  );
  useUiStore.getState().selectScenario('create-order', 'run-1');
});

afterEach(() => {
  vi.unstubAllGlobals();
  useUiStore.getState().selectScenario(null, null);
  useUiStore.getState().selectComponent(null);
});

function renderPanel() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <InWorkspace>
        <SidePanel />
      </InWorkspace>
    </QueryClientProvider>,
  );
}

test('a picked step shows the same details the sequence view does', async () => {
  act(() => useUiStore.getState().selectStep(request.stepId));
  renderPanel();
  const panel = screen.getByRole('complementary', { name: 'Details' });
  expect(await within(panel).findByText('Step 1 of 2')).toBeInTheDocument();
  expect(within(panel).getByRole('heading', { name: 'POST /orders' })).toBeInTheDocument();
  expect(within(panel).getByRole('region', { name: 'Request' })).toHaveTextContent('"customerId": "c-1"');
  expect(within(panel).getByRole('region', { name: 'Response' })).toHaveTextContent('"status": "CREATED"');
  expect(within(panel).getByRole('link', { name: 'Sequence view' })).toHaveAttribute(
    'href',
    `/w/test/sequence/run-1?step=${encodeURIComponent(request.stepId)}`,
  );
});

test('the run is walked one number at a time, and a number of several calls lists them to pick from', async () => {
  const user = userEvent.setup();
  act(() => useUiStore.getState().selectStep(request.stepId));
  renderPanel();
  expect(await screen.findByRole('button', { name: 'Previous step' })).toBeDisabled();

  await user.click(screen.getByRole('button', { name: 'Next step' }));
  expect(screen.getByText('Step 2 of 2')).toBeInTheDocument();
  expect(useUiStore.getState().selectedStepId).toBe(insert.stepId);
  expect(screen.getByRole('button', { name: 'Next step' })).toBeDisabled();

  const calls = screen.getByRole('list', { name: 'Calls of step 2' });
  await user.click(within(calls).getByRole('button', { name: /select \* from orders/ }));
  expect(useUiStore.getState().selectedStepId).toBe('again');
  expect(screen.getByText('Step 2 of 2')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'select * from orders' })).toBeInTheDocument();
});

test('picking another run or a component lets the step go', async () => {
  act(() => useUiStore.getState().selectStep(request.stepId));
  act(() => useUiStore.getState().selectRun('run-2'));
  expect(useUiStore.getState().selectedStepId).toBeNull();

  act(() => useUiStore.getState().selectStep(request.stepId));
  act(() => useUiStore.getState().selectComponent('orders-service'));
  expect(useUiStore.getState().selectedStepId).toBeNull();
});
