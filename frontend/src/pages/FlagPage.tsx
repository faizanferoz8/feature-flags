import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { SAMPLE_AUDIENCE } from '../audience';
import { useAuth } from '../auth';
import { AudienceGrid } from '../components/AudienceGrid';
import { ErrorNote } from '../components/ErrorNote';
import { newRule, RuleEditor } from '../components/RuleEditor';
import { Switch } from '../components/Switch';
import type { Draft, Evaluation, Flag, FlagConfig } from '../types';
import { useEnvironmentStream } from '../useEnvironmentStream';

const toDraft = (config: FlagConfig): Draft => ({
  enabled: config.enabled,
  rules: config.rules,
  fallthroughPercentage: config.fallthroughPercentage,
});

/** A rule with a condition that has no values cannot be evaluated, so it is not previewed or saved. */
const isComplete = (draft: Draft) =>
  draft.rules.every((rule) =>
    rule.conditions.every((condition) => condition.attribute.trim() !== '' && condition.values.length > 0),
  );

export function FlagPage() {
  const { key = '' } = useParams();
  const { session, can } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const editable = can('EDITOR');

  const [environment, setEnvironment] = useState(session?.environments[0]?.key ?? 'development');
  const { live } = useEnvironmentStream(environment);

  const flag = useQuery({ queryKey: ['flags', key], queryFn: () => api<Flag>(`/api/flags/${key}`) });
  const saved = flag.data?.environments.find((config) => config.environment === environment);

  // The draft remembers the saved state it started from. "Dirty" means the draft differs
  // from that starting point, not from whatever is saved now: when someone else saves, an
  // untouched draft follows them, and one with edits is kept and flagged as out of date.
  const [draft, setDraft] = useState<Draft | null>(null);
  const [base, setBase] = useState<{ environment: string; version: number; draft: Draft } | null>(null);
  const current = base !== null && base.environment === environment;
  const dirty = draft !== null && current && JSON.stringify(draft) !== JSON.stringify(base.draft);
  // Versions only go up, so a cached copy older than the draft's base is not news.
  const outOfDate = saved !== undefined && current && saved.version > base.version;

  const adopt = (config: FlagConfig) => {
    setDraft(toDraft(config));
    setBase({ environment: config.environment, version: config.version, draft: toDraft(config) });
  };

  useEffect(() => {
    if (saved && (!current || (outOfDate && !dirty))) adopt(saved);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saved, current, outOfDate, dirty]);

  const reload = () => {
    if (!saved) return;
    adopt(saved);
    save.reset();
  };

  const complete = draft !== null && isComplete(draft);
  const preview = useQuery({
    queryKey: ['preview', key, draft],
    queryFn: () =>
      api<{ evaluations: Evaluation[] }>(`/api/flags/${key}/preview`, {
        method: 'POST',
        body: { ...draft, contexts: SAMPLE_AUDIENCE },
      }),
    enabled: draft !== null && complete && flag.isSuccess,
    placeholderData: (previous) => previous,
  });

  const save = useMutation({
    mutationFn: () =>
      api<FlagConfig>(`/api/flags/${key}/environments/${environment}`, {
        method: 'PUT',
        body: { ...draft, version: base?.version },
      }),
    onSuccess: (config) => {
      queryClient.setQueryData<Flag>(['flags', key], (flag) =>
        flag && {
          ...flag,
          environments: flag.environments.map((each) => (each.environment === config.environment ? config : each)),
        },
      );
      adopt(config);
      void queryClient.invalidateQueries({ queryKey: ['flags'] });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) void queryClient.invalidateQueries({ queryKey: ['flags'] });
    },
  });

  const remove = useMutation({
    mutationFn: () => api<void>(`/api/flags/${key}`, { method: 'DELETE' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['flags'] });
      navigate('/flags');
    },
  });
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const ruleIds = useMemo(() => draft?.rules.map((rule) => rule.id) ?? [], [draft]);

  if (flag.isPending) return <p className="page-status">Loading…</p>;
  if (flag.error || !flag.data) {
    return (
      <>
        <ErrorNote error={flag.error} />
        <Link to="/flags">Back to flags</Link>
      </>
    );
  }
  if (!draft || !saved || !current) return <p className="page-status">Loading…</p>;

  const patch = (change: Partial<Draft>) => setDraft({ ...draft, ...change });

  return (
    <>
      <header className="page-head flag-head">
        <div>
          <Link to="/flags" className="back">
            Flags
          </Link>
          <h1>{flag.data.name}</h1>
          <code>{flag.data.key}</code>
          {flag.data.description && <p className="description">{flag.data.description}</p>}
        </div>
        <span className="live" data-live={live} title={live ? 'Changes made elsewhere appear here as they happen' : 'Reconnecting'}>
          {live ? 'Live' : 'Offline'}
        </span>
      </header>

      <div className="tabs" role="tablist" aria-label="Environment">
        {flag.data.environments.map((config) => (
          <button
            key={config.environment}
            type="button"
            role="tab"
            aria-selected={config.environment === environment}
            data-environment={config.environment}
            onClick={() => {
              save.reset();
              // Set together, so the page never renders one environment's draft under another's tab.
              setEnvironment(config.environment);
              adopt(config);
            }}
          >
            {config.environmentName}
            <span className="dot" data-on={config.enabled} />
          </button>
        ))}
      </div>

      {outOfDate && dirty && (
        <div className="notice" role="status">
          <p>Someone else saved a change to this flag while you were editing. Your edits are still here, but they are based on the old settings.</p>
          <button type="button" onClick={reload}>
            Discard my edits and load theirs
          </button>
        </div>
      )}

      <div className="flag-body">
        <div className="settings">
          <section className="panel master">
            <Switch
              checked={draft.enabled}
              disabled={!editable}
              label={`Flag is ${draft.enabled ? 'on' : 'off'} in ${saved.environmentName}`}
              onChange={(enabled) => patch({ enabled })}
            />
            <div>
              <h2>{draft.enabled ? 'On' : 'Off'} in {saved.environmentName}</h2>
              <p>
                {draft.enabled
                  ? 'Rules are checked in order. The first one that matches decides.'
                  : 'Nobody gets this flag here, whatever the rules below say.'}
              </p>
            </div>
          </section>

          <section className="panel" data-muted={!draft.enabled}>
            <h2>Targeting rules</h2>
            {draft.rules.length === 0 && <p className="hint">No rules. Everyone gets the default rollout below.</p>}
            <RuleEditor
              key={`${environment}:${base?.version}`}
              rules={draft.rules}
              disabled={!editable}
              onChange={(rules) => patch({ rules })}
            />
            {editable && (
              <button type="button" onClick={() => patch({ rules: [...draft.rules, newRule()] })}>
                Add a rule
              </button>
            )}
          </section>

          <section className="panel" data-muted={!draft.enabled}>
            <h2>{draft.rules.length > 0 ? 'Everyone else' : 'Default rollout'}</h2>
            <label className="rollout">
              <input
                type="range"
                min={0}
                max={100}
                value={draft.fallthroughPercentage}
                disabled={!editable}
                aria-label="Default rollout percentage"
                onChange={(event) => patch({ fallthroughPercentage: Number(event.target.value) })}
              />
              <output>{draft.fallthroughPercentage}%</output>
            </label>
          </section>

          {editable && (
            <div className="save-bar" data-dirty={dirty}>
              <button type="button" className="primary" disabled={!dirty || !complete || save.isPending} onClick={() => save.mutate()}>
                Save changes
              </button>
              {dirty && (
                <button type="button" onClick={reload}>
                  Discard
                </button>
              )}
              <span className="save-status">
                {!complete
                  ? 'Give every condition an attribute and at least one value.'
                  : dirty
                    ? `Unsaved changes to ${saved.environmentName}`
                    : save.isSuccess
                      ? 'Saved'
                      : ''}
              </span>
            </div>
          )}
          <ErrorNote error={save.error ?? preview.error} />

          {editable && (
            <section className="danger-zone">
              {confirmingDelete ? (
                <>
                  <p>
                    Delete <code>{flag.data.key}</code> from every environment? Code that still asks for it will get its
                    default value.
                  </p>
                  <button type="button" className="danger" disabled={remove.isPending} onClick={() => remove.mutate()}>
                    Delete flag
                  </button>
                  <button type="button" onClick={() => setConfirmingDelete(false)}>
                    Keep it
                  </button>
                  <ErrorNote error={remove.error} />
                </>
              ) : (
                <button type="button" className="link danger" onClick={() => setConfirmingDelete(true)}>
                  Delete this flag…
                </button>
              )}
            </section>
          )}
        </div>

        <AudienceGrid audience={SAMPLE_AUDIENCE} evaluations={complete ? preview.data?.evaluations : undefined} ruleIds={ruleIds} />
      </div>
    </>
  );
}
