import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, getMarkdown, putJson, putMarkdown } from './client';
import { useWorkspaceName } from '../workspaces/useWorkspaceName';
import type {
  BoardComponent,
  BoardConnection,
  BoardLayout,
  ComponentDefinition,
  ConnectionDefinition,
  ConnectionState,
  Flow,
  RecordingManifest,
  WorkspaceList,
  WorkspaceView,
} from './types';

/** Where the viewer serves everything of one workspace. */
export function apiBase(ws: string): string {
  return `/api/w/${encodeURIComponent(ws)}`;
}

/** Every key of a workspace's data starts with its name, so the cache of one never shows in another. */
export const keys = {
  workspaces: ['workspaces'] as const,
  workspace: (ws: string) => ['w', ws, 'workspace'] as const,
  layout: (ws: string, board: string) => ['w', ws, 'layout', board] as const,
  note: (ws: string, targetId: string) => ['w', ws, 'note', targetId] as const,
  runs: (ws: string, scenarioId: string) => ['w', ws, 'runs', scenarioId] as const,
  flow: (ws: string, runId: string) => ['w', ws, 'flow', runId] as const,
};

/** The listed workspaces and the one opened last. */
export function useWorkspaces() {
  return useQuery({ queryKey: keys.workspaces, queryFn: () => getJson<WorkspaceList>('/api/workspaces') });
}

export function useWorkspace() {
  const ws = useWorkspaceName();
  return useQuery({ queryKey: keys.workspace(ws), queryFn: () => getJson<WorkspaceView>(`${apiBase(ws)}/workspace`) });
}

/** The saved layout of a board; `null` data means none was saved yet. */
export function useLayout(board: string) {
  const ws = useWorkspaceName();
  return useQuery({
    queryKey: keys.layout(ws, board),
    queryFn: () => getJson<BoardLayout>(`${apiBase(ws)}/boards/${board}/layout`, { notFoundIsNull: true }),
    staleTime: Infinity, // the board is the only writer while it is open
  });
}

export function useSaveLayout(board: string) {
  const ws = useWorkspaceName();
  const client = useQueryClient();
  return useMutation({
    meta: { action: 'Saving the board layout' },
    mutationFn: async (layout: BoardLayout) => {
      await putJson(`${apiBase(ws)}/boards/${board}/layout`, layout);
      return layout;
    },
    onSuccess: (layout) => client.setQueryData(keys.layout(ws, board), layout),
  });
}

/** Turns a discovered component into a curated one (the workspace gets its file). */
export function useAcceptComponent() {
  const ws = useWorkspaceName();
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
      return putJson(`${apiBase(ws)}/components/${component.id}`, def);
    },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workspace(ws) }),
  });
}

/** Accepts or hides a connection: the viewer persists the new state as the connection's workspace file. */
export function useSetConnectionState() {
  const ws = useWorkspaceName();
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
      return putJson(`${apiBase(ws)}/connections/${connection.id}`, def);
    },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workspace(ws) }),
  });
}

/** The Markdown note of a component or connection; `null` data means there is none. */
export function useNote(targetId: string) {
  const ws = useWorkspaceName();
  return useQuery({ queryKey: keys.note(ws, targetId), queryFn: () => getMarkdown(`${apiBase(ws)}/notes/${targetId}`) });
}

export function useSaveNote(targetId: string) {
  const ws = useWorkspaceName();
  const client = useQueryClient();
  return useMutation({
    meta: { inline: true }, // the notes editor shows its own error
    mutationFn: async (markdown: string) => {
      await putMarkdown(`${apiBase(ws)}/notes/${targetId}`, markdown);
      return markdown;
    },
    onSuccess: (markdown) => {
      client.setQueryData(keys.note(ws, targetId), markdown === '' ? null : markdown);
      // the node's note icon comes from the workspace view
      return client.invalidateQueries({ queryKey: keys.workspace(ws) });
    },
  });
}

/** The recorded runs of a scenario, newest first. */
export function useScenarioRuns(scenarioId: string | null) {
  const ws = useWorkspaceName();
  return useQuery({
    queryKey: keys.runs(ws, scenarioId ?? ''),
    queryFn: () => getJson<RecordingManifest[]>(`${apiBase(ws)}/scenarios/${scenarioId}/runs`),
    enabled: scenarioId !== null,
  });
}

export function useFlow(runId: string | null) {
  const ws = useWorkspaceName();
  return useQuery({
    queryKey: keys.flow(ws, runId ?? ''),
    queryFn: () => getJson<Flow>(`${apiBase(ws)}/runs/${runId}/flow`),
    enabled: runId !== null,
  });
}
