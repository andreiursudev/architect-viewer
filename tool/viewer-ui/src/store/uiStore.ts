import { create } from 'zustand';

/** Client-side view state shared across the board, the side panel and (later) the scenario and sequence views. */
export interface UiState {
  selectedComponentId: string | null;
  selectedConnectionId: string | null;
  /** The scenario the run picker is showing; [selectedRunId] is the run whose path is highlighted on the board. */
  selectedScenarioId: string | null;
  selectedRunId: string | null;
  hoveredConnectionId: string | null;
  /** Unsaved note drafts by target id: they survive deselecting and reselecting, so a stray click loses nothing. */
  noteDrafts: Record<string, string>;
  /** Failed background actions (accepting, saving the layout...) waiting to be dismissed. */
  errors: { id: number; message: string }[];
  selectComponent: (id: string | null) => void;
  selectConnection: (id: string | null) => void;
  selectScenario: (scenarioId: string | null, runId: string | null) => void;
  selectRun: (id: string | null) => void;
  hoverConnection: (id: string | null) => void;
  setNoteDraft: (targetId: string, draft: string | null) => void;
  reportError: (message: string) => void;
  dismissError: (id: number) => void;
}

let nextErrorId = 1;

export const useUiStore = create<UiState>((set) => ({
  selectedComponentId: null,
  selectedConnectionId: null,
  selectedScenarioId: null,
  selectedRunId: null,
  hoveredConnectionId: null,
  noteDrafts: {},
  errors: [],
  // a component and a connection are never selected together: the side panel shows one of them
  selectComponent: (id) => set({ selectedComponentId: id, selectedConnectionId: null }),
  selectConnection: (id) => set({ selectedConnectionId: id, selectedComponentId: null }),
  selectScenario: (scenarioId, runId) => set({ selectedScenarioId: scenarioId, selectedRunId: runId }),
  selectRun: (id) => set({ selectedRunId: id }),
  hoverConnection: (id) => set({ hoveredConnectionId: id }),
  setNoteDraft: (targetId, draft) =>
    set((s) => {
      const { [targetId]: _dropped, ...rest } = s.noteDrafts;
      return { noteDrafts: draft === null ? rest : { ...rest, [targetId]: draft } };
    }),
  // the same failure repeating (a layout save after every drag) shows once
  reportError: (message) =>
    set((s) => (s.errors.some((e) => e.message === message) ? s : { errors: [...s.errors, { id: nextErrorId++, message }] })),
  dismissError: (id) => set((s) => ({ errors: s.errors.filter((e) => e.id !== id) })),
}));
