const WHEN = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function formatWhen(iso: string): string {
  return WHEN.format(new Date(iso));
}

const ACTIONS: Record<string, string> = {
  ORGANIZATION_CREATED: 'created the organization',
  FLAG_CREATED: 'created flag',
  FLAG_UPDATED: 'renamed or re-described flag',
  FLAG_DELETED: 'deleted flag',
  FLAG_CONFIG_UPDATED: 'changed flag',
  API_KEY_CREATED: 'created API key',
  API_KEY_REVOKED: 'revoked API key',
  MEMBER_ADDED: 'added member',
  MEMBER_ROLE_CHANGED: 'changed the role of',
  MEMBER_REMOVED: 'removed member',
};

const FIELDS: Record<string, string> = {
  fallthroughPercentage: 'default rollout %',
  enabled: 'switched on',
};

export function describeAction(action: string): string {
  return ACTIONS[action] ?? action.toLowerCase().replace(/_/g, ' ');
}

/**
 * The top-level fields that differ between two recorded states, as readable lines.
 * Nested values are compared whole: a changed rule list is shown as old and new.
 */
export function diffLines(before: unknown, after: unknown): { field: string; from: string; to: string }[] {
  const a = (before ?? {}) as Record<string, unknown>;
  const b = (after ?? {}) as Record<string, unknown>;
  const show = (value: unknown) => (value === undefined ? '–' : typeof value === 'string' ? value : JSON.stringify(value));
  return [...new Set([...Object.keys(a), ...Object.keys(b)])]
    .filter((field) => JSON.stringify(a[field]) !== JSON.stringify(b[field]))
    .map((field) => ({ field: FIELDS[field] ?? field, from: show(a[field]), to: show(b[field]) }));
}
