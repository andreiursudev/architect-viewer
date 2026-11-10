import type { ReactNode } from 'react';
import { useWorkspaces } from '../api/queries';
import { useWorkspaceName } from './useWorkspaceName';
import { WorkspaceSwitcher } from './WorkspaceSwitcher';

/** Renders its children for a listed workspace only. */
export function WorkspaceScope({ children }: { children: ReactNode }) {
  const ws = useWorkspaceName();
  const list = useWorkspaces();

  if (list.isError) return <p className="error panel-message">Could not load the workspaces: {list.error.message}</p>;
  if (!list.data) return <p className="muted panel-message">Loading the workspaces…</p>;
  if (!list.data.workspaces.some((w) => w.name === ws)) {
    return (
      <>
        <header className="app-header">
          <WorkspaceSwitcher />
        </header>
        <p className="panel-message">Unknown workspace</p>
      </>
    );
  }
  return <>{children}</>;
}
