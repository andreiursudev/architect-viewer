import { useEffect, useState } from 'react';

/** Where the viewer serves a run's screenshot. */
export function screenshotUrl(runId: string, id: string): string {
  return `/api/runs/${encodeURIComponent(runId)}/screenshots/${id}`;
}

/** A ui step's screenshot at the width of the details panel; a click asks to open it at its own size. */
export function ScreenshotImage({ runId, id, alt, onOpen }: { runId: string; id: string; alt: string; onOpen?: () => void }) {
  const [failed, setFailed] = useState(false);
  const src = screenshotUrl(runId, id);
  // a step selected after another keeps this component, so a new screenshot starts over
  useEffect(() => setFailed(false), [src]);

  if (failed) return <p className="md-note muted md-screenshot-missing">Screenshot not available</p>;
  return (
    <button type="button" className="md-screenshot" onClick={onOpen} title="Open at full size">
      <img src={src} alt={alt} onError={() => setFailed(true)} />
    </button>
  );
}

/** One ui step in the carousel: the screenshot taken once it settled. */
export interface ScreenshotSlide {
  stepId: string;
  label: string;
  screenshot: string;
}

/**
 * The screenshot of [current] at its own size over the viewer, scrollable, with the run's other ui steps one ‹ › or
 * ← → away; moving asks [onMove] to select that step. Esc, the close button or a click outside closes it.
 */
export function ScreenshotCarousel({
  runId,
  slides,
  current,
  onMove,
  onClose,
}: {
  runId: string;
  slides: ScreenshotSlide[];
  current: string;
  onMove: (stepId: string) => void;
  onClose: () => void;
}) {
  const index = slides.findIndex((s) => s.stepId === current);
  const slide = slides[index];
  const previous = slides[index - 1];
  const next = slides[index + 1];

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault(); // the overlay scrolls sideways otherwise
        const to = e.key === 'ArrowLeft' ? previous : next;
        if (to) onMove(to.stepId);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, onMove, previous, next]);

  if (!slide) return null;
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
      <p className="screenshot-caption">
        {slide.label} · {index + 1} of {slides.length}
      </p>
      <button type="button" className="btn btn-small screenshot-close" onClick={onClose}>
        Close
      </button>
      <button
        type="button"
        className="screenshot-step is-previous"
        aria-label="Previous screenshot"
        disabled={!previous}
        onClick={() => previous && onMove(previous.stepId)}
      >
        ‹
      </button>
      <img src={screenshotUrl(runId, slide.screenshot)} alt={`Screenshot after ${slide.label}`} />
      <button
        type="button"
        className="screenshot-step is-next"
        aria-label="Next screenshot"
        disabled={!next}
        onClick={() => next && onMove(next.stepId)}
      >
        ›
      </button>
    </div>
  );
}
