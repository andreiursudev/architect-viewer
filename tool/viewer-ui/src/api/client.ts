export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function failure(res: Response, method: string, path: string): Promise<ApiError> {
  let detail = '';
  try {
    detail = ((await res.json()) as { error?: string }).error ?? '';
  } catch {
    // no JSON body: the status line says enough
  }
  return new ApiError(res.status, `${method} ${path} failed (${res.status})${detail ? `: ${detail}` : ''}`);
}

/** GET a JSON resource; a 404 is `null` when [notFoundIsNull] is set (a layout that was never saved, say). */
export async function getJson<T>(path: string, opts: { notFoundIsNull: true }): Promise<T | null>;
export async function getJson<T>(path: string): Promise<T>;
export async function getJson<T>(path: string, opts?: { notFoundIsNull?: boolean }): Promise<T | null> {
  const res = await fetch(path, { headers: { Accept: 'application/json' } });
  if (res.status === 404 && opts?.notFoundIsNull) return null;
  if (!res.ok) throw await failure(res, 'GET', path);
  return (await res.json()) as T;
}

export async function putJson(path: string, body: unknown): Promise<void> {
  const res = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await failure(res, 'PUT', path);
}

/** Notes are `text/markdown`; a missing note is `null`. */
export async function getMarkdown(path: string): Promise<string | null> {
  const res = await fetch(path, { headers: { Accept: 'text/markdown' } });
  if (res.status === 404) return null;
  if (!res.ok) throw await failure(res, 'GET', path);
  return res.text();
}

/** An empty body clears the note on the backend. */
export async function putMarkdown(path: string, markdown: string): Promise<void> {
  const res = await fetch(path, { method: 'PUT', headers: { 'Content-Type': 'text/markdown' }, body: markdown });
  if (!res.ok) throw await failure(res, 'PUT', path);
}
