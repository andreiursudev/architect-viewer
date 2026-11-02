import { useEffect, useState } from 'react';

/** Where the viewer serves a run's screenshot. */
export function screenshotUrl(runId: string, id: string): string {
  return `/api/runs/${encodeURIComponent(runId)}/screenshots/${id}`;
}

/** A ui step's screenshot at the width of the details panel; a click opens it at its own size. */
export function ScreenshotImage({ runId, id, alt }: { runId: string; id: string; alt: string }) {
  const [open, setOpen] = useState(false);
  const [failed, setFailed] = useState(false);
  const src = screenshotUrl(runId, id);
  // a step selected after another keeps this component, so a new screenshot starts over
  useEffect(() => {
    setOpen(false);
    setFailed(false);
  }, [src]);

  if (failed) return <p className="md-note muted md-screenshot-missing">Screenshot not available</p>;
  return (
    <>
      <button type="button" className="md-screenshot" onClick={() => setOpen(true)} title="Open at full size">
        <img src={src} alt={alt} onError={() => setFailed(true)} />
      </button>
      {open && <ScreenshotOverlay src={src} alt={alt} onClose={() => setOpen(false)} />}
    </>
  );
}

/** The screenshot at its own size over the viewer, scrollable; Esc, the close button or a click outside closes it. */
function ScreenshotOverlay({ src, alt, onClose }: { src: string; alt: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="screenshot-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Screenshot"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <button type="button" className="btn btn-small screenshot-close" onClick={onClose}>
        Close
      </button>
      <img src={src} alt={alt} />
    </div>
  );
}
