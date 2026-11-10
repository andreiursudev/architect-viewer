import { useEffect, type ReactNode } from 'react';
import { useWorkspaces } from '../api/queries';
import { useUiStore } from '../store/uiStore';
import { useWorkspaceName } from './useWorkspaceName';
import { WorkspaceSwitcher } from './WorkspaceSwitcher';

/** Renders its children for a listed workspace only; a new name starts with nothing selected. */
export function WorkspaceScope({ children }: { children: ReactNode }) {
  const ws = useWorkspaceName();
  const list = useWorkspaces();
  const resetSelection = useUiStore((s) => s.resetSelection);
  // a selection belongs to one workspace's data; note drafts are keyed by workspace and survive
  useEffect(() => resetSelection(), [ws, resetSelection]);

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
