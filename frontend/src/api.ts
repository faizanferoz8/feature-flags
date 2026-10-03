const TOKEN_KEY = 'flags.token';

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

/** A failed request, with the server's own explanation when it sent one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

let onSignedOut: () => void = () => {};
export function setSignedOutHandler(handler: () => void) {
  onSignedOut = handler;
}

function fallbackMessage(status: number): string {
  if (status === 403) return 'Your role does not allow this.';
  if (status === 404) return 'That no longer exists.';
  if (status >= 500) return 'The server could not complete this. Try again in a moment.';
  return `The request failed (${status}).`;
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const token = tokenStore.get();
  let response: Response;
  try {
    response = await fetch(path, {
      method: init.method ?? 'GET',
      headers: {
        ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'The server could not be reached. Check your connection and try again.');
  }
  if (response.status === 401 && token && !path.startsWith('/api/auth/')) {
    tokenStore.clear();
    onSignedOut();
    throw new ApiError(401, 'Your session has ended. Sign in again.');
  }
  if (!response.ok) {
    // Errors arrive as RFC 9457 problem documents; security rejections have no body.
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? fallbackMessage(response.status), problem?.fields ?? {});
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}
