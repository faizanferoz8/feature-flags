import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote } from '../components/ErrorNote';
import { Switch } from '../components/Switch';
import type { Flag, FlagConfig } from '../types';
import { useEnvironmentStream } from '../useEnvironmentStream';

function summary(config: FlagConfig): string {
  if (!config.enabled) return 'Off';
  const rules = config.rules.length;
  const base = config.fallthroughPercentage === 100 ? 'Everyone' : `${config.fallthroughPercentage}%`;
  return rules === 0 ? base : `${base}, ${rules} rule${rules === 1 ? '' : 's'}`;
}

function keyFromName(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64);
}

export function FlagsPage() {
  const { session, can } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const flags = useQuery({ queryKey: ['flags'], queryFn: () => api<Flag[]>('/api/flags') });
  // Production is the environment where an unnoticed change matters most.
  useEnvironmentStream('production');

  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [key, setKey] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const effectiveKey = key ?? keyFromName(name);

  const create = useMutation({
    mutationFn: () => api<Flag>('/api/flags', { method: 'POST', body: { key: effectiveKey, name, description } }),
    onSuccess: (flag) => {
      void queryClient.invalidateQueries({ queryKey: ['flags'] });
      navigate(`/flags/${flag.key}`);
    },
  });

  const toggle = useMutation({
    mutationFn: ({ flag, config }: { flag: Flag; config: FlagConfig }) =>
      api<FlagConfig>(`/api/flags/${flag.key}/environments/${config.environment}`, {
        method: 'PUT',
        body: {
          enabled: !config.enabled,
          rules: config.rules,
          fallthroughPercentage: config.fallthroughPercentage,
          version: config.version,
        },
      }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['flags'] }),
  });

  const onCreate = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  const createFields = create.error instanceof ApiError ? create.error.fields : {};

  return (
    <>
      <header className="page-head">
        <h1>Flags</h1>
        {can('EDITOR') && !creating && (
          <button type="button" className="primary" onClick={() => setCreating(true)}>
            New flag
          </button>
        )}
      </header>

      {creating && (
        <form className="panel create-flag" onSubmit={onCreate}>
          <label>
            Name
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="New checkout" required autoFocus />
          </label>
          <label>
            Key
            <input className="mono" value={effectiveKey} onChange={(e) => setKey(e.target.value)} required />
            <span className="hint">What your code asks for. It cannot be changed later.</span>
            {createFields.key && <span className="field-error">Key {createFields.key}</span>}
          </label>
          <label className="wide">
            Description
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="What this flag controls, and when it can be removed"
            />
          </label>
          {!createFields.key && <ErrorNote error={create.error} />}
          <div className="form-actions">
            <button type="submit" className="primary" disabled={create.isPending}>
              Create flag
            </button>
            <button type="button" onClick={() => setCreating(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}

      <ErrorNote error={flags.error ?? toggle.error} />

      {flags.data?.length === 0 && !creating && (
        <div className="empty">
          <h2>No flags yet</h2>
          <p>
            A flag starts switched off in every environment, so creating one changes nothing until you turn it on.
          </p>
        </div>
      )}

      {flags.data && flags.data.length > 0 && (
        <table className="flags">
          <thead>
            <tr>
              <th scope="col">Flag</th>
              {session?.environments.map((environment) => (
                <th scope="col" key={environment.key}>
                  {environment.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {flags.data.map((flag) => (
              <tr key={flag.key}>
                <th scope="row">
                  <Link to={`/flags/${flag.key}`}>{flag.name}</Link>
                  <code>{flag.key}</code>
                </th>
                {flag.environments.map((config) => (
                  <td key={config.environment} data-environment={config.environment}>
                    <div className="env-cell">
                      <Switch
                        checked={config.enabled}
                        disabled={!can('EDITOR') || toggle.isPending}
                        label={`${flag.name} in ${config.environmentName}`}
                        onChange={() => toggle.mutate({ flag, config })}
                      />
                      <span>{summary(config)}</span>
                    </div>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
