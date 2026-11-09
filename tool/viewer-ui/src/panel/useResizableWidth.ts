import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';

export const DEFAULT_PANEL_WIDTH = 360;
export const MIN_PANEL_WIDTH = 280;
/** The board always keeps at least this much room next to the panel. */
const MIN_BOARD_WIDTH = 320;
const KEY_STEP = 24;
const STORAGE_KEY = 'stack-viewer.side-panel-width';

/** Which panel is resized: each one remembers its own width. */
export interface ResizableOptions {
  /** The localStorage key of the remembered width. */
  storageKey?: string;
  /** The width a fresh browser starts with, and a double click on the handle restores. */
  defaultWidth?: number;
  /** The accessible name of the handle. */
  label?: string;
}

function maxWidth(): number {
  return Math.max(MIN_PANEL_WIDTH, window.innerWidth - MIN_BOARD_WIDTH);
}

export function clampWidth(width: number, max = maxWidth()): number {
  return Math.round(Math.min(Math.max(width, MIN_PANEL_WIDTH), max));
}

function storedWidth(key: string, fallback: number): number {
  try {
    const stored = Number(window.localStorage.getItem(key));
    return Number.isFinite(stored) && stored > 0 ? clampWidth(stored) : clampWidth(fallback);
  } catch {
    return clampWidth(fallback); // storage blocked: the panel still works, it just forgets its width
  }
}

function store(key: string, width: number) {
  try {
    window.localStorage.setItem(key, String(width));
  } catch {
    // remembered for this page only
  }
}

/**
 * The width of a panel docked on the right, resized by dragging a handle on its left edge (or with the arrow keys on
 * it; a double click restores the default). The width is remembered in the browser.
 */
export function useResizableWidth({
  storageKey = STORAGE_KEY,
  defaultWidth = DEFAULT_PANEL_WIDTH,
  label = 'Resize details panel',
}: ResizableOptions = {}) {
  const [width, setWidth] = useState(() => storedWidth(storageKey, defaultWidth));
  const drag = useRef<{ startX: number; startWidth: number } | null>(null);
  const [dragging, setDragging] = useState(false);

  const apply = useCallback((next: number) => {
    const clamped = clampWidth(next);
    setWidth(clamped);
    store(storageKey, clamped);
  }, [storageKey]);

  // A window made narrower would otherwise leave the board no room.
  useEffect(() => {
    const fit = () => setWidth((w) => clampWidth(w));
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);

  // While dragging, the whole page shows the resize cursor and no text gets selected.
  useEffect(() => {
    if (!dragging) return;
    document.body.classList.add('is-resizing');
    return () => document.body.classList.remove('is-resizing');
  }, [dragging]);

  const handleProps = {
    role: 'separator' as const,
    'aria-orientation': 'vertical' as const,
    'aria-label': label,
    'aria-valuenow': width,
    'aria-valuemin': MIN_PANEL_WIDTH,
    tabIndex: 0,
    onPointerDown: (e: PointerEvent<HTMLDivElement>) => {
      if (e.button !== 0) return;
      e.preventDefault();
      e.currentTarget.setPointerCapture(e.pointerId);
      drag.current = { startX: e.clientX, startWidth: width };
      setDragging(true);
    },
    onPointerMove: (e: PointerEvent<HTMLDivElement>) => {
      if (!drag.current) return;
      // the handle sits on the panel's left edge: moving left makes it wider
      apply(drag.current.startWidth + drag.current.startX - e.clientX);
    },
    onPointerUp: (e: PointerEvent<HTMLDivElement>) => {
      if (!drag.current) return;
      drag.current = null;
      setDragging(false);
      e.currentTarget.releasePointerCapture(e.pointerId);
    },
    onLostPointerCapture: () => {
      drag.current = null;
      setDragging(false);
    },
    onDoubleClick: () => apply(defaultWidth),
    onKeyDown: (e: KeyboardEvent<HTMLDivElement>) => {
      if (e.key === 'ArrowLeft') apply(width + KEY_STEP);
      else if (e.key === 'ArrowRight') apply(width - KEY_STEP);
      else return;
      e.preventDefault();
    },
  };

  return { width, dragging, handleProps };
}
