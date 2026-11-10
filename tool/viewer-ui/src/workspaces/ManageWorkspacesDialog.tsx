import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { useRemoveWorkspace, useRenameWorkspace, useWorkspaces } from '../api/queries';
import { reasonOf } from './AddWorkspaceDialog';
import { Dialog } from './Dialog';

/** The list with a rename and a remove per workspace; removing only drops it from the list, no files are deleted. */
export function ManageWorkspacesDialog({ onClose }: { onClose: () => void }) {
  const list = useWorkspaces();
  const current = useParams().ws;
  const navigate = useNavigate();
  const location = useLocation();
  const rename = useRenameWorkspace();
  const remove = useRemoveWorkspace();
  const [renaming, setRenaming] = useState<{ ws: string; draft: string } | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);

  async function saveRename(ws: string, draft: string) {
    const name = draft.trim();
    try {
      await rename.mutateAsync({ ws, name });
    } catch {
      return; // rename.error is shown
    }
    setRenaming(null);
    if (ws === current) {
      // the same page under the new name
      const from = `/w/${encodeURIComponent(ws)}`;
      const rest = location.pathname.startsWith(from) ? location.pathname.slice(from.length) : '/';
      navigate({ pathname: `/w/${encodeURIComponent(name)}${rest}`, search: location.search, hash: location.hash });
    }
  }

  async function confirmRemove(ws: string) {
    try {
      await remove.mutateAsync(ws);
    } catch {
      return; // remove.error is shown
    }
    setRemoving(null);
    if (ws === current) {
      navigate('/');
      onClose();
    }
  }

  const error = rename.isError ? rename.error : remove.isError ? remove.error : null;
  return (
    <Dialog label="Manage workspaces" onClose={onClose}>
      {!list.data ? (
        <p className="muted">Loading the workspaces…</p>
      ) : (
        <ul className="manage-list">
          {list.data.workspaces.map((w) => (
            <li key={w.name}>
              {renaming?.ws === w.name ? (
                <form
                  className="manage-row"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void saveRename(w.name, renaming.draft);
                  }}
                >
                  <label>
                    <span className="visually-hidden">New name</span>
                    <input value={renaming.draft} onChange={(e) => setRenaming({ ws: w.name, draft: e.target.value })} />
                  </label>
                  <button type="submit" className="btn btn-small btn-primary" disabled={rename.isPending}>
                    Save
                  </button>
                  <button type="button" className="btn btn-small" onClick={() => setRenaming(null)}>
                    Cancel
                  </button>
                </form>
              ) : (
                <div className="manage-row">
                  <span className="manage-name">
                    <strong>{w.projectName}</strong>
                    <code>{w.name}</code>
                  </span>
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => {
                      rename.reset();
                      setRenaming({ ws: w.name, draft: w.name });
                    }}
                  >
                    Rename
                  </button>
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => {
                      remove.reset();
                      setRemoving(w.name);
                    }}
                  >
                    Remove
                  </button>
                </div>
              )}
              {removing === w.name && (
                <div className="manage-confirm">
                  <p>Remove {w.name} from the list? No files are deleted.</p>
                  <div className="button-row">
                    <button
                      type="button"
                      className="btn btn-small btn-primary"
                      disabled={remove.isPending}
                      onClick={() => void confirmRemove(w.name)}
                    >
                      Remove from list
                    </button>
                    <button type="button" className="btn btn-small" onClick={() => setRemoving(null)}>
                      Cancel
                    </button>
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p className="error" role="alert">
          {reasonOf(error)}
        </p>
      )}
      <div className="button-row">
        <button type="button" className="btn" onClick={onClose}>
          Close
        </button>
      </div>
    </Dialog>
  );
}
