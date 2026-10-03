import { describe, expect, it } from 'vitest';
import { createEventParser, type ServerEvent } from './sse';

function collect(chunks: string[]): ServerEvent[] {
  const events: ServerEvent[] = [];
  const parse = createEventParser((event) => events.push(event));
  chunks.forEach(parse);
  return events;
}

describe('createEventParser', () => {
  it('reads the name, id and data of an event', () => {
    expect(collect(['event:put\nid:7\ndata:{"a":1}\n\n'])).toEqual([{ name: 'put', id: '7', data: '{"a":1}' }]);
  });

  it('reassembles an event split across chunks at any point', () => {
    const whole = 'event:put\nid:12\ndata:{"revision":12}\n\n';
    for (let cut = 1; cut < whole.length; cut++) {
      expect(collect([whole.slice(0, cut), whole.slice(cut)])).toEqual([
        { name: 'put', id: '12', data: '{"revision":12}' },
      ]);
    }
  });

  it('ignores keep-alive comments', () => {
    expect(collect([':keep-alive\n\n', 'data:x\n\n'])).toEqual([{ name: 'message', id: null, data: 'x' }]);
  });

  it('does not deliver an event until its blank line arrives', () => {
    expect(collect(['event:put\ndata:{"half":'])).toEqual([]);
  });

  it('accepts CRLF line endings', () => {
    expect(collect(['event:put\r\ndata:1\r\n\r\n'])).toEqual([{ name: 'put', id: null, data: '1' }]);
  });
});
