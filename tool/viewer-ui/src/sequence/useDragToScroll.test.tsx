import { fireEvent, render, screen } from '@testing-library/react';
import { useRef } from 'react';
import { expect, test, vi } from 'vitest';
import { useDragToScroll } from './useDragToScroll';

function Pane({ onSelect = () => {} }: { onSelect?: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useDragToScroll(ref);
  return (
    <div ref={ref} data-testid="pane">
      <svg>
        <line data-testid="lifeline" />
        <g role="button" aria-label="request POST /orders" onClick={onSelect}>
          <text>POST /orders</text>
        </g>
      </svg>
      <button type="button">Collapse orders-service</button>
    </div>
  );
}

/** jsdom lays nothing out, so the pane starts scrolled to (200, 100) and keeps whatever the hook writes. */
function renderPane(onSelect?: () => void) {
  render(<Pane onSelect={onSelect} />);
  const pane = screen.getByTestId('pane');
  let left = 200;
  let top = 100;
  Object.defineProperty(pane, 'scrollLeft', { get: () => left, set: (v: number) => (left = v), configurable: true });
  Object.defineProperty(pane, 'scrollTop', { get: () => top, set: (v: number) => (top = v), configurable: true });
  return pane;
}

function drag(from: Element, dx: number, dy: number, release: Element = from) {
  fireEvent.pointerDown(from, { button: 0, pointerType: 'mouse', clientX: 50, clientY: 50 });
  fireEvent.pointerMove(window, { pointerType: 'mouse', clientX: 50 + dx, clientY: 50 + dy });
  fireEvent.pointerUp(window, { pointerType: 'mouse', clientX: 50 + dx, clientY: 50 + dy });
  fireEvent.click(release);
}

test('dragging from empty space scrolls the pane against the drag, both ways', () => {
  const pane = renderPane();
  drag(screen.getByTestId('lifeline'), -40, 30);
  expect(pane.scrollLeft).toBe(240);
  expect(pane.scrollTop).toBe(70);
});

test('a press on a message or a button does not scroll', () => {
  const pane = renderPane();
  drag(screen.getByText('POST /orders'), -40, 30);
  drag(screen.getByRole('button', { name: 'Collapse orders-service' }), -40, 30);
  expect(pane.scrollLeft).toBe(200);
  expect(pane.scrollTop).toBe(100);
});

test('a wobble under the threshold is still a click', () => {
  const onSelect = vi.fn();
  const pane = renderPane(onSelect);
  drag(screen.getByText('POST /orders'), 2, -2);
  expect(onSelect).toHaveBeenCalledOnce();
  drag(screen.getByTestId('lifeline'), 2, 2);
  expect(pane.scrollLeft).toBe(200);
  expect(pane.scrollTop).toBe(100);
});

test('letting go of a drag over a message does not select it', () => {
  const onSelect = vi.fn();
  renderPane(onSelect);
  drag(screen.getByTestId('lifeline'), -40, 0, screen.getByText('POST /orders'));
  expect(onSelect).not.toHaveBeenCalled();
  // the next plain click is a click again
  fireEvent.click(screen.getByText('POST /orders'));
  expect(onSelect).toHaveBeenCalledOnce();
});

test('a drag let go outside the pane does not swallow the next click on a message', () => {
  const onSelect = vi.fn();
  renderPane(onSelect);
  // no click follows: the mouse was released outside the pane
  fireEvent.pointerDown(screen.getByTestId('lifeline'), { button: 0, pointerType: 'mouse', clientX: 50, clientY: 50 });
  fireEvent.pointerMove(window, { pointerType: 'mouse', clientX: 400, clientY: 50 });
  fireEvent.pointerUp(window, { pointerType: 'mouse' });
  const message = screen.getByText('POST /orders');
  fireEvent.pointerDown(message, { button: 0, pointerType: 'mouse', clientX: 50, clientY: 50 });
  fireEvent.pointerUp(window, { pointerType: 'mouse' });
  fireEvent.click(message);
  expect(onSelect).toHaveBeenCalledOnce();
});

test('only the primary mouse button pans; touch scrolls natively', () => {
  const pane = renderPane();
  const lifeline = screen.getByTestId('lifeline');
  fireEvent.pointerDown(lifeline, { button: 2, pointerType: 'mouse', clientX: 50, clientY: 50 });
  fireEvent.pointerMove(window, { pointerType: 'mouse', clientX: 10, clientY: 90 });
  fireEvent.pointerUp(window, { pointerType: 'mouse' });
  fireEvent.pointerDown(lifeline, { button: 0, pointerType: 'touch', clientX: 50, clientY: 50 });
  fireEvent.pointerMove(window, { pointerType: 'touch', clientX: 10, clientY: 90 });
  fireEvent.pointerUp(window, { pointerType: 'touch' });
  expect(pane.scrollLeft).toBe(200);
  expect(pane.scrollTop).toBe(100);
});

test('the pane is marked while it pans', () => {
  const pane = renderPane();
  fireEvent.pointerDown(screen.getByTestId('lifeline'), { button: 0, pointerType: 'mouse', clientX: 50, clientY: 50 });
  expect(pane).not.toHaveClass('is-panning');
  fireEvent.pointerMove(window, { pointerType: 'mouse', clientX: 80, clientY: 50 });
  expect(pane).toHaveClass('is-panning');
  fireEvent.pointerUp(window, { pointerType: 'mouse' });
  expect(pane).not.toHaveClass('is-panning');
});
