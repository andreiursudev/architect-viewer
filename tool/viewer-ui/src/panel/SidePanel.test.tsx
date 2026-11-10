import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { comp, conn, viewWith } from '../board/testData';
import { InWorkspace } from '../test/inWorkspace';
import { useUiStore } from '../store/uiStore';
import { SidePanel } from './SidePanel';

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/w/test/workspace') {
        return Response.json(
          viewWith({ components: [comp('a'), comp('b')], connections: [conn('a--b', 'accepted', { unverified: true })] }),
        );
      }
      return new Response('saved note', { status: 200, headers: { 'Content-Type': 'text/markdown' } });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  useUiStore.getState().selectConnection(null);
  useUiStore.getState().selectComponent(null);
});

test('an unverified connection says it has no current evidence', async () => {
  useUiStore.getState().selectConnection('a--b');
  render(
    <QueryClientProvider client={new QueryClient()}>
      <InWorkspace>
        <SidePanel />
      </InWorkspace>
    </QueryClientProvider>,
  );

  expect(
    await screen.findByText(
      'No current evidence for this connection: neither the latest run of a scenario nor a reference recording goes through it.',
    ),
  ).toBeInTheDocument();
});

test('an unsaved note draft survives deselecting and reselecting', async () => {
  const user = userEvent.setup();
  useUiStore.getState().selectComponent('a');
  render(
    <QueryClientProvider client={new QueryClient()}>
      <InWorkspace>
        <SidePanel />
      </InWorkspace>
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

test('a component lists its connections by direction, each with its endpoints', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url === '/api/w/test/workspace'
        ? Response.json(
            viewWith({
              components: [comp('a'), comp('b'), comp('c')],
              connections: [
                conn('a--b', 'accepted', { label: 'POST /b/register, GET /b/items/{id}' }),
                conn('c--a', 'discovered', { label: 'GET /a' }),
              ],
            }),
          )
        : new Response('', { status: 404 }),
    ),
  );
  const user = userEvent.setup();
  useUiStore.getState().selectComponent('a');
  render(
    <QueryClientProvider client={new QueryClient()}>
      <InWorkspace>
        <SidePanel />
      </InWorkspace>
    </QueryClientProvider>,
  );

  const outgoing = await screen.findByRole('region', { name: 'Outgoing connections' });
  expect(within(outgoing).getAllByRole('listitem').filter((li) => li.classList.contains('endpoint')).map((li) => li.textContent)).toEqual([
    'GET/b/items/{id}',
    'POST/b/register',
  ]);
  const incoming = screen.getByRole('region', { name: 'Incoming connections' });
  expect(within(incoming).getByText('/a')).toBeInTheDocument();

  await user.click(within(outgoing).getByRole('button', { name: /b/ }));
  expect(useUiStore.getState().selectedConnectionId).toBe('a--b');
});

test('the panel can be resized from its left edge and remembers its width', () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <InWorkspace>
        <SidePanel />
      </InWorkspace>
    </QueryClientProvider>,
  );
  const handle = screen.getByRole('separator', { name: 'Resize details panel' });
  const panel = screen.getByRole('complementary', { name: 'Details' });
  const start = parseInt(panel.style.width, 10);

  fireEvent.keyDown(handle, { key: 'ArrowLeft' });
  expect(parseInt(panel.style.width, 10)).toBe(start + 24);
  expect(window.localStorage.getItem('stack-viewer.side-panel-width')).toBe(String(start + 24));

  fireEvent.doubleClick(handle);
  expect(panel.style.width).toBe('360px');
});
