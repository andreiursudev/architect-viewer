import { create } from 'zustand';

/** Client-side view state shared across the board, the side panel and (later) the scenario and sequence views. */
export interface UiState {
  selectedComponentId: string | null;
  selectedConnectionId: string | null;
  selectedRunId: string | null;
  hoveredConnectionId: string | null;
  selectComponent: (id: string | null) => void;
  selectConnection: (id: string | null) => void;
  selectRun: (id: string | null) => void;
  hoverConnection: (id: string | null) => void;
}

export const useUiStore = create<UiState>((set) => ({
  selectedComponentId: null,
  selectedConnectionId: null,
  selectedRunId: null,
  hoveredConnectionId: null,
  // a component and a connection are never selected together: the side panel shows one of them
  selectComponent: (id) => set({ selectedComponentId: id, selectedConnectionId: null }),
  selectConnection: (id) => set({ selectedConnectionId: id, selectedComponentId: null }),
  selectRun: (id) => set({ selectedRunId: id }),
  hoverConnection: (id) => set({ hoveredConnectionId: id }),
}));
