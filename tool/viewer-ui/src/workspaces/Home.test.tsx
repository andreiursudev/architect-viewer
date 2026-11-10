import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { Home } from './Home';

afterEach(() => vi.unstubAllGlobals());

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

function renderHome(list: unknown) {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json(list)));
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

test('redirects to the last opened workspace', async () => {
  renderHome({
    workspaces: [
      { name: 'sam', workspace: '/w/sam', recordings: '/w/sam/rec', projectName: 'Sam' },
      { name: 'other', workspace: '/w/other', recordings: '/w/other/rec', projectName: 'Other' },
    ],
    lastOpened: 'sam',
  });
  expect(await screen.findByTestId('where')).toHaveTextContent('/w/sam/');
});

test('shows the add screen when the list is empty', async () => {
  renderHome({ workspaces: [], lastOpened: null });
  expect(await screen.findByRole('heading', { name: 'Add a workspace' })).toBeInTheDocument();
});
