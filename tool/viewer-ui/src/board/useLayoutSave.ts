import { useCallback } from 'react';
import { useSaveLayout } from '../api/queries';
import type { BoardLayout } from '../api/types';
import { useWorkspaceName } from '../workspaces/useWorkspaceName';
import { useDebouncedSave } from './useDebouncedSave';

/**
 * Saves a board's layout, debounced (see [useDebouncedSave]). Each layout is bound to the workspace open when it was
 * scheduled, so a save still pending when the workspace changes never lands in the next one's layout file.
 */
export function useLayoutSave(board: string, delayMs: number): (layout: BoardLayout) => void {
  const ws = useWorkspaceName();
  const saveLayout = useSaveLayout(board);
  const schedule = useDebouncedSave<{ ws: string; layout: BoardLayout }>(saveLayout.mutate, delayMs);
  return useCallback((layout: BoardLayout) => schedule({ ws, layout }), [schedule, ws]);
}
