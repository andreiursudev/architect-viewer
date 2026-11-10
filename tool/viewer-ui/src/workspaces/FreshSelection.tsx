import { useLayoutEffect, useState, type ReactNode } from 'react';
import { useUiStore } from '../store/uiStore';

/**
 * Clears the selection, then renders its children. Mounted with `key={ws}`, a workspace's pages never render (and never
 * fetch) with a scenario, run or component selected in the previous workspace. The clearing runs in a layout effect, before
 * the children's first render and before anything is painted; note drafts are keyed by workspace and survive.
 */
export function FreshSelection({ children }: { children: ReactNode }) {
  const resetSelection = useUiStore((s) => s.resetSelection);
  const [cleared, setCleared] = useState(false);
  useLayoutEffect(() => {
    resetSelection();
    setCleared(true);
  }, [resetSelection]);
  return cleared ? <>{children}</> : null;
}
