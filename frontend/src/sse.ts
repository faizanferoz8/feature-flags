export interface ServerEvent {
  name: string;
  id: string | null;
  data: string;
}

/**
 * Turns chunks of a text/event-stream into events. Chunks arrive split anywhere, so
 * the parser keeps whatever follows the last complete line until the rest shows up.
 */
export function createEventParser(onEvent: (event: ServerEvent) => void) {
  let buffer = '';
  let name: string | null = null;
  let id: string | null = null;
  let data: string[] | null = null;

  return (chunk: string) => {
    buffer += chunk;
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      if (line === '') {
        if (data) onEvent({ name: name ?? 'message', id, data: data.join('\n') });
        name = null;
        data = null;
        continue;
      }
      if (line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') name = value;
      else if (field === 'id') id = value;
      else if (field === 'data') (data ??= []).push(value);
    }
  };
}

/**
 * Follows a stream with fetch rather than EventSource, because EventSource cannot send
 * an Authorization header. Reconnects until the signal is aborted.
 */
export async function followStream(
  url: string,
  token: string,
  signal: AbortSignal,
  onEvent: (event: ServerEvent) => void,
  onStatus: (connected: boolean) => void,
) {
  let delay = 500;
  while (!signal.aborted) {
    try {
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal,
      });
      if (response.status === 401 || response.status === 403) return;
      if (!response.ok || !response.body) throw new Error(`status ${response.status}`);
      onStatus(true);
      delay = 500;
      const parse = createEventParser(onEvent);
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        parse(value);
      }
    } catch {
      // Fall through to reconnect, unless we were asked to stop.
    }
    onStatus(false);
    if (signal.aborted) return;
    await new Promise((resolve) => setTimeout(resolve, delay));
    delay = Math.min(delay * 2, 15000);
  }
}
