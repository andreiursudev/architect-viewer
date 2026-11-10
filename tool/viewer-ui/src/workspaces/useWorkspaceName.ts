import { useParams } from 'react-router';

/** The workspace the current `/w/:ws/...` route addresses. Everything below `WorkspaceScope` reads its data under it. */
export function useWorkspaceName(): string {
  const { ws } = useParams();
  if (ws === undefined) throw new Error('useWorkspaceName was used outside a /w/:ws route');
  return ws;
}
