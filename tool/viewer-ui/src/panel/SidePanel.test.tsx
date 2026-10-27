import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { comp, viewWith } from '../board/testData';
import { useUiStore } from '../store/uiStore';
import { SidePanel } from './SidePanel';

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/workspace') return Response.json(viewWith({ components: [comp('a'), comp('b')] }));
      return new Response('saved note', { status: 200, headers: { 'Content-Type': 'text/markdown' } });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

test('an unsaved note draft survives deselecting and reselecting', async () => {
  const user = userEvent.setup();
  useUiStore.getState().selectComponent('a');
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SidePanel />
    </QueryClientProvider>,
  );

  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  await user.clear(screen.getByRole('textbox'));
  await user.type(screen.getByRole('textbox'), 'half written');

  act(() => useUiStore.getState().selectComponent(null)); // a click on the empty canvas
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

  act(() => useUiStore.getState().selectComponent('b'));
  await screen.findByRole('button', { name: 'Edit' });
  expect(screen.queryByText('half written')).not.toBeInTheDocument(); // b has no draft

  act(() => useUiStore.getState().selectComponent('a'));
  expect(await screen.findByRole('textbox')).toHaveValue('half written');
  expect(screen.getByText('unsaved')).toBeInTheDocument();
});
