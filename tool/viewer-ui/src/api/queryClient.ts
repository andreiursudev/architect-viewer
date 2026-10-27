import { MutationCache, QueryClient } from '@tanstack/react-query';
import { useUiStore } from '../store/uiStore';

/**
 * The one place a failed mutation becomes something the user sees: the error banner. A mutation names what it was
 * doing in `meta.action`; one that shows its failure next to its own form sets `meta.inline` and is skipped here.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: true, staleTime: 5_000 } },
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.meta?.inline) return;
        const action = typeof mutation.meta?.action === 'string' ? mutation.meta.action : 'The request';
        useUiStore.getState().reportError(`${action} failed: ${error.message}`);
      },
    }),
  });
}
