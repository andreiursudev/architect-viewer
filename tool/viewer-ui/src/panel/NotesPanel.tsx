import Markdown from 'react-markdown';
import { useNote, useSaveNote } from '../api/queries';
import { useUiStore } from '../store/uiStore';

/** The Markdown note of a component or connection: rendered by default, a textarea while editing. Render it with `key={targetId}`. */
export function NotesPanel({ targetId }: { targetId: string }) {
  const note = useNote(targetId);
  const save = useSaveNote(targetId);
  // The draft lives in the store, not here, so deselecting (or any re-render of the panel) cannot lose typed text.
  const draft = useUiStore((s) => s.noteDrafts[targetId] ?? null);
  const setDraft = useUiStore((s) => s.setNoteDraft);
  const editing = draft !== null;
  const text = note.data ?? '';

  const startEditing = () => {
    save.reset();
    setDraft(targetId, text);
  };

  const submit = () => {
    if (draft === null) return;
    save.mutate(draft, { onSuccess: () => setDraft(targetId, null) });
  };

  return (
    <section className="notes" aria-label="Notes">
      <header className="panel-section-header">
        <div className="notes-title">
          <h3>Notes</h3>
          {editing && draft !== text && <span className="draft-hint">unsaved</span>}
        </div>
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
            onChange={(e) => setDraft(targetId, e.target.value)}
            rows={10}
            spellCheck={false}
            placeholder="Write Markdown…"
          />
          {save.isError && <p className="error">Could not save: {save.error.message}</p>}
          <div className="button-row">
            <button type="button" className="btn btn-primary" onClick={submit} disabled={save.isPending}>
              Save
            </button>
            <button type="button" className="btn" onClick={() => setDraft(targetId, null)} disabled={save.isPending}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
