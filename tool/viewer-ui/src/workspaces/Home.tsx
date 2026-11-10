import { Navigate } from 'react-router';
import { useWorkspaces } from '../api/queries';
import { AddWorkspaceForm } from './AddWorkspaceDialog';

/** `/`: opens the workspace that was open last, or asks for a first one when the list is empty. */
export function Home() {
  const list = useWorkspaces();
  if (list.isError) return <p className="error panel-message">Could not load the workspaces: {list.error.message}</p>;
  if (!list.data) return <p className="muted panel-message">Loading the workspaces…</p>;

  const target = list.data.lastOpened ?? list.data.workspaces[0]?.name;
  if (target !== undefined) return <Navigate to={`/w/${encodeURIComponent(target)}/`} replace />;
  return (
    <main className="add-screen">
      <h2>Add a workspace</h2>
      <AddWorkspaceForm />
    </main>
  );
}
