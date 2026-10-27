import { useUiStore } from './store/uiStore';

/** Failed actions (see createQueryClient), shown until dismissed. */
export function ErrorBanner() {
  const errors = useUiStore((s) => s.errors);
  const dismiss = useUiStore((s) => s.dismissError);
  if (errors.length === 0) return null;
  return (
    <div className="error-banner" role="alert">
      {errors.map((e) => (
        <p key={e.id}>
          <span>{e.message}</span>
          <button type="button" className="btn btn-quiet" aria-label="Dismiss" onClick={() => dismiss(e.id)}>
            ✕
          </button>
        </p>
      ))}
    </div>
  );
}
