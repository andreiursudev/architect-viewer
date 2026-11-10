import { useEffect, useRef, type ReactNode } from 'react';

/** A modal box over the page: labelled for screen readers, closed by Escape or a click on the backdrop. */
export function Dialog({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    box.current?.querySelector<HTMLElement>('input, button')?.focus();
  }, []);
  return (
    <div
      className="dialog-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={box}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={label}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.stopPropagation();
            onClose();
          }
        }}
      >
        <h2>{label}</h2>
        {children}
      </div>
    </div>
  );
}
