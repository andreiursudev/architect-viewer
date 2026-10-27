import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from './api/queryClient';
import { useSaveLayout } from './api/queries';
import { comp, conn, viewWith } from './board/testData';
import { ErrorBanner } from './ErrorBanner';
import { SidePanel } from './panel/SidePanel';
import { useUiStore } from './store/uiStore';

const view = viewWith({
  components: [comp('a'), comp('b')],
  connections: [conn('a--b', 'discovered', { label: 'GET /x' })],
});

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return Response.json({ error: 'disk full' }, { status: 500 });
      if (url === '/api/workspace') return Response.json(view);
      return new Response(null, { status: 404 });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

function renderWithBanner(ui: React.ReactNode) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      {ui}
      <ErrorBanner />
    </QueryClientProvider>,
  );
}

test('a failed connection accept shows an error message', async () => {
  const user = userEvent.setup();
  useUiStore.getState().selectConnection('a--b');
  renderWithBanner(<SidePanel />);

  await user.click(await screen.findByRole('button', { name: 'Accept' }));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Updating the connection failed');
  expect(alert).toHaveTextContent('disk full');
});

function SaveLayoutButton() {
  const save = useSaveLayout('primary');
  return <button onClick={() => save.mutate({ nodes: { a: { x: 1, y: 2 } }, viewport: null })}>save layout</button>;
}

test('a failed layout save shows an error message', async () => {
  const user = userEvent.setup();
  renderWithBanner(<SaveLayoutButton />);

  await user.click(screen.getByRole('button', { name: 'save layout' }));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Saving the board layout failed');
  expect(alert).toHaveTextContent('disk full');
});

test('the error can be dismissed', async () => {
  const user = userEvent.setup();
  renderWithBanner(<SaveLayoutButton />);
  await user.click(screen.getByRole('button', { name: 'save layout' }));
  await screen.findByRole('alert');

  await user.click(screen.getByRole('button', { name: 'Dismiss' }));

  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
