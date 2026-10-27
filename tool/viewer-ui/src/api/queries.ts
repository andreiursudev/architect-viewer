import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, getMarkdown, putJson, putMarkdown } from './client';
import type {
  BoardComponent,
  BoardConnection,
  BoardLayout,
  ComponentDefinition,
  ConnectionDefinition,
  ConnectionState,
  Flow,
  RecordingManifest,
  WorkspaceView,
} from './types';

export const keys = {
  workspace: ['workspace'] as const,
  layout: (board: string) => ['layout', board] as const,
  note: (targetId: string) => ['note', targetId] as const,
  runs: (scenarioId: string) => ['runs', scenarioId] as const,
  flow: (runId: string) => ['flow', runId] as const,
};

export function useWorkspace() {
  return useQuery({ queryKey: keys.workspace, queryFn: () => getJson<WorkspaceView>('/api/workspace') });
}

/** The saved layout of a board; `null` data means none was saved yet. */
export function useLayout(board: string) {
  return useQuery({
    queryKey: keys.layout(board),
    queryFn: () => getJson<BoardLayout>(`/api/boards/${board}/layout`, { notFoundIsNull: true }),
    staleTime: Infinity, // the board is the only writer while it is open
  });
}

export function useSaveLayout(board: string) {
  const client = useQueryClient();
  return useMutation({
    meta: { action: 'Saving the board layout' },
    mutationFn: async (layout: BoardLayout) => {
      await putJson(`/api/boards/${board}/layout`, layout);
      return layout;
    },
    onSuccess: (layout) => client.setQueryData(keys.layout(board), layout),
  });
}

/** Turns a discovered component into a curated one (the workspace gets its file). */
export function useAcceptComponent() {
  const client = useQueryClient();
  return useMutation({
    meta: { action: 'Accepting the component' },
    mutationFn: (component: BoardComponent) => {
      const def: ComponentDefinition = {
        id: component.id,
        type: component.type,
        displayName: component.displayName,
        tags: [],
      };
      return putJson(`/api/components/${component.id}`, def);
    },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workspace }),
  });
}

/** Accepts or hides a connection: the viewer persists the new state as the connection's workspace file. */
export function useSetConnectionState() {
  const client = useQueryClient();
  return useMutation({
    meta: { action: 'Updating the connection' },
    mutationFn: ({ connection, state }: { connection: BoardConnection; state: ConnectionState }) => {
      const def: ConnectionDefinition = {
        from: connection.from,
        to: connection.to,
        kind: connection.kind,
        label: connection.label ?? null,
        state,
      };
      return putJson(`/api/connections/${connection.id}`, def);
    },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workspace }),
  });
}

/** The Markdown note of a component or connection; `null` data means there is none. */
export function useNote(targetId: string) {
  return useQuery({ queryKey: keys.note(targetId), queryFn: () => getMarkdown(`/api/notes/${targetId}`) });
}

export function useSaveNote(targetId: string) {
  const client = useQueryClient();
  return useMutation({
    meta: { inline: true }, // the notes editor shows its own error
    mutationFn: async (markdown: string) => {
      await putMarkdown(`/api/notes/${targetId}`, markdown);
      return markdown;
    },
    onSuccess: (markdown) => {
      client.setQueryData(keys.note(targetId), markdown === '' ? null : markdown);
      // the node's note icon comes from the workspace view
      return client.invalidateQueries({ queryKey: keys.workspace });
    },
  });
}

/** The recorded runs of a scenario, newest first. */
export function useScenarioRuns(scenarioId: string | null) {
  return useQuery({
    queryKey: keys.runs(scenarioId ?? ''),
    queryFn: () => getJson<RecordingManifest[]>(`/api/scenarios/${scenarioId}/runs`),
    enabled: scenarioId !== null,
  });
}

export function useFlow(runId: string | null) {
  return useQuery({
    queryKey: keys.flow(runId ?? ''),
    queryFn: () => getJson<Flow>(`/api/runs/${runId}/flow`),
    enabled: runId !== null,
  });
}
