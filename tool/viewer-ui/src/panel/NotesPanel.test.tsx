import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { NotesPanel } from './NotesPanel';

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') return new Response(null, { status: 204 });
    return new Response('# Title\n\nbody text', { status: 200, headers: { 'Content-Type': 'text/markdown' } });
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => vi.unstubAllGlobals());

function renderPanel(targetId = 'orders-service') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NotesPanel targetId={targetId} />
    </QueryClientProvider>,
  );
}

test('notes panel renders markdown and saves edits', async () => {
  const user = userEvent.setup();
  renderPanel();

  expect(await screen.findByRole('heading', { level: 1, name: 'Title' })).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Edit' }));
  const box = screen.getByRole('textbox');
  expect(box).toHaveValue('# Title\n\nbody text');
  await user.clear(box);
  await user.type(box, 'new text');
  await user.click(screen.getByRole('button', { name: 'Save' }));

  await waitFor(() => {
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT');
    expect(put).toBeDefined();
    expect(put![0]).toBe('/api/notes/orders-service');
    expect(put![1].body).toBe('new text');
    expect(new Headers(put![1].headers).get('Content-Type')).toBe('text/markdown');
  });
});

test('a missing note shows an empty state and can be created', async () => {
  fetchMock.mockImplementation(async (_url: string, init?: RequestInit) =>
    init?.method === 'PUT' ? new Response(null, { status: 204 }) : new Response(null, { status: 404 }),
  );
  const user = userEvent.setup();
  renderPanel();

  expect(await screen.findByText('No notes yet.')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Edit' }));
  expect(screen.getByRole('textbox')).toHaveValue('');
});
