import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import type { RecordingManifest, ScenarioSummary } from '../api/types';
import { createOrderFlow, viewWith } from '../board/testData';
import { useUiStore } from '../store/uiStore';
import { ScenarioPicker } from './ScenarioPicker';

const scenarios: ScenarioSummary[] = [
  { id: 'create-order', description: 'A customer places an order', latestRunId: 'run-2', runCount: 2 },
  { id: 'get-order', description: 'Not recorded yet', latestRunId: null, runCount: 0 },
];

function manifest(runId: string, startedAt: string): RecordingManifest {
  return {
    schemaVersion: 1,
    runId,
    scenarioId: 'create-order',
    kind: 'e2e',
    startedAt,
    lastEventAt: startedAt,
    stoppedAt: null,
    reporterVersion: '0.1.0-test',
    componentsSeen: ['orders-service'],
  };
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/workspace') return Response.json(viewWith({ scenarios }));
      if (url === '/api/scenarios/create-order/runs') {
        return Response.json([manifest('run-2', '2026-10-27T23:10:00Z'), manifest('run-1', '2026-10-27T22:41:05Z')]);
      }
      if (url.startsWith('/api/runs/')) return Response.json({ ...createOrderFlow, runId: url.split('/')[3] });
      return new Response(null, { status: 404 });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

function renderPicker() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter>
        <Routes>
          <Route path="*" element={<><ScenarioPicker /><Where /></>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function openList(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /scenario/i }));
  return screen.findByRole('listbox');
}

test('selecting a scenario selects its latest run', async () => {
  const user = userEvent.setup();
  renderPicker();

  const list = await openList(user);
  expect(within(list).getByText('A customer places an order')).toBeInTheDocument(); // the description is the subtitle
  await user.click(within(list).getByRole('option', { name: /create-order/ }));

  expect(useUiStore.getState().selectedRunId).toBe('run-2');
  expect(useUiStore.getState().selectedScenarioId).toBe('create-order');
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument(); // choosing closes the list
});

test('scenario without runs is disabled', async () => {
  const user = userEvent.setup();
  renderPicker();

  const list = await openList(user);
  const option = within(list).getByRole('option', { name: /get-order/ });
  expect(option).toHaveAttribute('aria-disabled', 'true');

  await user.click(option);
  expect(useUiStore.getState().selectedRunId).toBeNull();
  expect(screen.getByRole('listbox')).toBeInTheDocument();
});

test('the run dropdown lists the runs newest first and switches the run', async () => {
  const user = userEvent.setup();
  renderPicker();
  await user.click(within(await openList(user)).getByRole('option', { name: /create-order/ }));

  const select = await screen.findByRole('combobox', { name: /run/i });
  await within(select).findByText(/run-1/);
  const options = within(select).getAllByRole('option');
  expect(options.map((o) => o.getAttribute('value'))).toEqual(['run-2', 'run-1']);
  expect(select).toHaveValue('run-2');

  await user.selectOptions(select, 'run-1');
  expect(useUiStore.getState().selectedRunId).toBe('run-1');
});

test('Open sequence navigates to the sequence page of the selected run', async () => {
  const user = userEvent.setup();
  renderPicker();
  expect(screen.queryByRole('button', { name: 'Open sequence' })).not.toBeInTheDocument();

  await user.click(within(await openList(user)).getByRole('option', { name: /create-order/ }));
  await user.click(await screen.findByRole('button', { name: 'Open sequence' }));

  expect(screen.getByTestId('where')).toHaveTextContent('/sequence/run-2');
});

test('clearing the scenario removes the highlighted run', async () => {
  const user = userEvent.setup();
  renderPicker();
  await user.click(within(await openList(user)).getByRole('option', { name: /create-order/ }));

  await user.click(await screen.findByRole('button', { name: 'Clear' }));
  expect(useUiStore.getState().selectedRunId).toBeNull();
  expect(useUiStore.getState().selectedScenarioId).toBeNull();
});
