import { describe, expect, it } from 'vitest';
import { diffLines } from './format';

describe('diffLines', () => {
  it('lists only the fields that changed', () => {
    expect(
      diffLines(
        { enabled: false, fallthroughPercentage: 100, rules: [] },
        { enabled: true, fallthroughPercentage: 100, rules: [] },
      ),
    ).toEqual([{ field: 'switched on', from: 'false', to: 'true' }]);
  });

  it('treats a missing side as empty, for creations and deletions', () => {
    expect(diffLines(null, { role: 'EDITOR' })).toEqual([{ field: 'role', from: '–', to: 'EDITOR' }]);
    expect(diffLines({ role: 'ADMIN' }, null)).toEqual([{ field: 'role', from: 'ADMIN', to: '–' }]);
  });
});
