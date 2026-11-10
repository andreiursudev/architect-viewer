import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useWorkspaces } from '../api/queries';
import { AddWorkspaceDialog } from './AddWorkspaceDialog';
import { ManageWorkspacesDialog } from './ManageWorkspacesDialog';
import { useWorkspaceName } from './useWorkspaceName';

/** The header's project title as a dropdown: every workspace, plus the dialogs to add and manage them. */
export function WorkspaceSwitcher() {
  const ws = useWorkspaceName();
  const list = useWorkspaces();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [dialog, setDialog] = useState<'add' | 'manage' | null>(null);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const workspaces = list.data?.workspaces ?? [];
  const label = workspaces.find((w) => w.name === ws)?.projectName ?? 'Workspaces';

  return (
    <div className="switcher" ref={root}>
      <h1>
        <button type="button" className="switcher-button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
          {label}
          <span aria-hidden="true" className="switcher-caret">
            ▾
          </span>
        </button>
      </h1>
      {open && (
        <div className="switcher-menu" role="menu" aria-label="Workspaces">
          {workspaces.map((w) => (
            <button
              key={w.name}
              type="button"
              role="menuitemradio"
              aria-checked={w.name === ws}
              className="switcher-item"
              onClick={() => {
                setOpen(false);
                navigate(`/w/${encodeURIComponent(w.name)}/`);
              }}
            >
              <span className="switcher-tick" aria-hidden="true">
                {w.name === ws ? '✓' : ''}
              </span>
              <span>
                {w.projectName}
                {w.name !== w.projectName && <small className="switcher-name">{w.name}</small>}
              </span>
            </button>
          ))}
          <hr />
          <button
            type="button"
            role="menuitem"
            className="switcher-item"
            onClick={() => {
              setOpen(false);
              setDialog('add');
            }}
          >
            Add workspace…
          </button>
          <button
            type="button"
            role="menuitem"
            className="switcher-item"
            onClick={() => {
              setOpen(false);
              setDialog('manage');
            }}
          >
            Manage…
          </button>
        </div>
      )}
      {dialog === 'add' && <AddWorkspaceDialog onClose={() => setDialog(null)} />}
      {dialog === 'manage' && <ManageWorkspacesDialog onClose={() => setDialog(null)} />}
    </div>
  );
}
