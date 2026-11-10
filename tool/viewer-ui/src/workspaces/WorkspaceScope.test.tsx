import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { App } from '../App';
import { createQueryClient } from '../api/queryClient';
import { viewWith } from '../board/testData';
import { useUiStore } from '../store/uiStore';

// React Flow needs a layout engine jsdom does not have; these tests are about the routes around the board
vi.mock('../board/PrimaryBoard', () => ({ PrimaryBoard: () => null }));

const entry = (name: string) => ({ name, workspace: `/w/${name}`, recordings: `/w/${name}/rec`, projectName: `Project ${name}` });

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/workspaces') return Response.json({ workspaces: [entry('a'), entry('b')], lastOpened: 'a' });
      for (const name of ['a', 'b']) {
        if (url === `/api/w/${name}/workspace`) return Response.json(viewWith({ project: { name: `Project ${name}` } }));
      }
      return new Response(null, { status: 404 });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

function renderApp(path: string) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <Link to="/w/a/">go to a</Link>
        <Link to="/w/b/">go to b</Link>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test('an unknown name shows Unknown workspace', async () => {
  renderApp('/w/nope/');
  expect(await screen.findByText('Unknown workspace')).toBeInTheDocument();
});

test("switching workspace clears the selection but keeps each workspace's note drafts", async () => {
  const user = userEvent.setup();
  renderApp('/w/a/');
  await screen.findByRole('heading', { level: 1, name: 'Project a' });
  useUiStore.getState().selectComponent('orders-service');
  useUiStore.getState().setNoteDraft('a/orders-service', 'half written');

  await user.click(screen.getByRole('link', { name: 'go to b' }));
  await screen.findByRole('heading', { level: 1, name: 'Project b' });
  expect(useUiStore.getState().selectedComponentId).toBeNull();

  await user.click(screen.getByRole('link', { name: 'go to a' }));
  await screen.findByRole('heading', { level: 1, name: 'Project a' });
  expect(useUiStore.getState().noteDrafts['a/orders-service']).toBe('half written');
});

test('queries of two workspaces do not share cache', async () => {
  const user = userEvent.setup();
  renderApp('/w/a/');
  expect(await screen.findByRole('heading', { level: 1, name: 'Project a' })).toBeInTheDocument();

  await user.click(screen.getByRole('link', { name: 'go to b' }));
  expect(await screen.findByRole('heading', { level: 1, name: 'Project b' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { level: 1, name: 'Project a' })).not.toBeInTheDocument();
});
