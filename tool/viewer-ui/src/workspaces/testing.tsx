import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';

export const entry = (name: string, projectName = `Project ${name}`) => ({
  name,
  workspace: `/w/${name}`,
  recordings: `/w/${name}/rec`,
  projectName,
});

export type Entry = ReturnType<typeof entry>;
export type Call = { method: string; url: string; body: unknown };

/** Stubs `fetch` with a mutable list of workspaces; every non-GET call is recorded and answered by [answer]. */
export function stubWorkspaces(
  initial: Entry[],
  answer: (call: Call, list: Entry[]) => Response = () => new Response(null, { status: 204 }),
) {
  const list = [...initial];
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (method === 'GET') {
        if (url === '/api/workspaces') return Response.json({ workspaces: list, lastOpened: list[0]?.name ?? null });
        return new Response(null, { status: 404 });
      }
      const call = { method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined };
      calls.push(call);
      return answer(call, list);
    }),
  );
  return { calls, list };
}

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

/** [ui] rendered at [path] under `/w/:ws/*` (and anywhere else), with the location on show. */
export function renderAt(path: string, ui: ReactNode) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/w/:ws/*" element={ui} />
          <Route path="*" element={ui} />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
