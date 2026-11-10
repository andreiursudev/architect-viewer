import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { App } from '../App';
import { createQueryClient } from '../api/queryClient';
import { viewWith } from '../board/testData';
import { entry } from './testing';

vi.mock('../board/PrimaryBoard', () => ({ PrimaryBoard: () => null }));

afterEach(() => vi.unstubAllGlobals());

let list: ReturnType<typeof entry>[];
let sawUnknown: boolean;
let observer: MutationObserver;

beforeEach(() => {
  list = [entry('sam'), entry('other')];
  sawUnknown = false;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (method === 'PATCH') {
        const name = (JSON.parse(String(init?.body)) as { name: string }).name;
        list[0] = entry(name);
        return Response.json(list[0]);
      }
      if (method === 'DELETE') {
        list = list.slice(1);
        return new Response(null, { status: 204 });
      }
      if (url === '/api/workspaces') return Response.json({ workspaces: list, lastOpened: list[0]?.name ?? null });
      if (/^\/api\/w\/[^/]+\/workspace$/.test(url)) return Response.json(viewWith({}));
      return new Response(null, { status: 404 });
    }),
  );
  // every added node is checked, so a single frame showing the unknown page fails the test
  observer = new MutationObserver((records) => {
    // the records, not the DOM now: a flash that is gone by the time the callback runs still counts
    for (const r of records) {
      for (const n of r.addedNodes) if (n.textContent?.includes('Unknown workspace')) sawUnknown = true;
    }
  });
  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
});

afterEach(() => observer.disconnect());

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

// as main.tsx: without transitions a navigation renders in the same batch as the cache write
function renderApp(path: string) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]} useTransitions={false}>
        <App />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function openManage(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /^Project sam/ }));
  await user.click(screen.getByRole('menuitem', { name: 'Manage…' }));
  return screen.getByRole('dialog', { name: 'Manage workspaces' });
}

test('renaming the current workspace never shows Unknown workspace', async () => {
  const user = userEvent.setup();
  renderApp('/w/sam/sequence/run-1');
  const dialog = await openManage(user);

  const row = within(dialog).getByText('sam').closest('li') as HTMLElement;
  await user.click(within(row).getByRole('button', { name: 'Rename' }));
  const input = within(row).getByLabelText('New name');
  await user.clear(input);
  await user.type(input, 'sam2');
  await user.click(within(row).getByRole('button', { name: 'Save' }));

  await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/w/sam2/sequence/run-1'));
  expect(screen.queryByText('Unknown workspace')).toBeNull();
  // the dialog is still open, showing the new name, and the refetch has settled
  await vi.waitFor(() => expect(screen.getByRole('dialog', { name: 'Manage workspaces' })).toHaveTextContent('sam2'));
  expect(screen.queryByText('Unknown workspace')).toBeNull();
  expect(sawUnknown).toBe(false);
});

test('removing the current workspace goes to / without showing Unknown workspace', async () => {
  const user = userEvent.setup();
  renderApp('/w/sam/');
  const dialog = await openManage(user);

  const row = within(dialog).getByText('sam').closest('li') as HTMLElement;
  await user.click(within(row).getByRole('button', { name: 'Remove' }));
  await user.click(screen.getByRole('button', { name: 'Remove from list' }));

  // Home then opens what is left
  await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/w/other/'));
  expect(screen.queryByText('Unknown workspace')).toBeNull();
  expect(sawUnknown).toBe(false);
});
