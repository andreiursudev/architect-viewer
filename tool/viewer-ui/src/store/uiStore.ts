import { create } from 'zustand';

/** Client-side view state shared across the board, the side panel and (later) the scenario and sequence views. */
export interface UiState {
  selectedComponentId: string | null;
  selectedConnectionId: string | null;
  /**
   * A step of the highlighted run, picked by its number on the board: the side panel then shows what it carried, as the
   * sequence view's details panel does.
   */
  selectedStepId: string | null;
  /** The scenario the run picker is showing; [selectedRunId] is the run whose path is highlighted on the board. */
  selectedScenarioId: string | null;
  selectedRunId: string | null;
  hoveredConnectionId: string | null;
  /**
   * Every connection is drawn on the board. Off by default: the board then draws only the path of the selected run, or
   * the connections of the selected component, so a large workspace does not drown in lines.
   */
  showAllConnections: boolean;
  /** Unsaved note drafts by `${workspace}/${target id}`: they survive deselecting and reselecting, so a stray click loses nothing. */
  noteDrafts: Record<string, string>;
  /** Failed background actions (accepting, saving the layout...) waiting to be dismissed. */
  errors: { id: number; message: string }[];
  selectComponent: (id: string | null) => void;
  selectConnection: (id: string | null) => void;
  selectStep: (id: string | null) => void;
  selectScenario: (scenarioId: string | null, runId: string | null) => void;
  selectRun: (id: string | null) => void;
  hoverConnection: (id: string | null) => void;
  setShowAllConnections: (show: boolean) => void;
  setNoteDraft: (draftKey: string, draft: string | null) => void;
  /** Switching workspace lets go of everything selected in the old one; drafts and errors stay. */
  resetSelection: () => void;
  reportError: (message: string) => void;
  dismissError: (id: number) => void;
}

let nextErrorId = 1;

export const useUiStore = create<UiState>((set) => ({
  selectedComponentId: null,
  selectedConnectionId: null,
  selectedStepId: null,
  selectedScenarioId: null,
  selectedRunId: null,
  hoveredConnectionId: null,
  showAllConnections: false,
  noteDrafts: {},
  errors: [],
  // a component, a connection and a step are never selected together: the side panel shows one of them
  selectComponent: (id) => set({ selectedComponentId: id, selectedConnectionId: null, selectedStepId: null }),
  selectConnection: (id) => set({ selectedConnectionId: id, selectedComponentId: null, selectedStepId: null }),
  selectStep: (id) => set({ selectedStepId: id, selectedComponentId: null, selectedConnectionId: null }),
  // a step belongs to one run, so picking another run lets it go
  selectScenario: (scenarioId, runId) => set({ selectedScenarioId: scenarioId, selectedRunId: runId, selectedStepId: null }),
  selectRun: (id) => set((s) => (s.selectedRunId === id ? s : { selectedRunId: id, selectedStepId: null })),
  hoverConnection: (id) => set({ hoveredConnectionId: id }),
  setShowAllConnections: (show) => set({ showAllConnections: show }),
  setNoteDraft: (draftKey, draft) =>
    set((s) => {
      const { [draftKey]: _dropped, ...rest } = s.noteDrafts;
      return { noteDrafts: draft === null ? rest : { ...rest, [draftKey]: draft } };
    }),
  resetSelection: () =>
    set({
      selectedComponentId: null,
      selectedConnectionId: null,
      selectedStepId: null,
      selectedScenarioId: null,
      selectedRunId: null,
      hoveredConnectionId: null,
    }),
  // the same failure repeating (a layout save after every drag) shows once
  reportError: (message) =>
    set((s) => (s.errors.some((e) => e.message === message) ? s : { errors: [...s.errors, { id: nextErrorId++, message }] })),
  dismissError: (id) => set((s) => ({ errors: s.errors.filter((e) => e.id !== id) })),
}));
