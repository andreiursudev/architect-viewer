import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { App } from '../App';
import { createQueryClient } from '../api/queryClient';
import { viewWith } from '../board/testData';
import { useUiStore } from '../store/uiStore';
import { entry } from './testing';

const mounts = vi.hoisted(() => ({ board: 0 }));

// React Flow needs a layout engine jsdom does not have: this board mounts like the real one and saves through the same hook
vi.mock('../board/PrimaryBoard', async () => {
  const { useEffect } = await import('react');
  const { useLayoutSave } = await import('../board/useLayoutSave');
  const { layoutOf } = await import('../board/testData');
  return {
    PrimaryBoard: function FakeBoard() {
      useEffect(() => {
        mounts.board++;
      }, []);
      const save = useLayoutSave('primary', 500);
      return (
        <button type="button" onClick={() => save(layoutOf({ 'e2e-client': { x: 11, y: 22 } }))}>
          drag
        </button>
      );
    },
  };
});

let requests: { method: string; url: string }[];

beforeEach(() => {
  mounts.board = 0;
  requests = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      requests.push({ method, url });
      if (method === 'PUT') return new Response(null, { status: 204 });
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
        <Link to="/w/b/">go to b</Link>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test("switching workspace remounts the workspace's pages but not the header", async () => {
  const user = userEvent.setup();
  renderApp('/w/a/');
  await screen.findByRole('heading', { level: 1, name: 'Project a' });
  const header = screen.getByRole('banner');
  expect(mounts.board).toBe(1);

  await user.click(screen.getByRole('link', { name: 'go to b' }));
  await screen.findByRole('heading', { level: 1, name: 'Project b' });
  // the board of b starts from b's data, never from the nodes the board of a had
  expect(mounts.board).toBe(2);
  expect(screen.getByRole('banner')).toBe(header);
});

test('a layout save pending when switching workspace goes to the workspace it was made in', async () => {
  const user = userEvent.setup();
  renderApp('/w/a/');
  await screen.findByRole('heading', { level: 1, name: 'Project a' });

  await user.click(screen.getByRole('button', { name: 'drag' }));
  await user.click(screen.getByRole('link', { name: 'go to b' }));
  await screen.findByRole('heading', { level: 1, name: 'Project b' });

  await vi.waitFor(() => expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(1));
  // nothing else turns up once the debounce would have run out
  await new Promise((resolve) => setTimeout(resolve, 600));
  expect(requests.filter((r) => r.method === 'PUT')).toEqual([{ method: 'PUT', url: '/api/w/a/boards/primary/layout' }]);
});

test("switching workspace sends no request for the old workspace's selected scenario or run", async () => {
  const user = userEvent.setup();
  renderApp('/w/a/');
  await screen.findByRole('heading', { level: 1, name: 'Project a' });
  useUiStore.getState().selectScenario('checkout-a', 'run-a1');
  await vi.waitFor(() => expect(requests.some((r) => r.url === '/api/w/a/runs/run-a1/flow')).toBe(true));

  await user.click(screen.getByRole('link', { name: 'go to b' }));
  await screen.findByRole('heading', { level: 1, name: 'Project b' });
  await new Promise((resolve) => setTimeout(resolve, 50));

  const toB = requests.filter((r) => r.url.startsWith('/api/w/b/')).map((r) => r.url);
  expect(toB.filter((url) => url.includes('checkout-a') || url.includes('run-a1'))).toEqual([]);
  expect(useUiStore.getState().selectedRunId).toBeNull();
});
