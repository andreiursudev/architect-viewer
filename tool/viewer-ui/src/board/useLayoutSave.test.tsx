import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { layoutOf } from './testData';
import { useLayoutSave } from './useLayoutSave';

let puts: string[];

beforeEach(() => {
  puts = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') puts.push(url);
      return new Response(null, { status: 204 });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

function Saver() {
  const save = useLayoutSave('primary', 100);
  return (
    <button type="button" onClick={() => save(layoutOf({ x: { x: 1, y: 2 } }))}>
      drag
    </button>
  );
}

test('a save goes to the workspace that was open when it was scheduled, even if the route changed since', async () => {
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={['/w/a/']}>
        <Routes>
          {/* the same element for every workspace: the saver stays mounted when only the name changes */}
          <Route path="/w/:ws/*" element={<Saver />} />
        </Routes>
        <Link to="/w/b/">go to b</Link>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await user.click(screen.getByRole('button', { name: 'drag' }));
  await user.click(screen.getByRole('link', { name: 'go to b' }));

  await vi.waitFor(() => expect(puts).toEqual(['/api/w/a/boards/primary/layout']));
});
