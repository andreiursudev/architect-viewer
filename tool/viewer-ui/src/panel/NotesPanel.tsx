import { useState } from 'react';
import Markdown from 'react-markdown';
import { useNote, useSaveNote } from '../api/queries';

/** The Markdown note of a component or connection: rendered by default, a textarea while editing. Render it with `key={targetId}`. */
export function NotesPanel({ targetId }: { targetId: string }) {
  const note = useNote(targetId);
  const save = useSaveNote(targetId);
  const [draft, setDraft] = useState<string | null>(null);
  const editing = draft !== null;
  const text = note.data ?? '';

  const startEditing = () => {
    save.reset();
    setDraft(text);
  };

  const submit = () => {
    if (draft === null) return;
    save.mutate(draft, { onSuccess: () => setDraft(null) });
  };

  return (
    <section className="notes" aria-label="Notes">
      <header className="panel-section-header">
        <h3>Notes</h3>
        {!editing && note.isSuccess && (
          <button type="button" className="btn btn-quiet" onClick={startEditing}>
            Edit
          </button>
        )}
      </header>

      {note.isPending && <p className="muted">Loading…</p>}
      {note.isError && <p className="error">Could not load the note: {note.error.message}</p>}

      {note.isSuccess && !editing && (
        <>
          {text.trim() === '' ? (
            <p className="muted">No notes yet.</p>
          ) : (
            <div className="markdown">
              <Markdown>{text}</Markdown>
            </div>
          )}
        </>
      )}

      {editing && (
        <div className="notes-editor">
          <textarea
            aria-label="Note"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            rows={10}
            spellCheck={false}
            placeholder="Write Markdown…"
          />
          {save.isError && <p className="error">Could not save: {save.error.message}</p>}
          <div className="button-row">
            <button type="button" className="btn btn-primary" onClick={submit} disabled={save.isPending}>
              Save
            </button>
            <button type="button" className="btn" onClick={() => setDraft(null)} disabled={save.isPending}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
