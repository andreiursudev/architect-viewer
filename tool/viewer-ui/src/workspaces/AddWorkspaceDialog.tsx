import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { useAddWorkspace } from '../api/queries';
import { Dialog } from './Dialog';

/** The reason the server gave, without the request line the client puts before it. */
export function reasonOf(error: unknown): string {
  if (error instanceof ApiError) return error.detail || error.message;
  return error instanceof Error ? error.message : String(error);
}

/** Folder, name and recordings folder; the last two may stay empty to use the registry's defaults. Opens the new workspace. */
export function AddWorkspaceForm({ onDone, onCancel }: { onDone?: () => void; onCancel?: () => void }) {
  const navigate = useNavigate();
  const add = useAddWorkspace();
  const [folder, setFolder] = useState('');
  const [name, setName] = useState('');
  const [recordings, setRecordings] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const created = await add.mutateAsync({
        folder: folder.trim(),
        name: name.trim() || null,
        recordings: recordings.trim() || null,
      });
      navigate(`/w/${encodeURIComponent(created.name)}/`);
      onDone?.();
    } catch {
      // add.error is shown below; the form stays open for another try
    }
  }

  return (
    <form className="dialog-form" onSubmit={submit}>
      <label>
        Folder
        <input value={folder} onChange={(e) => setFolder(e.target.value)} required placeholder="Path of the workspace folder" />
      </label>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Optional; taken from the folder" />
      </label>
      <label>
        Recordings folder
        <input value={recordings} onChange={(e) => setRecordings(e.target.value)} placeholder="Optional; the default location" />
      </label>
      {add.isError && (
        <p className="error" role="alert">
          {reasonOf(add.error)}
        </p>
      )}
      <div className="button-row">
        <button type="submit" className="btn btn-primary" disabled={add.isPending || folder.trim() === ''}>
          Add
        </button>
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}

export function AddWorkspaceDialog({ onClose }: { onClose: () => void }) {
  return (
    <Dialog label="Add workspace" onClose={onClose}>
      <AddWorkspaceForm onDone={onClose} onCancel={onClose} />
    </Dialog>
  );
}
