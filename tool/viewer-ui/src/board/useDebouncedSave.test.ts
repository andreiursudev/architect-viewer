import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { useDebouncedSave } from './useDebouncedSave';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

test('saves the latest value once the delay has passed', () => {
  const save = vi.fn();
  const { result } = renderHook(() => useDebouncedSave(save, 500));
  result.current('first');
  vi.advanceTimersByTime(300);
  result.current('second');
  vi.advanceTimersByTime(499);
  expect(save).not.toHaveBeenCalled();
  vi.advanceTimersByTime(1);
  expect(save).toHaveBeenCalledOnce();
  expect(save).toHaveBeenCalledWith('second');
});

test('a pending save is flushed, not dropped, when the component unmounts', () => {
  const save = vi.fn();
  const { result, unmount } = renderHook(() => useDebouncedSave(save, 500));
  result.current('dragged');
  unmount();
  expect(save).toHaveBeenCalledOnce();
  expect(save).toHaveBeenCalledWith('dragged');
  vi.advanceTimersByTime(1000);
  expect(save).toHaveBeenCalledOnce();
});

test('nothing is saved on unmount when nothing is pending', () => {
  const save = vi.fn();
  const { result, unmount } = renderHook(() => useDebouncedSave(save, 500));
  result.current('x');
  vi.advanceTimersByTime(500);
  unmount();
  expect(save).toHaveBeenCalledOnce();
});

test('the latest save function is used', () => {
  const first = vi.fn();
  const second = vi.fn();
  const { result, rerender } = renderHook(({ save }) => useDebouncedSave(save, 500), { initialProps: { save: first } });
  result.current('v');
  rerender({ save: second });
  vi.advanceTimersByTime(500);
  expect(first).not.toHaveBeenCalled();
  expect(second).toHaveBeenCalledWith('v');
});
