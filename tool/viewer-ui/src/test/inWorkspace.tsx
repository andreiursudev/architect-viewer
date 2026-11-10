import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';

/** Renders [children] under the route `/w/test/`, as everything below `WorkspaceScope` is. Usable as a testing-library `wrapper`. */
export function InWorkspace({ children, path = '/w/test/' }: { children: ReactNode; path?: string }) {
  return (
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/w/:ws/*" element={children} />
      </Routes>
    </MemoryRouter>
  );
}
