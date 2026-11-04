import { useEffect, type RefObject } from 'react';

/** What a press must not start a pan on: anything clickable, a message arrow and a thumbnail among them. */
const CLICKABLE = 'button, a, input, select, textarea, label, [role="button"]';
/** How far the mouse moves before a press is a drag rather than a click. */
const THRESHOLD = 3;

/**
 * Lets the mouse drag the scrolled content of [ref] around, both ways, from any spot where there is nothing to click.
 * A press that moves less than `THRESHOLD` pixels stays a click; the click that ends a drag is swallowed. Touch keeps
 * its native scrolling, and the scrollbars and the wheel work as before. While it pans the element has `is-panning`.
 */
export function useDragToScroll(ref: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const pane = ref.current;
    if (!pane) return;
    let dragged = false;

    const onPointerDown = (down: PointerEvent) => {
      // every press starts afresh: a drag let go outside the pane had no click to swallow
      dragged = false;
      if (down.button !== 0 || down.pointerType === 'touch') return;
      if (down.target instanceof Element && down.target.closest(CLICKABLE)) return;
      const startX = down.clientX;
      const startY = down.clientY;
      const startLeft = pane.scrollLeft;
      const startTop = pane.scrollTop;

      const onMove = (move: PointerEvent) => {
        const dx = move.clientX - startX;
        const dy = move.clientY - startY;
        if (!dragged && Math.abs(dx) <= THRESHOLD && Math.abs(dy) <= THRESHOLD) return;
        if (!dragged) {
          dragged = true;
          pane.classList.add('is-panning');
          window.getSelection()?.removeAllRanges();
        }
        pane.scrollLeft = startLeft - dx;
        pane.scrollTop = startTop - dy;
      };
      const onUp = () => {
        pane.classList.remove('is-panning');
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
        window.removeEventListener('pointercancel', onUp);
      };
      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
      window.addEventListener('pointercancel', onUp);
    };

    // capture: the click ending a drag must not reach the message under the mouse (React handles clicks at the root)
    const onClick = (click: MouseEvent) => {
      if (!dragged) return;
      dragged = false;
      click.stopPropagation();
      click.preventDefault();
    };

    pane.addEventListener('pointerdown', onPointerDown);
    pane.addEventListener('click', onClick, true);
    return () => {
      pane.removeEventListener('pointerdown', onPointerDown);
      pane.removeEventListener('click', onClick, true);
    };
  }, [ref]);
}
